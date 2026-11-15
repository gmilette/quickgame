package com.greenopal.quickgames.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.greenopal.quickgames.core.model.PlatformClient
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

const val TURN_CHANNEL_ID = "turns"
const val EXTRA_GAME_ID = "gameId"

object Push {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            TURN_CHANNEL_ID,
            "Game turns",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        channel.description = "Your turn, game started and game over"
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun registerCurrentToken(client: PlatformClient, uid: String) {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            saveToken(client, uid, token)
        }
    }

    fun saveToken(client: PlatformClient, uid: String, token: String) {
        scope.launch { runCatching { client.savePushToken(uid, token) } }
    }
}

class QuickGamesMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        val uid = Firebase.auth.currentUser?.uid ?: return
        Push.saveToken((application as QuickGamesApplication).client, uid, token)
    }

    override fun onMessageReceived(message: RemoteMessage) = Unit
}
