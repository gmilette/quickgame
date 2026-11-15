package com.greenopal.quickgames.games.rps

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
data class RpsRound(
    val choices: Map<String, String>,
    val winner: String?
)

@Serializable
data class RpsState(
    val players: List<String>,
    val scores: Map<String, Int>,
    val targetWins: Int,
    val firstMover: String,
    val pendingBy: String?,
    val pendingChoice: String?,
    val history: List<RpsRound>,
    val winner: String?
) {
    fun other(uid: String): String = players.first { it != uid }
    fun activePlayer(): String = pendingBy?.let { other(it) } ?: firstMover
}

@Serializable
data class RpsSubmission(
    val choice: String
)

@Serializable
data class RpsConfig(
    val targetWins: Int = 2
)

object RpsJson {
    val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
}

object RpsEngine : GameEngine {

    val choices = listOf("rock", "paper", "scissors")
    private val beats = mapOf("rock" to "scissors", "paper" to "rock", "scissors" to "paper")

    override fun activePlayer(stateJson: String, configJson: String): String =
        decode(stateJson).activePlayer()

    override fun validate(
        stateJson: String,
        submissionJson: String,
        playerId: String,
        configJson: String
    ): EngineValidation {
        val state = decode(stateJson)
        val errors = buildList {
            if (state.winner != null) {
                add("gameComplete")
            } else if (state.activePlayer() != playerId) {
                add("notActivePlayer")
            }
            val submission = decodeSubmission(submissionJson)
            when {
                submission == null -> add("malformedSubmission")
                submission.choice !in choices -> add("unknownChoice")
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
        val choice = RpsJson.json.decodeFromString<RpsSubmission>(submissionJson).choice
        val firstUid = state.pendingBy
        val firstChoice = state.pendingChoice
        if (firstUid == null || firstChoice == null) {
            val waiting = state.copy(pendingBy = playerId, pendingChoice = choice)
            return EngineResult(RpsJson.json.encodeToString(waiting), null, GameStatus.ACTIVE, null)
        }
        val roundWinner = when {
            choice == firstChoice -> null
            beats.getValue(choice) == firstChoice -> playerId
            else -> firstUid
        }
        val scores = if (roundWinner == null) {
            state.scores
        } else {
            state.scores + (roundWinner to state.scores.getValue(roundWinner) + 1)
        }
        val gameWinner = scores.entries.firstOrNull { it.value >= state.targetWins }?.key
        val resolved = state.copy(
            scores = scores,
            firstMover = state.other(state.firstMover),
            pendingBy = null,
            pendingChoice = null,
            history = state.history + RpsRound(mapOf(firstUid to firstChoice, playerId to choice), roundWinner),
            winner = gameWinner
        )
        return EngineResult(
            stateJson = RpsJson.json.encodeToString(resolved),
            logJson = null,
            status = if (gameWinner != null) GameStatus.COMPLETE else GameStatus.ACTIVE,
            winnerUid = gameWinner
        )
    }

    private fun decode(stateJson: String): RpsState = RpsJson.json.decodeFromString(stateJson)

    private fun decodeSubmission(submissionJson: String): RpsSubmission? =
        runCatching { RpsJson.json.decodeFromString<RpsSubmission>(submissionJson) }.getOrNull()
}

object RpsDefinition : GameDefinition {
    override val gameType = GameType.ROCK_PAPER_SCISSORS
    override val displayName = "Rock Paper Scissors"
    override val configVariants = listOf("standard")
    override val engineVersion = 1
    override val turnDeadlineHours = 72

    override fun configJson(variant: String): String = RpsJson.json.encodeToString(RpsConfig())

    override fun engine(): GameEngine = RpsEngine

    override fun initialStateJson(configJson: String, players: List<PlayerRef>, seed: Long): String {
        val config = RpsJson.json.decodeFromString<RpsConfig>(configJson)
        val ordered = players.sortedBy { it.playerIndex }.map { it.uid }
        val state = RpsState(
            players = ordered,
            scores = ordered.associateWith { 0 },
            targetWins = config.targetWins,
            firstMover = ordered[0],
            pendingBy = null,
            pendingChoice = null,
            history = emptyList(),
            winner = null
        )
        return RpsJson.json.encodeToString(state)
    }

    override fun publicStateJson(stateJson: String): String {
        val state = RpsJson.json.decodeFromString<RpsState>(stateJson)
        return RpsJson.json.encodeToString(state.copy(pendingChoice = null))
    }
}
