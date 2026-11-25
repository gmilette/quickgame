package com.greenopal.quickgames.games.squad

/** One shot type of a weapon: AP cost as a percentage of the unit's AP allowance, and base accuracy. */
data class Shot(val apPct: Int, val accuracy: Int)

data class Weapon(
    val name: String,
    val snap: Shot?,
    val aimed: Shot?,
    val auto: Shot?,
    /** Percentage of the unit's skill added to accuracy (halved for auto, doubled for aimed). */
    val skillFactor: Int,
    val damage: Int,
    val meleePct: Int,
    val meleeAccuracy: Int,
    val meleeDamage: Int
) {
    fun shot(type: String): Shot? = when (type) {
        OrderType.SNAP -> snap
        OrderType.AIM -> aimed
        OrderType.AUTO -> auto
        else -> null
    }
}

data class UnitKind(
    val name: String,
    val ap: Int,
    val health: Int,
    val skill: Int,
    val agility: Int,
    /** Front, side, rear. */
    val armour: List<Int>,
    val weapon: Weapon,
    val grenades: Int
)

/** Every number in the game. All are starting points to tune with the bot simulation. */
object SquadContent {
    const val SKIRMISH = "skirmish"
    const val RAID = "raid"
    val modes = listOf(SKIRMISH, RAID)

    const val TURN_DEADLINE_HOURS = 24
    const val ROUNDS = 12
    const val MAX_ORDERS = 120

    const val MOVE_COST = 4
    const val DIAGONAL_COST = 6
    const val RUBBLE_EXTRA = 2
    const val TURN_COST = 1

    const val AUTO_BURST = 3
    const val REACTION_MIN_PCT = 50
    /** Skirmish: the first player's AP on the opening turn, as a percentage of the allowance. */
    const val OPENING_AP_PCT = 65
    const val RANGE_FREE = 4
    const val RANGE_PENALTY = 3
    const val COVER_ACCURACY = 10
    const val COVER_ARMOUR = 3
    const val MIN_CHANCE = 5
    const val MAX_CHANCE = 95

    const val THROW_PCT = 25
    const val THROW_RANGE = 6
    const val GRENADE_DAMAGE = 20

    const val SABOTAGE_PCT = 50

    const val KILL_POINTS = 2
    const val ZONE_POINTS = 1
    const val WIN_POINTS = 10
    const val CORES_TO_WIN = 2

    const val FLOOR = '.'
    const val WALL = '#'
    const val RUBBLE = ':'
    const val CORE = 'C'

    val AUTO_RIFLE = Weapon(
        "Auto-rifle", snap = Shot(25, 45), aimed = Shot(50, 65), auto = Shot(40, 30),
        skillFactor = 30, damage = 14, meleePct = 30, meleeAccuracy = 40, meleeDamage = 8
    )
    val LONG_RIFLE = Weapon(
        "Long rifle", snap = Shot(30, 35), aimed = Shot(60, 80), auto = null,
        skillFactor = 40, damage = 20, meleePct = 30, meleeAccuracy = 40, meleeDamage = 8
    )
    val HEAVY_BEAM = Weapon(
        "Heavy beam", snap = Shot(35, 35), aimed = Shot(65, 55), auto = Shot(50, 25),
        skillFactor = 20, damage = 24, meleePct = 35, meleeAccuracy = 30, meleeDamage = 10
    )
    val BLADE = Weapon(
        "Blade", snap = null, aimed = null, auto = null,
        skillFactor = 0, damage = 0, meleePct = 20, meleeAccuracy = 70, meleeDamage = 20
    )

    val kinds: Map<String, UnitKind> = mapOf(
        "SERGEANT" to UnitKind("Sergeant", 36, 30, 60, 55, listOf(6, 4, 2), AUTO_RIFLE, 1),
        "TROOPER" to UnitKind("Trooper", 36, 26, 50, 50, listOf(6, 4, 2), AUTO_RIFLE, 1),
        "MARKSMAN" to UnitKind("Marksman", 32, 22, 70, 45, listOf(4, 3, 2), LONG_RIFLE, 0),
        "HEAVY" to UnitKind("Heavy", 28, 36, 45, 35, listOf(10, 7, 4), HEAVY_BEAM, 0),
        "STRIKER" to UnitKind("Striker", 42, 24, 40, 75, listOf(4, 3, 2), BLADE, 2)
    )

    fun kind(id: String): UnitKind = kinds.getValue(id)

    /** 9 wide, 11 tall. Rotationally symmetric; the centre zone is [zone]. */
    val skirmishMap = listOf(
        "..:...#..",
        ".##...#..",
        "....:....",
        "#..##..:.",
        "..:...#..",
        "...:.:...",
        "..#...:..",
        ".:..##..#",
        "....:....",
        "..#...##.",
        "..#...:.."
    )

    /** The defenders' base is at the top, holding three cores. */
    val raidMap = listOf(
        ".........",
        ".#C.C.C#.",
        ".#.....#.",
        ".#.....#.",
        ".##.#.##.",
        "....:....",
        "..:...:..",
        ".:..#..:.",
        ".........",
        "..:...:..",
        "........."
    )

    fun map(mode: String): List<String> = if (mode == RAID) raidMap else skirmishMap

    /** Skirmish centre zone: x 3..5, y 4..6. */
    fun inZone(x: Int, y: Int): Boolean = x in 3..5 && y in 4..6

    private data class Start(val kind: String, val name: String, val x: Int, val y: Int, val facing: Int)

    private val skirmishSquad = listOf(
        Start("MARKSMAN", "Wren", 1, 10, 0),
        Start("SERGEANT", "Ash", 3, 10, 0),
        Start("TROOPER", "Pike", 5, 10, 0),
        Start("HEAVY", "Bolt", 7, 10, 0)
    )
    private val skirmishNames = listOf("Vesper", "Rook", "Moss", "Grit")

    private val raiders = listOf(
        Start("TROOPER", "Pike", 0, 10, 0),
        Start("SERGEANT", "Ash", 2, 10, 0),
        Start("HEAVY", "Bolt", 4, 10, 0),
        Start("STRIKER", "Jinx", 6, 10, 0),
        Start("TROOPER", "Flint", 8, 10, 0)
    )
    private val defenders = listOf(
        Start("TROOPER", "Rook", 3, 2, 4),
        Start("MARKSMAN", "Vesper", 5, 2, 4),
        Start("HEAVY", "Grit", 4, 3, 4)
    )

    fun startUnits(mode: String, players: List<String>): List<SquadUnit> {
        val starts: List<Pair<String, Start>> = if (mode == RAID) {
            raiders.map { players[0] to it } + defenders.map { players[1] to it }
        } else {
            skirmishSquad.map { players[0] to it } +
                skirmishSquad.mapIndexed { i, s ->
                    players[1] to s.copy(name = skirmishNames[i], x = 8 - s.x, y = 10 - s.y, facing = 4)
                }
        }
        return starts.mapIndexed { id, (owner, s) ->
            val k = kind(s.kind)
            SquadUnit(id, owner, s.kind, s.name, s.x, s.y, s.facing, k.health, k.ap, k.grenades)
        }
    }

    fun startCores(mode: String): List<Core> {
        val rows = map(mode)
        return rows.indices.flatMap { y -> rows[y].indices.filter { rows[y][it] == CORE }.map { x -> Core(x, y) } }
    }
}
