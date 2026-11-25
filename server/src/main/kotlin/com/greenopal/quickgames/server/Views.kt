package com.greenopal.quickgames.server

import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Transaction
import com.greenopal.quickgames.core.engine.GameDefinition

fun viewRef(db: Firestore, gameId: String, uid: String) = db.document("games/$gameId/views/$uid")

/**
 * Writes each player's own view of the state, for games that hide information between players.
 * [round] is the game doc's round after this write, so a client can tell a view from a newer
 * round from a stale one. Returns false when the game has no per-player views.
 */
fun writeViews(
    db: Firestore,
    tx: Transaction,
    def: GameDefinition,
    gameId: String,
    playerUids: List<String>,
    stateJson: String,
    round: Int
): Boolean {
    val views = playerUids.map { uid -> uid to def.playerStateJson(stateJson, uid) }
    if (views.any { it.second == null }) return false
    for ((uid, view) in views) {
        tx.set(viewRef(db, gameId, uid), mapOf("stateJson" to view, "round" to round))
    }
    return true
}
