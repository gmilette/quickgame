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

    if (System.getenv("QUICKGAMES_POLL") == "true") {
        startMatchmakingLoop(db)
        startSubmissionPollLoop(db)
        startDeadlineSweepLoop(db)
    }

    val port = System.getenv("PORT")?.toIntOrNull()
        ?: System.getenv("SERVER_PORT")?.toIntOrNull()
        ?: 8081
    startHttpControl(db, port)

    log.info("quickgames server ready on port $port")
    Thread.currentThread().join()
}
