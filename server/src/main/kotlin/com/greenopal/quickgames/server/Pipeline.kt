package com.greenopal.quickgames.server

import com.greenopal.quickgames.core.engine.EngineValidation
import com.greenopal.quickgames.core.engine.seedForRound
import com.greenopal.quickgames.core.model.GameStatus

data class GameData(
    val gameType: String,
    val variant: String,
    val playerUids: List<String>,
    val activePlayerId: String?,
    val round: Int,
    val stateJson: String,
    val status: String,
    val seed: Long
) {
    companion object {
        fun from(data: Map<String, Any?>): GameData = GameData(
            gameType = data["gameType"] as String,
            variant = data["variant"] as String,
            playerUids = (data["playerUids"] as List<*>).map { it as String },
            activePlayerId = data["activePlayerId"] as String?,
            round = (data["round"] as Number).toInt(),
            stateJson = data["stateJson"] as String,
            status = data["status"] as String,
            seed = (data["seed"] as Number).toLong()
        )
    }
}

sealed interface SubmissionResolution {
    data class Rejected(val errors: List<String>) : SubmissionResolution
    data class Applied(
        val stateJson: String,
        val logJson: String?,
        val status: GameStatus,
        val winnerUid: String?,
        val activePlayerId: String?,
        val round: Int,
        val seedUsed: Long,
        val turnDeadlineHours: Int
    ) : SubmissionResolution
}

fun resolveSubmission(game: GameData, submissionId: String, payloadJson: String): SubmissionResolution {
    val def = gameRegistry.byId(game.gameType)
        ?: return SubmissionResolution.Rejected(listOf("unknownGameType"))
    if (game.status != "active") return SubmissionResolution.Rejected(listOf("gameNotActive"))

    val separator = submissionId.indexOf("-")
    if (separator < 1) return SubmissionResolution.Rejected(listOf("malformedSubmissionId"))
    val round = submissionId.substring(0, separator).toIntOrNull()
        ?: return SubmissionResolution.Rejected(listOf("malformedSubmissionId"))
    val uid = submissionId.substring(separator + 1)

    if (round != game.round) return SubmissionResolution.Rejected(listOf("staleRound"))
    if (uid != game.activePlayerId) return SubmissionResolution.Rejected(listOf("notActivePlayer"))

    val configJson = def.configJson(game.variant)
    val engine = def.engine()
    val validation = engine.validate(game.stateJson, payloadJson, uid, configJson)
    if (validation is EngineValidation.Invalid) return SubmissionResolution.Rejected(validation.errors)

    val seedUsed = seedForRound(game.seed, game.round)
    val result = engine.apply(game.stateJson, payloadJson, uid, seedUsed, configJson)
    val activePlayerId = if (result.status == GameStatus.ACTIVE) {
        engine.activePlayer(result.stateJson, configJson)
    } else {
        null
    }

    return SubmissionResolution.Applied(
        stateJson = result.stateJson,
        logJson = result.logJson,
        status = result.status,
        winnerUid = result.winnerUid,
        activePlayerId = activePlayerId,
        round = game.round + 1,
        seedUsed = seedUsed,
        turnDeadlineHours = def.turnDeadlineHours
    )
}
