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

    /** How a variant is named in the lobby, for games with more than one. */
    fun variantLabel(variant: String): String = variant
    fun engine(): GameEngine
    fun initialStateJson(configJson: String, players: List<PlayerRef>, seed: Long): String
    fun publicStateJson(stateJson: String): String = stateJson

    /**
     * What one player may see of the state, for games with information hidden between players
     * (fog of war). Null means every player sees [publicStateJson]. When a game returns a view,
     * the server stores it where only that player can read it, and the client shows it in place
     * of the public state.
     */
    fun playerStateJson(stateJson: String, uid: String): String? = null
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
