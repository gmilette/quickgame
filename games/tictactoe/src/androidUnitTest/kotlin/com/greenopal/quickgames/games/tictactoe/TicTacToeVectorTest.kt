package com.greenopal.quickgames.games.tictactoe

import com.greenopal.quickgames.core.engine.EngineValidation
import com.greenopal.quickgames.core.model.PlayerRef
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class TicTacToeVectorTest {

    private val players = listOf(
        PlayerRef("uid-x", "Player X", 0),
        PlayerRef("uid-o", "Player O", 1)
    )
    private val config = "{}"
    private val pretty = Json { prettyPrint = true }

    private fun stepVector(name: String, stateJson: String, submissionJson: String, playerId: String, seed: Long): JsonObject {
        val validation = TicTacToeEngine.validate(stateJson, submissionJson, playerId, config)
        val expected = when (validation) {
            is EngineValidation.Valid -> {
                val result = TicTacToeEngine.apply(stateJson, submissionJson, playerId, seed, config)
                buildJsonObject {
                    put("valid", true)
                    put("stateJson", result.stateJson)
                    put("logJson", result.logJson?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("status", result.status.id)
                    put("winnerUid", result.winnerUid?.let { JsonPrimitive(it) } ?: JsonNull)
                }
            }
            is EngineValidation.Invalid -> buildJsonObject {
                put("valid", false)
                put("errors", JsonArray(validation.errors.map { JsonPrimitive(it) }))
            }
        }
        return buildJsonObject {
            put("name", name)
            put("stateJson", stateJson)
            put("submissionJson", submissionJson)
            put("playerId", playerId)
            put("seed", seed)
            put("configJson", config)
            put("expected", expected)
        }
    }

    private fun buildVectors(): JsonObject {
        val vectors = mutableListOf<JsonObject>()
        val initialState = TicTacToeDefinition.initialStateJson(config, players, 7L)

        var xWinState = initialState
        listOf(0, 3, 1, 4, 2).forEachIndexed { i, cell ->
            val active = TicTacToeEngine.activePlayer(xWinState, config)
            val submission = """{"cell":$cell}"""
            vectors += stepVector("x-wins-move-$i", xWinState, submission, active, i.toLong())
            xWinState = TicTacToeEngine.apply(xWinState, submission, active, i.toLong(), config).stateJson
        }

        var drawState = initialState
        listOf(0, 4, 8, 1, 7, 6, 2, 5, 3).forEachIndexed { i, cell ->
            val active = TicTacToeEngine.activePlayer(drawState, config)
            val submission = """{"cell":$cell}"""
            vectors += stepVector("draw-move-$i", drawState, submission, active, (100 + i).toLong())
            drawState = TicTacToeEngine.apply(drawState, submission, active, (100 + i).toLong(), config).stateJson
        }

        var oWinState = initialState
        listOf(1, 0, 2, 4, 5, 8).forEachIndexed { i, cell ->
            val active = TicTacToeEngine.activePlayer(oWinState, config)
            val submission = """{"cell":$cell}"""
            vectors += stepVector("o-wins-move-$i", oWinState, submission, active, (200 + i).toLong())
            oWinState = TicTacToeEngine.apply(oWinState, submission, active, (200 + i).toLong(), config).stateJson
        }

        val midState = TicTacToeEngine.apply(initialState, """{"cell":4}""", "uid-x", 0L, config).stateJson
        vectors += stepVector("reject-not-active-player", midState, """{"cell":0}""", "uid-x", 0L)
        vectors += stepVector("reject-occupied", midState, """{"cell":4}""", "uid-o", 0L)
        vectors += stepVector("reject-out-of-range", midState, """{"cell":9}""", "uid-o", 0L)
        vectors += stepVector("reject-negative", midState, """{"cell":-1}""", "uid-o", 0L)
        vectors += stepVector("reject-game-complete", xWinState, """{"cell":5}""", "uid-o", 0L)

        return buildJsonObject {
            put("game", "tictactoe")
            put("engineVersion", 1)
            put("initialStates", buildJsonArray {
                add(buildJsonObject {
                    put("name", "standard-initial")
                    put("configJson", config)
                    put("players", buildJsonArray {
                        players.forEach { p ->
                            add(buildJsonObject {
                                put("uid", p.uid)
                                put("displayName", p.displayName)
                                put("playerIndex", p.playerIndex)
                            })
                        }
                    })
                    put("seed", 7L)
                    put("expectedStateJson", initialState)
                    put("expectedActivePlayer", TicTacToeEngine.activePlayer(initialState, config))
                })
            })
            put("vectors", JsonArray(vectors))
        }
    }

    @Test
    fun vectorsMatchCommittedFile() {
        val dir = File(System.getProperty("vectors.dir") ?: fail("vectors.dir not set"))
        dir.mkdirs()
        val file = File(dir, "tictactoe.json")
        val generated = pretty.encodeToString(JsonObject.serializer(), buildVectors()) + "\n"
        val regenerate = System.getProperty("vectors.regenerate") == "true"
        if (!file.exists() || regenerate) {
            file.writeText(generated)
        }
        assertEquals(
            file.readText(),
            generated,
            "Engine output drifted from committed test vectors. " +
                "If the change is intentional, rerun with -Dvectors.regenerate=true and bump engineVersion."
        )
    }
}
