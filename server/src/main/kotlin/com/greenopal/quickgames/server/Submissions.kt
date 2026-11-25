package com.greenopal.quickgames.server

import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import com.greenopal.quickgames.core.model.GameStatus
import java.util.logging.Logger

private val log = Logger.getLogger("Submissions")

fun secretStateRef(db: Firestore, gameId: String) = db.document("games/$gameId/secret/state")

fun secretSeedRef(db: Firestore, gameId: String) = db.document("games/$gameId/secret/seed")

fun pollSubmissions(db: Firestore, notifier: Notifier) {
    val pending = db.collectionGroup("submissions").limit(200).get().get()
    for (doc in pending.documents) {
        if (doc.get("status") != null) continue
        val gameRef = doc.reference.parent.parent ?: continue
        try {
            handleSubmission(db, notifier, gameRef.id, doc.id)
        } catch (e: Exception) {
            log.severe("handleSubmission failed for ${gameRef.id}/${doc.id}: $e")
            e.printStackTrace()
        }
    }
}

fun handleSubmission(db: Firestore, notifier: Notifier, gameId: String, subId: String) {
    val notices = mutableListOf<Notice>()
    db.runTransaction { tx ->
        notices.clear()
        val gameRef = db.document("games/$gameId")
        val gameSnap = tx.get(gameRef).get()
        if (!gameSnap.exists()) return@runTransaction null
        val gameData = gameSnap.data ?: return@runTransaction null
        val secretRef = secretStateRef(db, gameId)
        val secretSnap = tx.get(secretRef).get()
        val seedSnap = tx.get(secretSeedRef(db, gameId)).get()
        val publicGame = GameData.from(gameData)
        val game = publicGame.copy(
            stateJson = secretSnap.getString("stateJson") ?: publicGame.stateJson,
            seed = seedSnap.getLong("seed") ?: publicGame.seed
        )

        val subRef = db.document("games/$gameId/submissions/$subId")
        val subSnap = tx.get(subRef).get()
        if (!subSnap.exists() || subSnap.get("status") != null) return@runTransaction null

        val payloadJson = subSnap.get("payloadJson") as? String

        fun reject(errors: List<String>) {
            tx.set(
                db.document("games/$gameId/logs/$subId-rejected"),
                mapOf(
                    "type" to "rejection",
                    "errors" to errors,
                    "payloadJson" to payloadJson,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
            tx.delete(subRef)
            log.info("submission rejected $gameId/$subId $errors")
        }

        if (payloadJson == null) {
            reject(listOf("missingPayload"))
            return@runTransaction null
        }

        when (val resolution = resolveSubmission(game, subId, payloadJson)) {
            is SubmissionResolution.Rejected -> reject(resolution.errors)
            is SubmissionResolution.Applied -> {
                val def = gameRegistry.byId(game.gameType)
                val publicStateJson = def?.publicStateJson(resolution.stateJson) ?: resolution.stateJson
                if (secretSnap.exists() || publicStateJson != resolution.stateJson) {
                    tx.set(secretRef, mapOf("stateJson" to resolution.stateJson))
                }
                if (def != null) {
                    writeViews(db, tx, def, gameId, game.playerUids, resolution.stateJson, resolution.round)
                }
                tx.update(
                    gameRef,
                    mapOf(
                        "stateJson" to publicStateJson,
                        "status" to resolution.status.id,
                        "winner" to resolution.winnerUid,
                        "activePlayerId" to resolution.activePlayerId,
                        "round" to resolution.round,
                        "turnDeadline" to if (resolution.status == GameStatus.ACTIVE) {
                            deadlineTimestamp(resolution.turnDeadlineHours)
                        } else {
                            null
                        }
                    )
                )
                if (resolution.logJson != null) {
                    tx.set(
                        db.document("games/$gameId/logs/$subId"),
                        mapOf(
                            "logJson" to resolution.logJson,
                            "seed" to resolution.seedUsed,
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
                tx.update(subRef, mapOf("status" to "applied"))
                notices += submissionNotices(gameId, gameData, game, resolution)
                log.info("submission applied $gameId/$subId status=${resolution.status}")
            }
        }
        null
    }.get()
    notifier.sendAll(notices)
}

private fun submissionNotices(
    gameId: String,
    gameData: Map<String, Any?>,
    game: GameData,
    resolution: SubmissionResolution.Applied
): List<Notice> {
    val mover = game.activePlayerId ?: return emptyList()
    val names = playerNames(gameData)
    val moverName = names[mover] ?: "Your opponent"
    val title = gameDisplayName(game.gameType)
    val opponent = game.playerUids.firstOrNull { it != mover } ?: return emptyList()
    return when {
        resolution.status != GameStatus.ACTIVE -> {
            val outcome = when (resolution.winnerUid) {
                null -> "Draw against $moverName"
                opponent -> "You beat $moverName!"
                else -> "$moverName won"
            }
            listOf(Notice(opponent, "$title: game over", outcome, gameId))
        }
        resolution.activePlayerId == opponent ->
            listOf(Notice(opponent, "$title: your turn", "$moverName has moved", gameId))
        else -> emptyList()
    }
}
