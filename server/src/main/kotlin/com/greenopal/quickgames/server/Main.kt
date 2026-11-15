package com.greenopal.quickgames.server

import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import java.util.logging.Logger

private val log = Logger.getLogger("Main")

fun buildFirestore(): Firestore {
    val projectId = System.getenv("GOOGLE_CLOUD_PROJECT")
        ?: System.getenv("GCLOUD_PROJECT")
        ?: "demo-quickgames"
    val emulatorHost = System.getenv("FIRESTORE_EMULATOR_HOST")

    val builder = if (emulatorHost != null) {
        FirestoreOptions.newBuilder()
            .setProjectId(projectId)
            .setEmulatorHost(emulatorHost)
    } else {
        FirestoreOptions.getDefaultInstance().toBuilder().setProjectId(projectId)
    }
    log.info("connecting to firestore project=$projectId emulator=${emulatorHost ?: "none (application default credentials)"}")
    return builder.build().service
}

fun main() {
    val db = buildFirestore()
    val notifier = buildNotifier(db, db.options.projectId)

    if (System.getenv("QUICKGAMES_POLL") == "true") {
        startPollLoop("open-games", 1000) { pollOpenGames(db, notifier) }
        startPollLoop("submissions", 1000) { pollSubmissions(db, notifier) }
        startPollLoop("resignations", 1000) { pollResignations(db, notifier) }
    }

    val port = System.getenv("PORT")?.toIntOrNull()
        ?: System.getenv("SERVER_PORT")?.toIntOrNull()
        ?: 8081
    startHttpControl(db, notifier, port)

    log.info("quickgames server ready on port $port")
    Thread.currentThread().join()
}
