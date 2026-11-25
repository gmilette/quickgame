package com.greenopal.quickgames.games.hexstead

import com.greenopal.quickgames.core.engine.EngineResult
import com.greenopal.quickgames.core.engine.EngineValidation
import com.greenopal.quickgames.core.engine.GameDefinition
import com.greenopal.quickgames.core.engine.GameEngine
import com.greenopal.quickgames.core.engine.SeededRng
import com.greenopal.quickgames.core.model.GameStatus
import com.greenopal.quickgames.core.model.GameType
import com.greenopal.quickgames.core.model.PlayerRef

/**
 * Each submission is one action (build, buy, trade, choose, end turn). The same player stays
 * active until they end their turn. Dice, steals and card draws use the submission's own seed,
 * and the card deck is stored as counts, so there is no hidden order for a client to read.
 */
object HexsteadEngine : GameEngine {

    override fun activePlayer(stateJson: String, configJson: String): String = decode(stateJson).activePlayer()

    override fun validate(
        stateJson: String,
        submissionJson: String,
        playerId: String,
        configJson: String
    ): EngineValidation {
        val action = decodeAction(submissionJson)
            ?: return EngineValidation.Invalid(listOf("malformedSubmission"))
        val errors = Rules.errors(decode(stateJson), action, playerId)
        return if (errors.isEmpty()) EngineValidation.Valid else EngineValidation.Invalid(errors)
    }

    override fun apply(
        stateJson: String,
        submissionJson: String,
        playerId: String,
        seed: Long,
        configJson: String
    ): EngineResult {
        val action = HexsteadJson.json.decodeFromString<Action>(submissionJson)
        val next = Rules.apply(decode(stateJson), action, playerId, SeededRng(seed))
        return EngineResult(
            stateJson = HexsteadJson.json.encodeToString(next),
            logJson = null,
            status = if (next.winner != null) GameStatus.COMPLETE else GameStatus.ACTIVE,
            winnerUid = next.winner
        )
    }

    private fun decode(stateJson: String): HexsteadState = HexsteadJson.json.decodeFromString(stateJson)

    private fun decodeAction(submissionJson: String): Action? =
        runCatching { HexsteadJson.json.decodeFromString<Action>(submissionJson) }.getOrNull()
}

object HexsteadDefinition : GameDefinition {
    override val gameType = GameType.HEXSTEAD
    override val displayName = "Hexstead"
    override val configVariants = listOf("standard")
    override val engineVersion = 1
    override val turnDeadlineHours = Limits.TURN_DEADLINE_HOURS

    override fun configJson(variant: String): String = """{"variant":"$variant"}"""

    override fun engine(): GameEngine = HexsteadEngine

    override fun initialStateJson(configJson: String, players: List<PlayerRef>, seed: Long): String {
        val order = players.sortedBy { it.playerIndex }.map { it.uid }
        val state = HexsteadState(
            phase = Phase.SETUP,
            order = order,
            setupIndex = 0,
            active = order[0],
            turn = 0,
            hexes = BoardGen.generate(seed),
            buildings = emptyMap(),
            roads = emptyMap(),
            players = order.associateWith {
                PlayerState(hand = Res.all.associateWith { 0 }, raiders = 0, monuments = 0)
            },
            deck = Cards.deck,
            pending = null,
            longestRoadHolder = null,
            mostRaidersHolder = null,
            lastTurnEvents = emptyList(),
            events = emptyList(),
            winner = null
        )
        return HexsteadJson.json.encodeToString(state)
    }
}
