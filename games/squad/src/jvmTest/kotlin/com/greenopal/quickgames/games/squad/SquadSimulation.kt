package com.greenopal.quickgames.games.squad

import com.greenopal.quickgames.core.engine.SeededRng
import com.greenopal.quickgames.core.engine.seedForRound
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Bot-vs-bot games through the same views the players get, to check that games end and to see
 * how balanced each mode is. Prints a report; only fails if a game breaks.
 */
class SquadSimulation {

    private val games = 300
    private val players = listOf("a", "b")

    private class Result(val winner: String?, val turns: Int, val kills: Int, val cores: Int)

    private fun playGame(mode: String, seed: Long, first: SquadBot, second: SquadBot): Result {
        var state = SquadDefinition.initialState(mode, players)
        while (!state.complete) {
            val uid = state.activePlayer()
            val bot = if (uid == "a") first else second
            val turn = bot.turn(SquadRules.view(state, uid), uid)
            assertTrue(SquadRules.errors(state, uid, turn).isEmpty(), "bot sent an invalid turn")
            state = SquadRules.play(state, uid, turn, SeededRng(seedForRound(seed, state.turn)))
        }
        val kills = state.units.count { !it.alive }
        return Result(state.winner, state.turn, kills, state.cores.count { it.destroyed })
    }

    private fun report(mode: String, first: SquadBot, second: SquadBot) {
        val results = (1..games).map { playGame(mode, it.toLong() * 7919, first, second) }
        val aWins = results.count { it.winner == "a" }
        val bWins = results.count { it.winner == "b" }
        val draws = results.count { it.winner == null }
        val turns = results.map { it.turns }.sorted()
        val timeouts = results.count { it.turns >= SquadContent.ROUNDS * 2 }
        println(
            "$mode ${first.name} vs ${second.name}: a ${pct(aWins)} b ${pct(bWins)} draw ${pct(draws)} | " +
                "median turns ${turns[turns.size / 2]}, ran out of time ${pct(timeouts)} | " +
                "avg kills ${"%.1f".format(results.map { it.kills }.average())}" +
                if (mode == SquadContent.RAID) ", avg cores ${"%.2f".format(results.map { it.cores }.average())}" else ""
        )
    }

    private fun pct(n: Int): String = "${n * 100 / games}%"

    @Test
    fun simulate() {
        val steady = SquadBot("steady")
        val rushing = SquadBot("rushing", reserve = 0.0)
        val cautious = SquadBot("cautious", reserve = 0.5)
        val noGrenades = SquadBot("noGrenades", grenades = false)
        println("== squad simulation, $games games each")
        report(SquadContent.SKIRMISH, steady, steady)
        report(SquadContent.SKIRMISH, rushing, cautious)
        report(SquadContent.SKIRMISH, cautious, rushing)
        report(SquadContent.SKIRMISH, steady, noGrenades)
        report(SquadContent.RAID, steady, steady)
        report(SquadContent.RAID, rushing, steady)
        report(SquadContent.RAID, cautious, steady)
    }
}
