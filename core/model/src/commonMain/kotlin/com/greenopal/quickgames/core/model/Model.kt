package com.greenopal.quickgames.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class GameType(val id: String) {
    @SerialName("tictactoe") TIC_TAC_TOE("tictactoe"),
    @SerialName("miniRpg") MINI_RPG("miniRpg"),
    @SerialName("riskLite") RISK_LITE("riskLite"),
    @SerialName("quickWw2") QUICK_WW2("quickWw2");

    companion object {
        fun fromId(id: String): GameType = entries.first { it.id == id }
    }
}

@Serializable
data class PlayerRef(
    val uid: String,
    val displayName: String,
    val playerIndex: Int
)

@Serializable
enum class GameStatus(val id: String) {
    @SerialName("active") ACTIVE("active"),
    @SerialName("complete") COMPLETE("complete"),
    @SerialName("forfeited") FORFEITED("forfeited");

    companion object {
        fun fromId(id: String): GameStatus = entries.first { it.id == id }
    }
}

@Serializable
data class MatchResult(
    val status: GameStatus,
    val winnerUid: String?
)
