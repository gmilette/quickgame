package com.greenopal.quickgames.core.model

import kotlinx.coroutines.flow.Flow

interface PlatformClient {
    suspend fun signIn(): String
    fun games(uid: String): Flow<List<GameDoc>>
    fun game(gameId: String): Flow<GameDoc?>
    suspend fun joinQueue(gameType: GameType, variant: String, uid: String, displayName: String)
    suspend fun leaveQueue(gameType: GameType, variant: String, uid: String)
    suspend fun submit(gameId: String, round: Int, payloadJson: String)
}
