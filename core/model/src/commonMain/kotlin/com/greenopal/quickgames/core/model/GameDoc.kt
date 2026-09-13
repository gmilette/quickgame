package com.greenopal.quickgames.core.model

data class GameDoc(
    val id: String,
    val gameType: GameType,
    val variant: String,
    val players: List<PlayerRef>,
    val activePlayerId: String?,
    val round: Int,
    val stateJson: String,
    val status: GameStatus,
    val winnerUid: String?,
    val turnDeadlineMillis: Long?,
    val createdAtMillis: Long?
) {
    fun opponentOf(uid: String): PlayerRef? = players.firstOrNull { it.uid != uid }
    fun isMyTurn(uid: String): Boolean = status == GameStatus.ACTIVE && activePlayerId == uid
}
