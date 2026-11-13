package com.greenopal.quickgames.server

import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import com.greenopal.quickgames.core.model.GameStatus
import java.util.logging.Logger

private val log = Logger.getLogger("Submissions")

fun startSubmissionPollLoop(db: Firestore, intervalMillis: Long = 1000) {
    val thread = Thread({
        while (true) {
            try {
                pollSubmissions(db)
            } catch (e: Exception) {
                log.severe("submission poll failed: $e")
                e.printStackTrace()
            }
            Thread.sleep(intervalMillis)
        }
    }, "submissions-poll")
    thread.isDaemon = true
    thread.start()
    log.info("submission poll loop started")
}

private fun pollSubmissions(db: Firestore) {
    val pending = db.collectionGroup("submissions").limit(200).get().get()
    for (doc in pending.documents) {
        if (doc.get("status") != null) continue
        val gameRef = doc.reference.parent.parent ?: continue
        try {
            handleSubmission(db, gameRef.id, doc.id)
        } catch (e: Exception) {
            log.severe("handleSubmission failed for ${gameRef.id}/${doc.id}: $e")
            e.printStackTrace()
        }
    }
}

fun handleSubmission(db: Firestore, gameId: String, subId: String) {
    db.runTransaction { tx ->
        val gameRef = db.document("games/$gameId")
        val gameSnap = tx.get(gameRef).get()
        if (!gameSnap.exists()) return@runTransaction null
        val gameData = gameSnap.data ?: return@runTransaction null
        val game = GameData.from(gameData)

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
                tx.update(
                    gameRef,
                    mapOf(
                        "stateJson" to resolution.stateJson,
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
                log.info("submission applied $gameId/$subId status=${resolution.status}")
            }
        }
        null
    }.get()
}
