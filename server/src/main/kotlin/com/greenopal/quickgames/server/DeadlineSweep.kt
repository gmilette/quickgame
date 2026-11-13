package com.greenopal.quickgames.server

import com.google.cloud.Timestamp
import com.google.cloud.firestore.Firestore
import java.util.logging.Logger

private val log = Logger.getLogger("DeadlineSweep")

fun deadlineTimestamp(hours: Int): Timestamp =
    Timestamp.ofTimeSecondsAndNanos(System.currentTimeMillis() / 1000 + hours * 3600L, 0)

fun sweepExpiredGames(db: Firestore): Int {
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
            log.info("game forfeited on deadline ${doc.id} winner=$winner")
            null
        }.get()
    }
    return forfeited
}

fun startDeadlineSweepLoop(db: Firestore, intervalMinutes: Long = 60) {
    val thread = Thread({
        while (true) {
            Thread.sleep(intervalMinutes * 60_000)
            try {
                sweepExpiredGames(db)
            } catch (e: Exception) {
                log.warning("sweep failed: ${e.message}")
            }
        }
    }, "deadline-sweep")
    thread.isDaemon = true
    thread.start()
    log.info("deadline sweep loop started (every $intervalMinutes min)")
}
