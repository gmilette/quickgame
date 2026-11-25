package com.greenopal.quickgames.games.squad

import com.greenopal.quickgames.core.engine.EngineResult
import com.greenopal.quickgames.core.engine.EngineValidation
import com.greenopal.quickgames.core.engine.GameDefinition
import com.greenopal.quickgames.core.engine.GameEngine
import com.greenopal.quickgames.core.engine.SeededRng
import com.greenopal.quickgames.core.model.GameStatus
import com.greenopal.quickgames.core.model.GameType
import com.greenopal.quickgames.core.model.PlayerRef

/**
 * Each submission is a player's whole turn. Shots, grenades and melee use the submission's own
 * seed. Each player only receives their own view of the state (fog of war), see [SquadRules.view].
 */
object SquadEngine : GameEngine {

    override fun activePlayer(stateJson: String, configJson: String): String = decode(stateJson).activePlayer()

    override fun validate(
        stateJson: String,
        submissionJson: String,
        playerId: String,
        configJson: String
    ): EngineValidation {
        val turn = decodeTurn(submissionJson) ?: return EngineValidation.Invalid(listOf("malformedSubmission"))
        val errors = SquadRules.errors(decode(stateJson), playerId, turn)
        return if (errors.isEmpty()) EngineValidation.Valid else EngineValidation.Invalid(errors)
    }

    override fun apply(
        stateJson: String,
        submissionJson: String,
        playerId: String,
        seed: Long,
        configJson: String
    ): EngineResult {
        val turn = SquadJson.json.decodeFromString<SquadTurn>(submissionJson)
        val next = SquadRules.play(decode(stateJson), playerId, turn, SeededRng(seed))
        return EngineResult(
            stateJson = SquadJson.json.encodeToString(next),
            logJson = null,
            status = if (next.complete) GameStatus.COMPLETE else GameStatus.ACTIVE,
            winnerUid = next.winner
        )
    }

    fun decode(stateJson: String): SquadState = SquadJson.json.decodeFromString(stateJson)

    private fun decodeTurn(submissionJson: String): SquadTurn? =
        runCatching { SquadJson.json.decodeFromString<SquadTurn>(submissionJson) }.getOrNull()
}

object SquadDefinition : GameDefinition {
    override val gameType = GameType.SQUAD
    override val displayName = "Squad"
    override val configVariants = SquadContent.modes
    override val engineVersion = 1
    override val turnDeadlineHours = SquadContent.TURN_DEADLINE_HOURS

    override fun configJson(variant: String): String = """{"variant":"$variant"}"""

    override fun variantLabel(variant: String): String =
        if (variant == SquadContent.RAID) "Raid (attack vs defend)" else "Skirmish"

    override fun engine(): GameEngine = SquadEngine

    /**
     * In a skirmish the second player's units start with no AP: they haven't had a turn, so they
     * have nothing saved for opportunity fire during the opening turn. Raid defenders start on guard.
     */
    fun initialState(mode: String, players: List<String>): SquadState {
        val units = SquadContent.startUnits(mode, players).map {
            when {
                mode != SquadContent.SKIRMISH -> it
                it.owner == players[1] -> it.copy(ap = 0)
                else -> it.copy(ap = it.ap * SquadContent.OPENING_AP_PCT / 100)
            }
        }
        return SquadState(
            mode = mode,
            players = players,
            turn = 0,
            maxTurns = SquadContent.ROUNDS * 2,
            units = units,
            cores = SquadContent.startCores(mode),
            scores = players.associateWith { 0 },
            remaining = players.associateWith { p -> units.count { it.owner == p } },
            lastSeen = players.associateWith { emptyList() }
        )
    }

    override fun initialStateJson(configJson: String, players: List<PlayerRef>, seed: Long): String {
        val mode = if (configJson.contains("\"${SquadContent.RAID}\"")) SquadContent.RAID else SquadContent.SKIRMISH
        val ordered = players.sortedBy { it.playerIndex }.map { it.uid }
        return SquadJson.json.encodeToString(initialState(mode, ordered))
    }

    override fun publicStateJson(stateJson: String): String =
        SquadJson.json.encodeToString(SquadRules.publicView(SquadEngine.decode(stateJson)))

    override fun playerStateJson(stateJson: String, uid: String): String =
        SquadJson.json.encodeToString(SquadRules.view(SquadEngine.decode(stateJson), uid))
}
