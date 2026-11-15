package com.greenopal.quickgames.server

import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.logging.Logger
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private val log = Logger.getLogger("Notifications")

const val TURN_CHANNEL_ID = "turns"

data class Notice(
    val uid: String,
    val title: String,
    val body: String,
    val gameId: String
)

fun interface Notifier {
    fun send(notice: Notice)
}

fun pushTokenRef(db: Firestore, uid: String) = db.document("users/$uid/private/push")

fun buildNotifier(db: Firestore, projectId: String): Notifier = when (System.getenv("QUICKGAMES_NOTIFY")) {
    "log" -> RecordingNotifier(db)
    "off" -> Notifier { }
    else -> if (System.getenv("FIRESTORE_EMULATOR_HOST") != null) Notifier { } else FcmNotifier(db, projectId)
}

fun Notifier.sendAll(notices: List<Notice>) {
    for (notice in notices) {
        try {
            send(notice)
        } catch (e: Exception) {
            log.warning("notification to ${notice.uid} failed: $e")
        }
    }
}

class RecordingNotifier(private val db: Firestore) : Notifier {
    override fun send(notice: Notice) {
        db.collection("debugNotifications").add(
            mapOf(
                "uid" to notice.uid,
                "title" to notice.title,
                "body" to notice.body,
                "gameId" to notice.gameId,
                "createdAt" to FieldValue.serverTimestamp()
            )
        ).get()
    }
}

class FcmNotifier(private val db: Firestore, private val projectId: String) : Notifier {
    private val http = HttpClient.newHttpClient()
    private val credentials = GoogleCredentials.getApplicationDefault()
        .createScoped("https://www.googleapis.com/auth/firebase.messaging")

    override fun send(notice: Notice) {
        val tokenRef = pushTokenRef(db, notice.uid)
        val token = tokenRef.get().get().getString("token") ?: return

        credentials.refreshIfExpired()
        val request = HttpRequest.newBuilder(URI("https://fcm.googleapis.com/v1/projects/$projectId/messages:send"))
            .header("Authorization", "Bearer ${credentials.accessToken.tokenValue}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(messageJson(token, notice)))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        when {
            response.statusCode() == 200 -> log.info("notified ${notice.uid}: ${notice.title}")
            response.statusCode() == 404 || response.body().contains("UNREGISTERED") -> {
                tokenRef.delete().get()
                log.info("removed dead push token for ${notice.uid}")
            }
            else -> log.warning("fcm send failed ${response.statusCode()}: ${response.body()}")
        }
    }

    private fun messageJson(token: String, notice: Notice): String = buildJsonObject {
        putJsonObject("message") {
            put("token", token)
            putJsonObject("notification") {
                put("title", notice.title)
                put("body", notice.body)
            }
            putJsonObject("data") {
                put("gameId", notice.gameId)
            }
            putJsonObject("android") {
                putJsonObject("notification") {
                    put("channel_id", TURN_CHANNEL_ID)
                    put("tag", notice.gameId)
                }
            }
        }
    }.toString()
}

fun playerNames(gameData: Map<String, Any?>): Map<String, String> =
    (gameData["players"] as? List<*>).orEmpty().mapNotNull { entry ->
        val player = entry as? Map<*, *> ?: return@mapNotNull null
        val uid = player["uid"] as? String ?: return@mapNotNull null
        uid to (player["displayName"] as? String ?: "Player")
    }.toMap()

fun gameDisplayName(gameTypeId: String?): String =
    gameTypeId?.let { gameRegistry.byId(it)?.displayName } ?: "Game"
