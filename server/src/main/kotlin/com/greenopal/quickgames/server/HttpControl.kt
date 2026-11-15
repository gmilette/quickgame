package com.greenopal.quickgames.server

import com.google.cloud.firestore.Firestore
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.logging.Logger

private val log = Logger.getLogger("HttpControl")

private fun HttpExchange.respond(status: Int, body: String) {
    val bytes = body.toByteArray()
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

private fun HttpExchange.documentSegments(): List<String> {
    requestBody.use { it.readAllBytes() }
    val document = requestHeaders.getFirst("ce-document")
        ?: requestHeaders.getFirst("ce-subject")?.removePrefix("documents/")
        ?: return emptyList()
    return document.split("/")
}

private fun HttpServer.route(path: String, handler: (HttpExchange) -> String) {
    createContext(path) { exchange ->
        try {
            exchange.respond(200, handler(exchange))
        } catch (e: Exception) {
            log.severe("$path failed: $e")
            e.printStackTrace()
            exchange.respond(500, "error")
        }
    }
}

fun startHttpControl(db: Firestore, notifier: Notifier, port: Int): HttpServer {
    val server = HttpServer.create(InetSocketAddress(port), 0)

    server.route("/healthz") { "ok" }

    server.route("/sweepNow") {
        val forfeited = sweepExpiredGames(db, notifier)
        val staleOpenGames = sweepStaleOpenGames(db)
        """{"forfeited":$forfeited,"staleOpenGames":$staleOpenGames}"""
    }

    server.route("/events/open-game") { exchange ->
        val segments = exchange.documentSegments()
        if (segments.size == 2 && segments[0] == "openGames") {
            startJoinedGame(db, notifier, segments[1])
        } else {
            log.warning("ignored open game event for $segments")
        }
        "ok"
    }

    server.route("/events/submission") { exchange ->
        val segments = exchange.documentSegments()
        if (segments.size == 4 && segments[0] == "games" && segments[2] == "submissions") {
            handleSubmission(db, notifier, segments[1], segments[3])
        } else {
            log.warning("ignored submission event for $segments")
        }
        "ok"
    }

    server.route("/events/resignation") { exchange ->
        val segments = exchange.documentSegments()
        if (segments.size == 4 && segments[0] == "games" && segments[2] == "resignations") {
            handleResignation(db, notifier, segments[1], segments[3])
        } else {
            log.warning("ignored resignation event for $segments")
        }
        "ok"
    }

    server.executor = Executors.newCachedThreadPool()
    server.start()
    log.info("http control listening on :$port")
    return server
}
