package com.greenopal.quickgames.core.model

import kotlinx.coroutines.flow.Flow

interface PlatformClient {
    suspend fun signIn(): String
    suspend fun loadDisplayName(uid: String): String?
    suspend fun saveDisplayName(uid: String, displayName: String)
    suspend fun savePushToken(uid: String, token: String)
    fun games(uid: String): Flow<List<GameDoc>>
    fun game(gameId: String): Flow<GameDoc?>
    fun openGames(): Flow<List<OpenGame>>
    suspend fun hostGame(gameType: GameType, variant: String, uid: String, displayName: String)
    suspend fun cancelHostedGame(gameType: GameType, uid: String)
    suspend fun joinOpenGame(openGameId: String, uid: String, displayName: String)
    suspend fun submit(gameId: String, round: Int, payloadJson: String)
    suspend fun resign(gameId: String, uid: String)
}
