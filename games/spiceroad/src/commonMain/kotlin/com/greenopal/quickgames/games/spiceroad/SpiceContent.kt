package com.greenopal.quickgames.games.spiceroad

import com.greenopal.quickgames.core.engine.SeededRng

object SpiceContent {
    const val SIZE = 5
    const val HOME_A = 0
    const val HOME_B = SIZE * SIZE - 1
    const val OASIS = 12
    const val ROYAL_A = 7
    const val ROYAL_B = 17

    const val HOME = "home"
    const val OASIS_KIND = "oasis"
    const val SOURCE = "source"

    const val EXPLORE = "explore"
    const val HIRE = "hire"
    const val LOADS = "loads"
    const val CAMELS = "camels"
    const val MONUMENT = "monument"
    const val WAIT = "wait"

    const val TURN_DEADLINE_HOURS = 24
    const val START_CAPACITY = 3
    const val ROYAL_TRIBUTE = 6
    const val EXCHANGE_RATE = 2

    val hireCosts = listOf(6, 12, 18)
    val loadCosts = listOf(6, 12, 18)
    val camelCosts = listOf(8, 16)

    val caravanNames = listOf("Lone Trader", "Bedouin Drovers", "Nabataean Traders", "Sultan's Convoy")
    val loadNames = listOf("Woven Saddlebags", "Goat-Hair Panniers", "Brass-Bound Chests", "Great Hawdaj")
    val camelNames = listOf("Pack Camels", "Mehari Riders", "Simoom Racers")

    val monumentNames = listOf(
        "Stone Well", "Caravanserai", "Grand Souk", "Watchtower",
        "Hammam", "Date Palm Garden", "Sultan's Palace"
    )
    private val monumentKinds = listOf(1, 2, 2, 3, 3, 4, 4)

    fun monumentCost(built: Int): Int = 6 + 2 * built + built * built
    fun monumentKinds(built: Int): Int = monumentKinds[built.coerceAtMost(monumentKinds.size - 1)]
    fun monumentName(built: Int): String = monumentNames[built.coerceAtMost(monumentNames.size - 1)]

    fun x(id: Int): Int = id % SIZE
    fun y(id: Int): Int = id / SIZE

    fun neighbours(id: Int): List<Int> = buildList {
        val x = x(id)
        val y = y(id)
        if (x > 0) add(id - 1)
        if (x < SIZE - 1) add(id + 1)
        if (y > 0) add(id - SIZE)
        if (y < SIZE - 1) add(id + SIZE)
    }

    private val essentials = listOf(Spice.PEPPER, Spice.PEPPER, Spice.CINNAMON, Spice.CINNAMON, Spice.GINGER)
    private val valuables = listOf(Spice.CARDAMOM, Spice.CLOVES, Spice.NUTMEG)
    private val precious = listOf(Spice.VANILLA, Spice.VANILLA, Spice.STAR_ANISE, Spice.STAR_ANISE)

    fun generateTiles(seed: Long, players: List<String>): List<Tile> {
        val rng = SeededRng(seed)
        val spices = arrayOfNulls<Spice>(SIZE * SIZE)
        val ids = (0 until SIZE * SIZE).toList()

        fun fillSide(sideA: Boolean) {
            val royal = if (sideA) ROYAL_A else ROYAL_B
            fun dist(id: Int) = if (sideA) x(id) + y(id) else 2 * (SIZE - 1) - x(id) - y(id)
            val near = ids.filter { dist(it) in 1..2 }
            val far = ids.filter { dist(it) == 3 && it != royal }
            rng.shuffle(essentials).forEachIndexed { i, spice -> spices[near[i]] = spice }
            rng.shuffle(valuables).forEachIndexed { i, spice -> spices[far[i]] = spice }
            spices[royal] = if (sideA) Spice.SAFFRON else Spice.FRANKINCENSE
        }
        fillSide(true)
        fillSide(false)
        val middle = ids.filter { x(it) + y(it) == SIZE - 1 && it != OASIS }
        rng.shuffle(precious).forEachIndexed { i, spice -> spices[middle[i]] = spice }

        val startFaceUp = neighbours(HOME_A) + neighbours(HOME_B) + ROYAL_A + ROYAL_B
        return ids.map { id ->
            when (id) {
                HOME_A -> Tile(id, HOME, null, true, players[0], null)
                HOME_B -> Tile(id, HOME, null, true, players[1], null)
                OASIS -> Tile(id, OASIS_KIND, null, true, null, null)
                else -> Tile(
                    id = id,
                    kind = SOURCE,
                    spice = spices[id],
                    faceUp = id in startFaceUp,
                    owner = null,
                    reservedFor = when (id) {
                        ROYAL_A -> players[0]
                        ROYAL_B -> players[1]
                        else -> null
                    }
                )
            }
        }
    }
}
