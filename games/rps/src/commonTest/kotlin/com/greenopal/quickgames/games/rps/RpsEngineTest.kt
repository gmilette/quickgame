package com.greenopal.quickgames.games.rps

import com.greenopal.quickgames.core.engine.EngineResult
import com.greenopal.quickgames.core.engine.EngineValidation
import com.greenopal.quickgames.core.model.GameStatus
import com.greenopal.quickgames.core.model.PlayerRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class RpsEngineTest {

    private val players = listOf(PlayerRef("a", "A", 0), PlayerRef("b", "B", 1))
    private val config = RpsDefinition.configJson("standard")

    private fun initial(): String = RpsDefinition.initialStateJson(config, players, 1L)

    private fun play(stateJson: String, choice: String): EngineResult {
        val active = RpsEngine.activePlayer(stateJson, config)
        val submission = """{"choice":"$choice"}"""
        assertIs<EngineValidation.Valid>(RpsEngine.validate(stateJson, submission, active, config))
        return RpsEngine.apply(stateJson, submission, active, 0L, config)
    }

    private fun playRound(stateJson: String, first: String, second: String): EngineResult =
        play(play(stateJson, first).stateJson, second)

    @Test
    fun firstChoiceIsHiddenFromPublicState() {
        val afterFirst = play(initial(), "rock").stateJson
        val public = RpsDefinition.publicStateJson(afterFirst)
        assertFalse(public.contains("rock"))
        assertEquals("b", RpsEngine.activePlayer(public, config))
    }

    @Test
    fun rockBeatsScissorsAndFirstMoverAlternates() {
        val result = playRound(initial(), "rock", "scissors")
        val state = RpsJson.json.decodeFromString<RpsState>(result.stateJson)
        assertEquals(1, state.scores.getValue("a"))
        assertEquals("b", state.firstMover)
        assertEquals(GameStatus.ACTIVE, result.status)
    }

    @Test
    fun tieScoresNothing() {
        val result = playRound(initial(), "paper", "paper")
        val state = RpsJson.json.decodeFromString<RpsState>(result.stateJson)
        assertEquals(0, state.scores.values.sum())
        assertNull(state.history.single().winner)
    }

    @Test
    fun firstToTwoWinsTheGame() {
        val round1 = playRound(initial(), "rock", "scissors")
        val round2 = playRound(round1.stateJson, "rock", "paper")
        assertEquals(GameStatus.COMPLETE, round2.status)
        assertEquals("a", round2.winnerUid)
    }

    @Test
    fun rejectsUnknownChoiceWrongPlayerAndFinishedGame() {
        assertEquals(
            listOf("unknownChoice"),
            (RpsEngine.validate(initial(), """{"choice":"lizard"}""", "a", config) as EngineValidation.Invalid).errors
        )
        assertEquals(
            listOf("notActivePlayer"),
            (RpsEngine.validate(initial(), """{"choice":"rock"}""", "b", config) as EngineValidation.Invalid).errors
        )
        val round1 = playRound(initial(), "rock", "scissors")
        val done = playRound(round1.stateJson, "rock", "paper")
        assertEquals(
            listOf("gameComplete"),
            (RpsEngine.validate(done.stateJson, """{"choice":"rock"}""", "a", config) as EngineValidation.Invalid).errors
        )
    }
}
