package com.greenopal.quickgames.core.engine

import com.greenopal.quickgames.core.model.GameStatus
import com.greenopal.quickgames.core.model.GameType
import com.greenopal.quickgames.core.model.PlayerRef

interface GameEngine {
    fun activePlayer(stateJson: String, configJson: String): String
    fun validate(stateJson: String, submissionJson: String, playerId: String, configJson: String): EngineValidation
    fun apply(stateJson: String, submissionJson: String, playerId: String, seed: Long, configJson: String): EngineResult
}

sealed interface EngineValidation {
    data object Valid : EngineValidation
    data class Invalid(val errors: List<String>) : EngineValidation
}

data class EngineResult(
    val stateJson: String,
    val logJson: String?,
    val status: GameStatus,
    val winnerUid: String?
)

interface GameDefinition {
    val gameType: GameType
    val displayName: String
    val configVariants: List<String>
    val engineVersion: Int
    val turnDeadlineHours: Int
    fun configJson(variant: String): String
    fun engine(): GameEngine
    fun initialStateJson(configJson: String, players: List<PlayerRef>, seed: Long): String
    fun publicStateJson(stateJson: String): String = stateJson
}

class GameRegistry(definitions: List<GameDefinition>) {
    val all: Map<GameType, GameDefinition> = definitions.associateBy { it.gameType }

    operator fun get(gameType: GameType): GameDefinition =
        all.getValue(gameType)

    fun byId(gameTypeId: String): GameDefinition? =
        GameType.entries.firstOrNull { it.id == gameTypeId }?.let { all[it] }
}

fun seedForRound(baseSeed: Long, round: Int): Long =
    (baseSeed xor (round.toLong() * 0x9e3779b9L)) and 0xFFFFFFFFL
