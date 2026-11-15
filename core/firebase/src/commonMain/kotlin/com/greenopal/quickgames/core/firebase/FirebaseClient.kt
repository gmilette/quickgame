package com.greenopal.quickgames.core.firebase

import com.greenopal.quickgames.core.model.GameDoc
import com.greenopal.quickgames.core.model.GameStatus
import com.greenopal.quickgames.core.model.GameType
import com.greenopal.quickgames.core.model.OpenGame
import com.greenopal.quickgames.core.model.PlatformClient
import com.greenopal.quickgames.core.model.PlayerRef
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.FirebaseAuth
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.firestore.FieldValue
import dev.gitlive.firebase.firestore.FirebaseFirestore
import dev.gitlive.firebase.firestore.Timestamp
import dev.gitlive.firebase.firestore.firestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

@Serializable
data class PlayerRefDto(
    val uid: String,
    val displayName: String,
    val playerIndex: Int
)

@Serializable
data class GameDocDto(
    val gameType: String,
    val variant: String,
    val players: List<PlayerRefDto>,
    val playerUids: List<String>,
    val activePlayerId: String? = null,
    val round: Int = 0,
    val stateJson: String,
    val status: String,
    val winner: String? = null,
    val engineVersion: Int = 1,
    val turnDeadline: Timestamp? = null,
    val createdAt: Timestamp? = null
)

@Serializable
data class OpenGameDto(
    val gameType: String,
    val variant: String,
    val hostUid: String,
    val hostName: String = "Player",
    val guestUid: String? = null,
    val createdAt: Timestamp? = null
)

@Serializable
data class UserDto(
    val displayName: String? = null
)

class FirebaseClient(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore
) : PlatformClient {

    override suspend fun signIn(): String =
        auth.currentUser?.uid ?: auth.signInAnonymously().user?.uid
            ?: error("anonymous sign-in returned no user")

    override fun games(uid: String): Flow<List<GameDoc>> =
        firestore.collection("games")
            .where { "playerUids" contains uid }
            .snapshots
            .map { snapshot ->
                snapshot.documents
                    .mapNotNull { doc -> runCatching { toGameDoc(doc.id, doc.data<GameDocDto>()) }.getOrNull() }
                    .sortedByDescending { it.createdAtMillis ?: 0L }
            }

    override fun game(gameId: String): Flow<GameDoc?> =
        firestore.collection("games").document(gameId)
            .snapshots
            .map { doc ->
                if (doc.exists) runCatching { toGameDoc(doc.id, doc.data<GameDocDto>()) }.getOrNull() else null
            }

    override suspend fun loadDisplayName(uid: String): String? {
        val doc = firestore.collection("users").document(uid).get()
        return if (doc.exists) doc.data<UserDto>().displayName else null
    }

    override suspend fun saveDisplayName(uid: String, displayName: String) {
        firestore.collection("users").document(uid).set(mapOf("displayName" to displayName))
    }

    override suspend fun savePushToken(uid: String, token: String) {
        firestore.collection("users").document(uid)
            .collection("private").document("push")
            .set(mapOf("token" to token, "updatedAt" to FieldValue.serverTimestamp))
    }

    override fun openGames(): Flow<List<OpenGame>> =
        firestore.collection("openGames")
            .snapshots
            .map { snapshot ->
                snapshot.documents
                    .mapNotNull { doc -> runCatching { toOpenGame(doc.id, doc.data<OpenGameDto>()) }.getOrNull() }
                    .sortedBy { it.createdAtMillis ?: Long.MAX_VALUE }
            }

    override suspend fun hostGame(gameType: GameType, variant: String, uid: String, displayName: String) {
        openGame(gameType, uid).set(
            mapOf(
                "gameType" to gameType.id,
                "variant" to variant,
                "hostUid" to uid,
                "hostName" to displayName,
                "guestUid" to null,
                "guestName" to null,
                "createdAt" to FieldValue.serverTimestamp
            )
        )
    }

    override suspend fun cancelHostedGame(gameType: GameType, uid: String) {
        openGame(gameType, uid).delete()
    }

    override suspend fun joinOpenGame(openGameId: String, uid: String, displayName: String) {
        firestore.collection("openGames").document(openGameId)
            .update(mapOf("guestUid" to uid, "guestName" to displayName))
    }

    override suspend fun resign(gameId: String, uid: String) {
        firestore.collection("games").document(gameId)
            .collection("resignations").document(uid)
            .set(mapOf("createdAt" to FieldValue.serverTimestamp))
    }

    override suspend fun submit(gameId: String, round: Int, payloadJson: String) {
        firestore.collection("games").document(gameId)
            .collection("submissions").document("$round-${auth.currentUser?.uid}")
            .set(
                mapOf(
                    "payloadJson" to payloadJson,
                    "submittedAt" to FieldValue.serverTimestamp
                )
            )
    }

    private fun openGame(gameType: GameType, uid: String) =
        firestore.collection("openGames").document("${uid}_${gameType.id}")

    private fun toOpenGame(id: String, dto: OpenGameDto): OpenGame? {
        val gameType = GameType.fromIdOrNull(dto.gameType) ?: return null
        return OpenGame(
            id = id,
            gameType = gameType,
            variant = dto.variant,
            hostUid = dto.hostUid,
            hostName = dto.hostName,
            guestUid = dto.guestUid,
            createdAtMillis = dto.createdAt?.let { it.seconds * 1000 + it.nanoseconds / 1_000_000 }
        )
    }

    private fun toGameDoc(id: String, dto: GameDocDto): GameDoc = GameDoc(
        id = id,
        gameType = GameType.fromId(dto.gameType),
        variant = dto.variant,
        players = dto.players.map { PlayerRef(it.uid, it.displayName, it.playerIndex) },
        activePlayerId = dto.activePlayerId,
        round = dto.round,
        stateJson = dto.stateJson,
        status = GameStatus.fromId(dto.status),
        winnerUid = dto.winner,
        turnDeadlineMillis = dto.turnDeadline?.let { it.seconds * 1000 + it.nanoseconds / 1_000_000 },
        createdAtMillis = dto.createdAt?.let { it.seconds * 1000 + it.nanoseconds / 1_000_000 }
    )

    companion object {
        fun create(useEmulators: Boolean, emulatorHost: String): FirebaseClient {
            val auth = Firebase.auth
            val firestore = Firebase.firestore
            if (useEmulators) {
                auth.useEmulator(emulatorHost, 9099)
                firestore.useEmulator(emulatorHost, 8080)
            }
            return FirebaseClient(auth, firestore)
        }
    }
}
