package com.greenopal.quickgames.server

import com.google.cloud.firestore.Firestore
import java.util.logging.Logger

private val log = Logger.getLogger("Resignations")

fun pollResignations(db: Firestore, notifier: Notifier) {
    val pending = db.collectionGroup("resignations").limit(200).get().get()
    for (doc in pending.documents) {
        val gameRef = doc.reference.parent.parent ?: continue
        handleResignation(db, notifier, gameRef.id, doc.id)
    }
}

fun handleResignation(db: Firestore, notifier: Notifier, gameId: String, uid: String) {
    val notices = mutableListOf<Notice>()
    db.runTransaction { tx ->
        notices.clear()
        val gameRef = db.document("games/$gameId")
        val resignationRef = db.document("games/$gameId/resignations/$uid")
        val game = tx.get(gameRef).get()
        val resignation = tx.get(resignationRef).get()
        if (!resignation.exists()) return@runTransaction null
        tx.delete(resignationRef)
        if (!game.exists() || game.getString("status") != "active") return@runTransaction null
        val playerUids = (game.get("playerUids") as? List<*>)?.map { it as String } ?: emptyList()
        if (uid !in playerUids) return@runTransaction null
        val winner = playerUids.firstOrNull { it != uid }
        tx.update(
            gameRef,
            mapOf(
                "status" to "forfeited",
                "winner" to winner,
                "activePlayerId" to null,
                "turnDeadline" to null
            )
        )
        if (winner != null) {
            val resignerName = playerNames(game.data.orEmpty())[uid] ?: "Your opponent"
            notices += Notice(
                uid = winner,
                title = "${gameDisplayName(game.getString("gameType"))}: you win",
                body = "$resignerName resigned",
                gameId = gameId
            )
        }
        log.info("game $gameId resigned by $uid")
        null
    }.get()
    notifier.sendAll(notices)
}
