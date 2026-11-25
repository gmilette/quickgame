package com.greenopal.quickgames.games.spiceroad

import com.greenopal.quickgames.core.engine.EngineResult
import com.greenopal.quickgames.core.engine.EngineValidation
import com.greenopal.quickgames.core.engine.GameDefinition
import com.greenopal.quickgames.core.engine.GameEngine
import com.greenopal.quickgames.core.model.GameStatus
import com.greenopal.quickgames.core.model.GameType
import com.greenopal.quickgames.core.model.PlayerRef

object SpiceEngine : GameEngine {

    override fun activePlayer(stateJson: String, configJson: String): String = decode(stateJson).activePlayer()

    override fun validate(
        stateJson: String,
        submissionJson: String,
        playerId: String,
        configJson: String
    ): EngineValidation {
        val turn = decodeTurn(submissionJson) ?: return EngineValidation.Invalid(listOf("malformedSubmission"))
        val outcome = SpiceRules.play(decode(stateJson), playerId, turn)
        return if (outcome.state != null) EngineValidation.Valid else EngineValidation.Invalid(outcome.errors)
    }

    override fun apply(
        stateJson: String,
        submissionJson: String,
        playerId: String,
        seed: Long,
        configJson: String
    ): EngineResult {
        val turn = SpiceJson.json.decodeFromString<SpiceTurn>(submissionJson)
        val outcome = SpiceRules.play(decode(stateJson), playerId, turn)
        val next = outcome.state ?: error("invalid turn: ${outcome.errors}")
        return EngineResult(
            stateJson = SpiceJson.json.encodeToString(next),
            logJson = null,
            status = if (next.complete) GameStatus.COMPLETE else GameStatus.ACTIVE,
            winnerUid = next.winner
        )
    }

    fun decode(stateJson: String): SpiceState = SpiceJson.json.decodeFromString(stateJson)

    private fun decodeTurn(submissionJson: String): SpiceTurn? =
        runCatching { SpiceJson.json.decodeFromString<SpiceTurn>(submissionJson) }.getOrNull()
}

object SpiceRoadDefinition : GameDefinition {
    override val gameType = GameType.SPICE_ROAD
    override val displayName = "Spice Road"
    override val configVariants = listOf("hours12", "hours24")
    override val engineVersion = 1
    override val turnDeadlineHours = SpiceContent.TURN_DEADLINE_HOURS

    private fun hours(variant: String): Int = if (variant == "hours24") 24 else 12

    override fun configJson(variant: String): String = """{"variant":"$variant","hours":${hours(variant)}}"""

    override fun variantLabel(variant: String): String = "${hours(variant)} hours"

    override fun engine(): GameEngine = SpiceEngine

    override fun initialStateJson(configJson: String, players: List<PlayerRef>, seed: Long): String {
        val ordered = players.sortedBy { it.playerIndex }.map { it.uid }
        val totalHours = if (configJson.contains("\"hours\":24")) 24 else 12
        val state = SpiceState(
            totalHours = totalHours,
            hour = 1,
            turnInHour = 0,
            players = ordered,
            tiles = SpiceContent.generateTiles(seed, ordered),
            traders = ordered.associateWith { Trader() }
        )
        return SpiceJson.json.encodeToString(state)
    }

    override fun publicStateJson(stateJson: String): String {
        val state = SpiceEngine.decode(stateJson)
        val hidden = state.copy(tiles = state.tiles.map { if (it.faceUp) it else it.copy(spice = null) })
        return SpiceJson.json.encodeToString(hidden)
    }
}
