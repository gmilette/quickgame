package com.greenopal.quickgames.server

import com.google.cloud.Timestamp
import com.google.cloud.firestore.Firestore
import java.util.logging.Logger

private val log = Logger.getLogger("DeadlineSweep")

fun deadlineTimestamp(hours: Int): Timestamp =
    Timestamp.ofTimeSecondsAndNanos(System.currentTimeMillis() / 1000 + hours * 3600L, 0)

fun sweepExpiredGames(db: Firestore, notifier: Notifier): Int {
    val notices = mutableListOf<Notice>()
    val now = Timestamp.now()
    val expired = db.collection("games")
        .whereEqualTo("status", "active")
        .whereLessThanOrEqualTo("turnDeadline", now)
        .limit(100)
        .get()
        .get()

    var forfeited = 0
    for (doc in expired.documents) {
        db.runTransaction { tx ->
            val snap = tx.get(doc.reference).get()
            if (!snap.exists()) return@runTransaction null
            val status = snap.getString("status")
            val turnDeadline = snap.getTimestamp("turnDeadline")
            if (status != "active" || turnDeadline == null || turnDeadline.compareTo(now) > 0) {
                return@runTransaction null
            }
            val playerUids = (snap.get("playerUids") as? List<*>)?.map { it as String } ?: emptyList()
            val activePlayerId = snap.getString("activePlayerId")
            val winner = playerUids.firstOrNull { it != activePlayerId }
            tx.update(
                doc.reference,
                mapOf(
                    "status" to "forfeited",
                    "winner" to winner,
                    "activePlayerId" to null,
                    "turnDeadline" to null
                )
            )
            forfeited += 1
            val title = gameDisplayName(snap.getString("gameType"))
            if (winner != null) {
                notices += Notice(winner, "$title: you win", "Your opponent ran out of time", doc.id)
            }
            if (activePlayerId != null) {
                notices += Notice(activePlayerId, "$title: game over", "You ran out of time", doc.id)
            }
            log.info("game forfeited on deadline ${doc.id} winner=$winner")
            null
        }.get()
    }
    notifier.sendAll(notices)
    return forfeited
}
