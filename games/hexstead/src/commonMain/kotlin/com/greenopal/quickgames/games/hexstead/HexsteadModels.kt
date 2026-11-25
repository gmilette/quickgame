package com.greenopal.quickgames.games.hexstead

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object Res {
    const val BRICK = "brick"
    const val WOOD = "wood"
    const val SHEEP = "sheep"
    const val WHEAT = "wheat"
    const val STONE = "stone"
    val all = listOf(BRICK, WOOD, SHEEP, WHEAT, STONE)
}

object Cards {
    const val RAIDER = "raider"
    const val MONUMENT = "monument"
    const val ROAD_CREW = "roadCrew"
    const val WINDFALL = "windfall"
    const val LEVY = "levy"
    val all = listOf(RAIDER, MONUMENT, ROAD_CREW, WINDFALL, LEVY)
    val deck = mapOf(RAIDER to 6, MONUMENT to 2, ROAD_CREW to 2, WINDFALL to 1, LEVY to 1)
}

object Costs {
    val road = mapOf(Res.BRICK to 1, Res.WOOD to 1)
    val settlement = mapOf(Res.BRICK to 1, Res.WOOD to 1, Res.SHEEP to 1, Res.WHEAT to 1)
    val city = mapOf(Res.STONE to 3, Res.WHEAT to 2)
    val card = mapOf(Res.STONE to 1, Res.SHEEP to 1, Res.WHEAT to 1)
}

object Limits {
    const val ROADS = 8
    const val SETTLEMENTS = 5
    const val CITIES = 4
    const val WIN_VP = 7
    const val ROAD_AWARD_MIN = 4
    const val RAIDER_AWARD_MIN = 2
    const val AWARD_VP = 2
    const val HAND_LIMIT = 7
    const val TRADE_RATE = 3
    const val TURN_DEADLINE_HOURS = 24
    val numbers = listOf(3, 4, 5, 6, 8, 9, 10)
    /** Seat that makes each of the four setup placements: first, second, second, first. */
    val setupSeats = listOf(0, 1, 1, 0)
}

object Phase {
    const val SETUP = "setup"
    const val MAIN = "main"
    const val COMPLETE = "complete"
}

object PendingKind {
    const val FREE_ROADS = "freeRoads"
    const val WINDFALL = "windfall"
    const val LEVY = "levy"
}

object ActionType {
    const val SETUP = "setup"
    const val ROAD = "road"
    const val SETTLEMENT = "settlement"
    const val CITY = "city"
    const val BUY_CARD = "buyCard"
    const val TRADE = "trade"
    const val CHOOSE = "choose"
    const val END_TURN = "endTurn"
}

object EventKind {
    const val SETUP = "setup"
    const val ROLL = "roll"
    const val DISCARD = "discard"
    const val ROAD = "road"
    const val SETTLEMENT = "settlement"
    const val CITY = "city"
    const val TRADE = "trade"
    const val CARD = "card"
    const val CHOICE = "choice"
    const val AWARD = "award"
    const val WIN = "win"
}

object Award {
    const val LONGEST_ROAD = "longestRoad"
    const val MOST_RAIDERS = "mostRaiders"
}

@Serializable
data class HexTile(val resource: String, val number: Int)

@Serializable
data class Building(val owner: String, val city: Boolean)

@Serializable
data class PlayerState(
    val hand: Map<String, Int>,
    val raiders: Int,
    val monuments: Int
) {
    fun count(resource: String): Int = hand[resource] ?: 0
    fun total(): Int = hand.values.sum()
    fun canAfford(cost: Map<String, Int>): Boolean = cost.all { (resource, n) -> count(resource) >= n }
    fun plus(resource: String, n: Int): PlayerState = copy(hand = hand + (resource to count(resource) + n))
    fun minus(cost: Map<String, Int>): PlayerState =
        copy(hand = hand.mapValues { (resource, n) -> n - (cost[resource] ?: 0) })

    /** One entry per card held, in a fixed order, for picking a random card. */
    fun cards(): List<String> = Res.all.flatMap { resource -> List(count(resource)) { resource } }
}

@Serializable
data class Pending(val kind: String, val count: Int)

/**
 * One thing that happened, kept so the screen can describe a turn. Which fields are used depends
 * on [kind]; unused ones keep their defaults.
 */
@Serializable
data class Event(
    val kind: String,
    val uid: String,
    val spot: Int = -1,
    val dice: List<Int> = emptyList(),
    val card: String? = null,
    val resource: String? = null,
    val given: String? = null,
    val count: Int = 0,
    val gains: Map<String, Map<String, Int>> = emptyMap()
)

@Serializable
data class HexsteadState(
    val phase: String,
    val order: List<String>,
    val setupIndex: Int,
    val active: String,
    val turn: Int,
    val hexes: List<HexTile>,
    val buildings: Map<Int, Building>,
    val roads: Map<Int, String>,
    val players: Map<String, PlayerState>,
    val deck: Map<String, Int>,
    val pending: Pending?,
    val longestRoadHolder: String?,
    val mostRaidersHolder: String?,
    val lastTurnEvents: List<Event>,
    val events: List<Event>,
    val winner: String?
) {
    fun activePlayer(): String =
        if (phase == Phase.SETUP) order[Limits.setupSeats[setupIndex]] else active

    fun opponentOf(uid: String): String = order.first { it != uid }
}

@Serializable
data class Action(
    val type: String,
    val vertex: Int? = null,
    val edge: Int? = null,
    val give: String? = null,
    val get: String? = null,
    val resources: List<String> = emptyList()
)

object HexsteadJson {
    val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
}
