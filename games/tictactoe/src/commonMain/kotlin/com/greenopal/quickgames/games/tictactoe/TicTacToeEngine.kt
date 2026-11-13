package com.greenopal.quickgames.games.tictactoe

import com.greenopal.quickgames.core.engine.EngineResult
import com.greenopal.quickgames.core.engine.EngineValidation
import com.greenopal.quickgames.core.engine.GameDefinition
import com.greenopal.quickgames.core.engine.GameEngine
import com.greenopal.quickgames.core.model.GameStatus
import com.greenopal.quickgames.core.model.GameType
import com.greenopal.quickgames.core.model.PlayerRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TicTacToeState(
    val cells: List<String>,
    val marks: Map<String, String>,
    val next: String,
    val winnerMark: String?,
    val line: List<Int>?,
    val moveCount: Int
)

@Serializable
data class TicTacToeSubmission(
    val cell: Int
)

object TicTacToeJson {
    val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
}

object TicTacToeEngine : GameEngine {

    private val lines = listOf(
        listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 8),
        listOf(0, 3, 6), listOf(1, 4, 7), listOf(2, 5, 8),
        listOf(0, 4, 8), listOf(2, 4, 6)
    )

    override fun activePlayer(stateJson: String, configJson: String): String {
        val state = decode(stateJson)
        return state.marks.getValue(state.next)
    }

    override fun validate(
        stateJson: String,
        submissionJson: String,
        playerId: String,
        configJson: String
    ): EngineValidation {
        val state = decode(stateJson)
        val errors = buildList {
            if (state.winnerMark != null || state.moveCount == 9) {
                add("gameComplete")
            } else if (state.marks.getValue(state.next) != playerId) {
                add("notActivePlayer")
            }
            val submission = runCatching {
                TicTacToeJson.json.decodeFromString<TicTacToeSubmission>(submissionJson)
            }.getOrNull()
            when {
                submission == null -> add("malformedSubmission")
                submission.cell !in 0..8 -> add("cellOutOfRange")
                state.cells[submission.cell].isNotEmpty() -> add("cellOccupied")
            }
        }
        return if (errors.isEmpty()) EngineValidation.Valid else EngineValidation.Invalid(errors)
    }

    override fun apply(
        stateJson: String,
        submissionJson: String,
        playerId: String,
        seed: Long,
        configJson: String
    ): EngineResult {
        val state = decode(stateJson)
        val submission = TicTacToeJson.json.decodeFromString<TicTacToeSubmission>(submissionJson)
        val mark = state.next
        val cells = state.cells.toMutableList().also { it[submission.cell] = mark }
        val winLine = lines.firstOrNull { line -> line.all { cells[it] == mark } }
        val moveCount = state.moveCount + 1
        val newState = state.copy(
            cells = cells,
            next = if (mark == "X") "O" else "X",
            winnerMark = if (winLine != null) mark else null,
            line = winLine,
            moveCount = moveCount
        )
        val status = if (winLine != null || moveCount == 9) GameStatus.COMPLETE else GameStatus.ACTIVE
        return EngineResult(
            stateJson = TicTacToeJson.json.encodeToString(newState),
            logJson = null,
            status = status,
            winnerUid = if (winLine != null) state.marks.getValue(mark) else null
        )
    }

    private fun decode(stateJson: String): TicTacToeState =
        TicTacToeJson.json.decodeFromString(stateJson)
}

object TicTacToeDefinition : GameDefinition {
    override val gameType = GameType.TIC_TAC_TOE
    override val displayName = "Tic-Tac-Toe"
    override val configVariants = listOf("standard")
    override val engineVersion = 1
    override val turnDeadlineHours = 72

    override fun configJson(variant: String): String = "{}"

    override fun engine(): GameEngine = TicTacToeEngine

    override fun initialStateJson(configJson: String, players: List<PlayerRef>, seed: Long): String {
        val ordered = players.sortedBy { it.playerIndex }
        val state = TicTacToeState(
            cells = List(9) { "" },
            marks = mapOf("X" to ordered[0].uid, "O" to ordered[1].uid),
            next = "X",
            winnerMark = null,
            line = null,
            moveCount = 0
        )
        return TicTacToeJson.json.encodeToString(state)
    }
}
