package com.greenopal.quickgames.server

import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import com.greenopal.quickgames.core.model.PlayerRef
import java.security.SecureRandom
import java.util.logging.Logger

private val log = Logger.getLogger("Matchmaking")
private val random = SecureRandom()

fun startMatchmakingLoop(db: Firestore, intervalMillis: Long = 1000) {
    val queueIds = gameRegistry.all.values.flatMap { def ->
        def.configVariants.map { variant -> Triple(def.gameType.id, variant, def) }
    }
    val thread = Thread({
        while (true) {
            for ((gameTypeId, variant, def) in queueIds) {
                try {
                    pairQueue(db, "${gameTypeId}_$variant", gameTypeId, variant, def)
                } catch (e: Exception) {
                    log.severe("matchmaking tick failed for ${gameTypeId}_$variant: $e")
                    e.printStackTrace()
                }
            }
            Thread.sleep(intervalMillis)
        }
    }, "matchmaking-poll")
    thread.isDaemon = true
    thread.start()
    log.info("matchmaking poll loop started for ${queueIds.size} queue(s)")
}

fun matchQueue(db: Firestore, queueId: String) {
    val separator = queueId.indexOf("_")
    if (separator < 1) {
        log.warning("malformed queue id $queueId")
        return
    }
    val gameTypeId = queueId.substring(0, separator)
    val variant = queueId.substring(separator + 1)
    val def = gameRegistry.byId(gameTypeId)
    if (def == null || variant !in def.configVariants) {
        log.warning("unknown game type or variant for queue $queueId")
        return
    }
    pairQueue(db, queueId, gameTypeId, variant, def)
}

private fun pairQueue(
    db: Firestore,
    queueId: String,
    gameTypeId: String,
    variant: String,
    def: com.greenopal.quickgames.core.engine.GameDefinition
) {
    db.runTransaction { tx ->
        val entries = tx.get(
            db.collection("queues/$queueId/entries").orderBy("createdAt").limit(2)
        ).get()
        if (entries.size() < 2) return@runTransaction null

        val docs = entries.documents
        val first = docs[0]
        val second = docs[1]

        val seed = random.nextLong() and 0xFFFFFFFFL
        val ordered = if (random.nextBoolean()) listOf(first, second) else listOf(second, first)
        val playerRefs = ordered.mapIndexed { index, doc ->
            PlayerRef(doc.id, doc.getString("displayName") ?: "Player", index)
        }

        val configJson = def.configJson(variant)
        val stateJson = def.initialStateJson(configJson, playerRefs, seed)
        val activePlayerId = def.engine().activePlayer(stateJson, configJson)

        val gameRef = db.collection("games").document()
        tx.delete(first.reference)
        tx.delete(second.reference)
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
                "stateJson" to stateJson,
                "status" to "active",
                "winner" to null,
                "engineVersion" to def.engineVersion,
                "seed" to seed,
                "turnDeadline" to deadlineTimestamp(def.turnDeadlineHours),
                "createdAt" to FieldValue.serverTimestamp()
            )
        )
        log.info("game created ${gameRef.id} type=$gameTypeId variant=$variant")
        null
    }.get()
}
