package com.greenopal.quickgames.games.tictactoe

import com.greenopal.quickgames.core.engine.EngineValidation
import com.greenopal.quickgames.core.model.GameStatus
import com.greenopal.quickgames.core.model.PlayerRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class TicTacToeEngineTest {

    private val players = listOf(
        PlayerRef("uid-x", "Player X", 0),
        PlayerRef("uid-o", "Player O", 1)
    )
    private val config = "{}"

    private fun initial(): String = TicTacToeDefinition.initialStateJson(config, players, 7L)

    private fun play(stateJson: String, cell: Int): Pair<String, com.greenopal.quickgames.core.engine.EngineResult> {
        val active = TicTacToeEngine.activePlayer(stateJson, config)
        val submission = """{"cell":$cell}"""
        val validation = TicTacToeEngine.validate(stateJson, submission, active, config)
        assertIs<EngineValidation.Valid>(validation, "move $cell should be valid")
        val result = TicTacToeEngine.apply(stateJson, submission, active, 0L, config)
        return result.stateJson to result
    }

    @Test
    fun initialStateStartsWithX() {
        val state = initial()
        assertEquals("uid-x", TicTacToeEngine.activePlayer(state, config))
    }

    @Test
    fun xWinsOnTopRow() {
        var state = initial()
        var result: com.greenopal.quickgames.core.engine.EngineResult? = null
        for (cell in listOf(0, 3, 1, 4, 2)) {
            val (next, r) = play(state, cell)
            state = next
            result = r
        }
        assertEquals(GameStatus.COMPLETE, result!!.status)
        assertEquals("uid-x", result.winnerUid)
    }

    @Test
    fun fullBoardWithoutWinnerIsDraw() {
        var state = initial()
        var result: com.greenopal.quickgames.core.engine.EngineResult? = null
        for (cell in listOf(0, 4, 8, 1, 7, 6, 2, 5, 3)) {
            val (next, r) = play(state, cell)
            state = next
            result = r
        }
        assertEquals(GameStatus.COMPLETE, result!!.status)
        assertNull(result.winnerUid)
    }

    @Test
    fun rejectsMoveFromInactivePlayer() {
        val validation = TicTacToeEngine.validate(initial(), """{"cell":0}""", "uid-o", config)
        assertEquals(listOf("notActivePlayer"), (validation as EngineValidation.Invalid).errors)
    }

    @Test
    fun rejectsOccupiedCell() {
        val (state, _) = play(initial(), 4)
        val validation = TicTacToeEngine.validate(state, """{"cell":4}""", "uid-o", config)
        assertEquals(listOf("cellOccupied"), (validation as EngineValidation.Invalid).errors)
    }

    @Test
    fun rejectsCellOutOfRange() {
        val validation = TicTacToeEngine.validate(initial(), """{"cell":9}""", "uid-x", config)
        assertEquals(listOf("cellOutOfRange"), (validation as EngineValidation.Invalid).errors)
    }

    @Test
    fun rejectsMalformedSubmission() {
        val validation = TicTacToeEngine.validate(initial(), """{"nope":true}""", "uid-x", config)
        assertEquals(listOf("malformedSubmission"), (validation as EngineValidation.Invalid).errors)
    }

    @Test
    fun rejectsMoveAfterGameComplete() {
        var state = initial()
        for (cell in listOf(0, 3, 1, 4, 2)) {
            state = play(state, cell).first
        }
        val validation = TicTacToeEngine.validate(state, """{"cell":5}""", "uid-o", config)
        assertEquals(listOf("gameComplete"), (validation as EngineValidation.Invalid).errors)
    }

    @Test
    fun applyIsDeterministic() {
        val a = TicTacToeEngine.apply(initial(), """{"cell":4}""", "uid-x", 1L, config)
        val b = TicTacToeEngine.apply(initial(), """{"cell":4}""", "uid-x", 99L, config)
        assertEquals(a.stateJson, b.stateJson)
    }
}
