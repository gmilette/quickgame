package com.greenopal.quickgames.games.squad

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One soldier. [facing] is a direction 0..7 (0 = north/up, clockwise). Dead units keep health 0. */
@Serializable
data class SquadUnit(
    val id: Int,
    val owner: String,
    val kind: String,
    val name: String,
    val x: Int,
    val y: Int,
    val facing: Int,
    val health: Int,
    val ap: Int,
    val grenades: Int
) {
    val alive: Boolean get() = health > 0
    val stats: UnitKind get() = SquadContent.kind(kind)
}

/** A raid objective. Its square is a `C` on the map; once destroyed it is rubble. */
@Serializable
data class Core(val x: Int, val y: Int, val destroyed: Boolean = false)

/** Where a player last saw an enemy unit that is now out of sight. */
@Serializable
data class Sighting(val unit: Int, val x: Int, val y: Int, val turn: Int)

/**
 * One thing that happened during a turn. Which fields are used depends on [kind] (see [EventKind]).
 * [x],[y] is where the acting unit was; [tx],[ty] the target square. [seenBy] lists the players
 * allowed to see the event; it is emptied in the views sent to players.
 */
@Serializable
data class Event(
    val turn: Int,
    val kind: String,
    val unit: Int = -1,
    val x: Int = 0,
    val y: Int = 0,
    val facing: Int = 0,
    val target: Int = -1,
    val tx: Int = 0,
    val ty: Int = 0,
    val chance: Int = 0,
    val hit: Boolean = false,
    val damage: Int = 0,
    val reaction: Boolean = false,
    val reason: String? = null,
    val seenBy: List<String> = emptyList()
)

object EventKind {
    const val MOVE = "move"
    const val TURN = "turn"
    const val SHOT = "shot"
    const val MELEE = "melee"
    const val THROW = "throw"
    const val BLAST = "blast"
    const val HURT = "hurt"
    const val KILL = "kill"
    const val SABOTAGE = "sabotage"
    const val ZONE = "zone"
    const val HALT = "halt"
    const val SKIP = "skip"
}

/**
 * The whole game. The same shape is used for the full state (server only), for each player's
 * view ([viewer] set; only their own units and the enemies they can see) and for the public state
 * on the game doc (no units, no events).
 */
@Serializable
data class SquadState(
    val mode: String,
    val players: List<String>,
    val turn: Int,
    val maxTurns: Int,
    val units: List<SquadUnit>,
    val cores: List<Core>,
    val scores: Map<String, Int>,
    val remaining: Map<String, Int>,
    val lastSeen: Map<String, List<Sighting>> = emptyMap(),
    val events: List<Event> = emptyList(),
    val complete: Boolean = false,
    val winner: String? = null,
    val viewer: String? = null
) {
    fun activePlayer(): String = players[turn % 2]
    fun opponentOf(uid: String): String = players.first { it != uid }

    /** Rounds are counted per player turn pair, from 1. */
    val round: Int get() = turn / 2 + 1
    val maxRounds: Int get() = maxTurns / 2

    fun unit(id: Int): SquadUnit? = units.firstOrNull { it.id == id }
    fun unitAt(x: Int, y: Int): SquadUnit? = units.firstOrNull { it.alive && it.x == x && it.y == y }
    fun squad(uid: String): List<SquadUnit> = units.filter { it.owner == uid }

    /** In the raid, player index 0 attacks and index 1 defends. */
    fun attacker(): String = players[0]
}

/**
 * One order. Which fields are used depends on [type]: MOVE/FACE/MELEE/SABOTAGE use [dir];
 * SNAP/AIM/AUTO use [target] (an enemy unit id); THROW uses [x],[y].
 */
@Serializable
data class Order(
    val unit: Int,
    val type: String,
    val dir: Int = -1,
    val target: Int = -1,
    val x: Int = -1,
    val y: Int = -1
)

object OrderType {
    const val MOVE = "MOVE"
    const val FACE = "FACE"
    const val SNAP = "SNAP"
    const val AIM = "AIM"
    const val AUTO = "AUTO"
    const val THROW = "THROW"
    const val MELEE = "MELEE"
    const val SABOTAGE = "SABOTAGE"

    val shots = setOf(SNAP, AIM, AUTO)
    val withDir = setOf(MOVE, FACE, MELEE, SABOTAGE)
    val all = shots + withDir + THROW
}

/** A whole turn: every order for every unit, run in list order. */
@Serializable
data class SquadTurn(val orders: List<Order>)

object SquadJson {
    val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
}
