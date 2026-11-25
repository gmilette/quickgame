package com.greenopal.quickgames.games.spiceroad

import com.greenopal.quickgames.core.engine.EngineValidation
import com.greenopal.quickgames.core.model.GameStatus
import com.greenopal.quickgames.core.model.PlayerRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpiceEngineTest {

    private val players = listOf(PlayerRef("a", "A", 0), PlayerRef("b", "B", 1))
    private val balanced = BotStyle("balanced")

    private fun start(seed: Long, variant: String = "hours12"): SpiceState {
        val config = SpiceRoadDefinition.configJson(variant)
        return SpiceEngine.decode(SpiceRoadDefinition.initialStateJson(config, players, seed))
    }

    private fun play(state: SpiceState, uid: String, turn: SpiceTurn): SpiceState {
        val outcome = SpiceRules.play(state, uid, turn)
        return assertNotNull(outcome.state, "rejected: ${outcome.errors}")
    }

    @Test
    fun layoutHasTheRightSpicesOnEachSide() {
        for (seed in 1L..50L) {
            val tiles = start(seed).tiles
            val sources = tiles.filter { it.kind == SpiceContent.SOURCE }
            assertEquals(22, sources.size)
            val sideA = sources.filter { SpiceContent.x(it.id) + SpiceContent.y(it.id) < 4 }.map { it.spice!! }
            val sideB = sources.filter { SpiceContent.x(it.id) + SpiceContent.y(it.id) > 4 }.map { it.spice!! }
            assertEquals(sideA.filter { !it.royal }.sorted(), sideB.filter { !it.royal }.sorted())
            assertEquals(Spice.SAFFRON, tiles[SpiceContent.ROYAL_A].spice)
            assertEquals(Spice.FRANKINCENSE, tiles[SpiceContent.ROYAL_B].spice)
            assertEquals(4, sources.count { it.spice!!.price == 8 })
        }
    }

    @Test
    fun botGamesRunToTheFinalHourThroughTheEngine() {
        for (variant in SpiceRoadDefinition.configVariants) {
            val config = SpiceRoadDefinition.configJson(variant)
            for (seed in 1L..30L) {
                var stateJson = SpiceRoadDefinition.initialStateJson(config, players, seed)
                var status = GameStatus.ACTIVE
                var turns = 0
                while (status == GameStatus.ACTIVE) {
                    val uid = SpiceEngine.activePlayer(stateJson, config)
                    val turn = SpiceJson.json.encodeToString(SpiceBot.turn(SpiceEngine.decode(stateJson), uid, balanced))
                    assertIs<EngineValidation.Valid>(SpiceEngine.validate(stateJson, turn, uid, config), "seed $seed turn $turns")
                    val result = SpiceEngine.apply(stateJson, turn, uid, seed, config)
                    stateJson = result.stateJson
                    status = result.status
                    turns += 1
                }
                val hours = SpiceEngine.decode(stateJson).totalHours
                assertEquals(hours * 2, turns)
            }
        }
    }

    @Test
    fun turnOrderSnakesEachHour() {
        var state = start(1)
        val order = mutableListOf<String>()
        repeat(6) {
            val uid = state.activePlayer()
            order += uid
            state = play(state, uid, SpiceTurn(SpiceContent.WAIT))
        }
        assertEquals(listOf("a", "b", "b", "a", "a", "b"), order)
    }

    @Test
    fun aNearTileDeliversEveryHourAndARoyalTileEveryThree() {
        var state = start(3)
        state = play(state, "a", SpiceTurn(SpiceContent.EXPLORE, tile = 1, assignments = listOf(1)))
        state = play(state, "b", SpiceTurn(SpiceContent.WAIT))
        assertEquals(SpiceContent.START_CAPACITY, state.traders.getValue("a").stock.values.sum())
        assertEquals(1, state.lastDeliveries.size)
        assertEquals(3, SpiceRules.tripHours(state, "a", SpiceContent.ROYAL_A))
    }

    @Test
    fun royalTileIsReservedAndCostsATribute() {
        var state = start(4)
        state = play(state, "a", SpiceTurn(SpiceContent.EXPLORE, tile = 1))
        state = play(state, "b", SpiceTurn(SpiceContent.WAIT))
        state = play(state, "b", SpiceTurn(SpiceContent.WAIT))
        state = play(state, "a", SpiceTurn(SpiceContent.EXPLORE, tile = 2))
        assertTrue(SpiceRules.claimable(state, "a").any { it.id == SpiceContent.ROYAL_A })
        assertEquals(
            listOf("paymentTooSmall"),
            SpiceRules.play(state, "a", SpiceTurn(SpiceContent.EXPLORE, tile = SpiceContent.ROYAL_A)).errors
        )
        assertTrue(SpiceRules.claimable(state, "b").none { it.id == SpiceContent.ROYAL_A })
    }

    @Test
    fun enemyTilesLengthenOrCutARoute() {
        val base = start(5)
        fun own(state: SpiceState, uid: String, vararg ids: Int) =
            state.copy(tiles = state.tiles.map { if (it.id in ids) it.copy(owner = uid) else it })
        assertEquals(3, SpiceRules.routeLength(base, "a", 3))
        assertEquals(5, SpiceRules.routeLength(own(base, "b", 2), "a", 3))
        assertNull(SpiceRules.routeLength(own(base, "b", 1, 5), "a", 3))
    }

    @Test
    fun monumentPaymentNeedsEnoughValueAndVariety() {
        val base = start(6)
        fun with(stock: Map<Spice, Int>, monuments: Int) =
            base.copy(traders = base.traders + ("a" to Trader(stock = stock, monuments = monuments)))
        val rich = mapOf(Spice.SAFFRON to 2, Spice.PEPPER to 3)
        assertEquals(
            listOf("paymentNeedsMoreSpices"),
            SpiceRules.play(with(rich, 1), "a", SpiceTurn(SpiceContent.MONUMENT, payment = mapOf(Spice.SAFFRON to 2))).errors
        )
        val built = play(
            with(rich, 1), "a",
            SpiceTurn(SpiceContent.MONUMENT, payment = mapOf(Spice.SAFFRON to 1, Spice.PEPPER to 2))
        )
        assertEquals(2, built.traders.getValue("a").monuments)
        assertEquals(mapOf(Spice.SAFFRON to 1, Spice.PEPPER to 1), built.traders.getValue("a").stock)
    }

    @Test
    fun exchangeGivesHalfValueAndOnlyRevealedSpices() {
        val base = start(7)
        val state = base.copy(traders = base.traders + ("a" to Trader(stock = mapOf(Spice.SAFFRON to 1))))
        val revealed = base.tiles.first { it.faceUp && it.spice != null && it.spice.price == 1 }.spice!!
        val swapped = play(
            state, "a",
            SpiceTurn(SpiceContent.WAIT, exchanges = listOf(Exchange(mapOf(Spice.SAFFRON to 1), mapOf(revealed to 5))))
        )
        assertEquals(mapOf(revealed to 5), swapped.traders.getValue("a").stock)
        val tooMuch = SpiceTurn(SpiceContent.WAIT, exchanges = listOf(Exchange(mapOf(Spice.SAFFRON to 1), mapOf(revealed to 6))))
        assertEquals(listOf("exchangeTooCheap"), SpiceRules.play(state, "a", tooMuch).errors)
        val hiddenSpice = Spice.entries.first { spice -> base.tiles.none { it.faceUp && it.spice == spice } }
        val unseen = SpiceTurn(SpiceContent.WAIT, exchanges = listOf(Exchange(mapOf(Spice.SAFFRON to 1), mapOf(hiddenSpice to 1))))
        assertEquals(listOf("exchangeSpiceNotRevealed"), SpiceRules.play(state, "a", unseen).errors)
    }

    @Test
    fun publicStateHidesFaceDownSpices() {
        val config = SpiceRoadDefinition.configJson("hours12")
        val full = SpiceRoadDefinition.initialStateJson(config, players, 8)
        val public = SpiceEngine.decode(SpiceRoadDefinition.publicStateJson(full))
        assertTrue(public.tiles.filter { !it.faceUp }.all { it.spice == null })
        assertTrue(public.tiles.filter { it.faceUp && it.kind == SpiceContent.SOURCE }.all { it.spice != null })
    }
}
