package com.greenopal.quickgames.server

import com.google.cloud.Timestamp
import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import com.greenopal.quickgames.core.model.PlayerRef
import java.security.SecureRandom
import java.util.logging.Logger

private val log = Logger.getLogger("OpenGames")
private val random = SecureRandom()
private const val STALE_OPEN_GAME_HOURS = 72L

fun pollOpenGames(db: Firestore, notifier: Notifier) {
    val open = db.collection("openGames").limit(200).get().get()
    for (doc in open.documents) {
        if (doc.getString("guestUid") == null) continue
        startJoinedGame(db, notifier, doc.id)
    }
}

fun startJoinedGame(db: Firestore, notifier: Notifier, openGameId: String) {
    val notices = mutableListOf<Notice>()
    db.runTransaction { tx ->
        notices.clear()
        val openRef = db.document("openGames/$openGameId")
        val open = tx.get(openRef).get()
        if (!open.exists()) return@runTransaction null
        val guestUid = open.getString("guestUid") ?: return@runTransaction null
        val hostUid = open.getString("hostUid")
        val gameTypeId = open.getString("gameType")
        val variant = open.getString("variant")
        val def = gameTypeId?.let { gameRegistry.byId(it) }
        if (hostUid == null || variant == null || def == null || variant !in def.configVariants) {
            log.warning("deleting malformed open game $openGameId")
            tx.delete(openRef)
            return@runTransaction null
        }

        val seed = random.nextLong() and 0xFFFFFFFFL
        val host = hostUid to (open.getString("hostName") ?: "Player")
        val guest = guestUid to (open.getString("guestName") ?: "Player")
        val ordered = if (random.nextBoolean()) listOf(host, guest) else listOf(guest, host)
        val playerRefs = ordered.mapIndexed { index, (uid, name) -> PlayerRef(uid, name, index) }

        val configJson = def.configJson(variant)
        val stateJson = def.initialStateJson(configJson, playerRefs, seed)
        val publicStateJson = def.publicStateJson(stateJson)
        val activePlayerId = def.engine().activePlayer(stateJson, configJson)

        val gameRef = db.collection("games").document()
        tx.delete(openRef)
        tx.create(
            gameRef,
            mapOf(
                "gameType" to gameTypeId,
                "variant" to variant,
                "players" to playerRefs.map {
                    mapOf("uid" to it.uid, "displayName" to it.displayName, "playerIndex" to it.playerIndex)
                },
                "playerUids" to playerRefs.map { it.uid },
                "activePlayerId" to activePlayerId,
                "round" to 0,
                "stateJson" to publicStateJson,
                "status" to "active",
                "winner" to null,
                "engineVersion" to def.engineVersion,
                "seed" to seed,
                "turnDeadline" to deadlineTimestamp(def.turnDeadlineHours),
                "createdAt" to FieldValue.serverTimestamp()
            )
        )
        if (publicStateJson != stateJson) {
            tx.set(secretStateRef(db, gameRef.id), mapOf("stateJson" to stateJson))
        }
        notices += Notice(
            uid = hostUid,
            title = "${def.displayName}: ${guest.second} joined",
            body = if (activePlayerId == hostUid) "It's your turn" else "${guest.second} moves first",
            gameId = gameRef.id
        )
        log.info("game created ${gameRef.id} type=$gameTypeId variant=$variant")
        null
    }.get()
    notifier.sendAll(notices)
}

fun sweepStaleOpenGames(db: Firestore): Int {
    val cutoff = Timestamp.ofTimeSecondsAndNanos(
        System.currentTimeMillis() / 1000 - STALE_OPEN_GAME_HOURS * 3600,
        0
    )
    val stale = db.collection("openGames").whereLessThanOrEqualTo("createdAt", cutoff).limit(200).get().get()
    for (doc in stale.documents) {
        doc.reference.delete().get()
        log.info("deleted stale open game ${doc.id}")
    }
    return stale.size()
}
