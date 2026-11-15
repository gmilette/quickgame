package com.greenopal.quickgames.server

import java.util.logging.Logger

private val log = Logger.getLogger("Polling")

fun startPollLoop(name: String, intervalMillis: Long, tick: () -> Unit) {
    val thread = Thread({
        while (true) {
            try {
                tick()
            } catch (e: Exception) {
                log.severe("$name poll failed: $e")
                e.printStackTrace()
            }
            Thread.sleep(intervalMillis)
        }
    }, "$name-poll")
    thread.isDaemon = true
    thread.start()
    log.info("$name poll loop started")
}
