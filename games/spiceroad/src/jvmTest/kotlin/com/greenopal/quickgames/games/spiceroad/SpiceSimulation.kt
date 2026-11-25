package com.greenopal.quickgames.games.spiceroad

import com.greenopal.quickgames.core.model.PlayerRef
import java.io.File
import kotlin.random.Random
import kotlin.test.Test

class SpiceSimulation {

    private val players = listOf(PlayerRef("a", "A", 0), PlayerRef("b", "B", 1))
    private val games = 400

    private val styles = listOf(
        BotStyle("balanced"),
        BotStyle("noRoyal", wantRoyal = false),
        BotStyle("buildEarly", buildFrom = 0.25),
        BotStyle("buildLate", buildFrom = 0.75),
        BotStyle("noUpgrades", upgrades = false)
    )

    private fun playGame(seed: Long, variant: String, a: BotStyle, b: BotStyle): SpiceState {
        val config = SpiceRoadDefinition.configJson(variant)
        var state = SpiceEngine.decode(SpiceRoadDefinition.initialStateJson(config, players, seed))
        while (!state.complete) {
            val uid = state.activePlayer()
            val turn = SpiceBot.turn(state, uid, if (uid == "a") a else b)
            state = SpiceRules.play(state, uid, turn).state!!
        }
        return state
    }

    private fun randomTurn(state: SpiceState, uid: String, rng: Random, allowRoyal: Boolean): SpiceTurn {
        val trader = state.traders.getValue(uid)
        val options = mutableListOf<SpiceTurn>()
        for (tile in SpiceRules.claimable(state, uid)) {
            val cost = SpiceRules.claimCost(tile)
            if (cost > 0 && !allowRoyal) continue
            SpiceBot.pay(trader, cost, 0)?.let { options += SpiceTurn(SpiceContent.EXPLORE, tile = tile.id, payment = it) }
        }
        for (action in listOf(SpiceContent.HIRE, SpiceContent.LOADS, SpiceContent.CAMELS)) {
            val cost = SpiceRules.upgradeCost(trader, action) ?: continue
            SpiceBot.pay(trader, cost, 0)?.let { options += SpiceTurn(action, payment = it) }
        }
        val monument = SpiceBot.pay(trader, SpiceContent.monumentCost(trader.monuments), SpiceContent.monumentKinds(trader.monuments))
            ?.let { SpiceTurn(SpiceContent.MONUMENT, payment = it) }
        if (monument != null) repeat(3) { options += monument }
        options += SpiceTurn(SpiceContent.WAIT)
        return SpiceBot.withAssignments(state, uid, options[rng.nextInt(options.size)])
    }

    private class Line(val monuments: Int, val stock: Int, val actions: List<String>)

    private fun bestSoloLine(variant: String, allowRoyal: Boolean, playouts: Int): Line {
        val config = SpiceRoadDefinition.configJson(variant)
        val rng = Random(42)
        var best = Line(-1, 0, emptyList())
        repeat(playouts) {
            var state = SpiceEngine.decode(SpiceRoadDefinition.initialStateJson(config, players, 1))
            val actions = mutableListOf<String>()
            while (!state.complete) {
                val uid = state.activePlayer()
                val turn = if (uid == "a") randomTurn(state, uid, rng, allowRoyal) else SpiceTurn(SpiceContent.WAIT)
                if (uid == "a") actions += turn.action + (turn.tile?.let { ":" + state.tiles[it].spice } ?: "")
                state = SpiceRules.play(state, uid, turn).state!!
            }
            val trader = state.traders.getValue("a")
            if (trader.monuments > best.monuments || (trader.monuments == best.monuments && trader.stockValue() > best.stock)) {
                best = Line(trader.monuments, trader.stockValue(), actions)
            }
        }
        return best
    }

    @Test
    fun bestLines() {
        val out = StringBuilder()
        for (variant in SpiceRoadDefinition.configVariants) {
            for (allowRoyal in listOf(true, false)) {
                val line = bestSoloLine(variant, allowRoyal, 60000)
                out.appendLine("$variant royal=$allowRoyal: best of 60000 random solo lines = ${line.monuments} monuments, ${line.stock} left over")
                out.appendLine("  " + line.actions.joinToString(" "))
            }
        }
        File("build/spice-best.txt").writeText(out.toString())
    }

    @Test
    fun trace() {
        val out = StringBuilder()
        for (variant in SpiceRoadDefinition.configVariants) {
            val config = SpiceRoadDefinition.configJson(variant)
            var state = SpiceEngine.decode(SpiceRoadDefinition.initialStateJson(config, players, 1))
            out.appendLine("== $variant, seed 1, balanced vs balanced ==")
            while (!state.complete) {
                val uid = state.activePlayer()
                val hour = state.hour
                val turn = SpiceBot.turn(state, uid, styles[0])
                state = SpiceRules.play(state, uid, turn).state!!
                val t = state.traders.getValue(uid)
                val spice = turn.tile?.let { state.tiles[it].spice }
                out.appendLine(
                    "h%-2d %s %-8s %-12s paid %-2d | stock %-3d caravans %d load %d camel %d monuments %d | routes %s"
                        .format(
                            hour, uid, turn.action, spice ?: "", SpiceRules.value(turn.payment),
                            t.stockValue(), t.caravans.size, t.loadLevel, t.camelLevel, t.monuments,
                            t.caravans.map { c -> c.next?.let { "${state.tiles[it].spice}/${SpiceRules.tripHours(state, uid, it)}h" } }
                        )
                )
            }
        }
        File("build/spice-trace.txt").writeText(out.toString())
    }

    @Test
    fun report() {
        val out = StringBuilder()
        for (variant in SpiceRoadDefinition.configVariants) {
            out.appendLine("== $variant, $games games per pairing ==")
            out.appendLine("Mirror matches (same style in both seats):")
            for (style in styles) {
                val results = (1L..games).map { playGame(it, variant, style, style) }
                val monuments = results.flatMap { r -> r.traders.values.map { it.monuments } }
                val aWins = results.count { it.winner == "a" }
                val bWins = results.count { it.winner == "b" }
                val leftover = results.flatMap { r -> r.traders.values.map { it.stockValue() } }.average()
                val tiles = results.flatMap { r -> r.players.map { SpiceRules.ownedSources(r, it).size } }.average()
                out.appendLine(
                    "  %-11s monuments avg %.2f (min %d, max %d) | seat A wins %d, seat B wins %d, draws %d | tiles %.1f | leftover value %.1f"
                        .format(style.name, monuments.average(), monuments.min(), monuments.max(), aWins, bWins, games - aWins - bWins, tiles, leftover)
                )
            }
            out.appendLine("Each style against balanced (both seats, win% for the style / draw%):")
            for (style in styles.drop(1)) {
                var wins = 0
                var draws = 0
                var monuments = 0
                for (seed in 1L..games) {
                    val asA = playGame(seed, variant, style, styles[0])
                    val asB = playGame(seed, variant, styles[0], style)
                    wins += (if (asA.winner == "a") 1 else 0) + (if (asB.winner == "b") 1 else 0)
                    draws += (if (asA.winner == null) 1 else 0) + (if (asB.winner == null) 1 else 0)
                    monuments += asA.traders.getValue("a").monuments + asB.traders.getValue("b").monuments
                }
                out.appendLine(
                    "  %-11s wins %.0f%%, draws %.0f%%, monuments avg %.2f"
                        .format(style.name, 100.0 * wins / (2 * games), 100.0 * draws / (2 * games), monuments / (2.0 * games))
                )
            }
        }
        File("build/spice-sim.txt").writeText(out.toString())
    }
}
