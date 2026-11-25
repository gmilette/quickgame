package com.greenopal.quickgames.games.spiceroad

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class Spice(val price: Int) {
    PEPPER(1), CINNAMON(1), GINGER(1),
    CARDAMOM(4), CLOVES(4), NUTMEG(4),
    VANILLA(8), STAR_ANISE(8),
    SAFFRON(10), FRANKINCENSE(10);

    val royal: Boolean get() = this == SAFFRON || this == FRANKINCENSE
}

@Serializable
data class Tile(
    val id: Int,
    val kind: String,
    val spice: Spice?,
    val faceUp: Boolean,
    val owner: String?,
    val reservedFor: String?
)

@Serializable
data class Caravan(
    val target: Int? = null,
    val hoursLeft: Int = 0,
    val next: Int? = null
)

@Serializable
data class Trader(
    val stock: Map<Spice, Int> = emptyMap(),
    val caravans: List<Caravan> = listOf(Caravan()),
    val loadLevel: Int = 0,
    val camelLevel: Int = 0,
    val monuments: Int = 0
) {
    val capacity: Int get() = SpiceContent.START_CAPACITY + loadLevel
    fun stockValue(): Int = stock.entries.sumOf { it.key.price * it.value }
}

@Serializable
data class Delivery(
    val uid: String,
    val tile: Int,
    val spice: Spice,
    val units: Int
)

@Serializable
data class SpiceState(
    val totalHours: Int,
    val hour: Int,
    val turnInHour: Int,
    val players: List<String>,
    val tiles: List<Tile>,
    val traders: Map<String, Trader>,
    val lastDeliveries: List<Delivery> = emptyList(),
    val complete: Boolean = false,
    val winner: String? = null
) {
    fun activePlayer(): String {
        val firstIndex = if (hour % 2 == 1) 0 else 1
        return players[if (turnInHour == 0) firstIndex else 1 - firstIndex]
    }

    fun opponentOf(uid: String): String = players.first { it != uid }
    fun homeOf(uid: String): Int = if (players[0] == uid) SpiceContent.HOME_A else SpiceContent.HOME_B
}

@Serializable
data class Exchange(
    val give: Map<Spice, Int>,
    val take: Map<Spice, Int>
)

@Serializable
data class SpiceTurn(
    val action: String,
    val tile: Int? = null,
    val payment: Map<Spice, Int> = emptyMap(),
    val exchanges: List<Exchange> = emptyList(),
    val assignments: List<Int?> = emptyList()
)

object SpiceJson {
    val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
}
