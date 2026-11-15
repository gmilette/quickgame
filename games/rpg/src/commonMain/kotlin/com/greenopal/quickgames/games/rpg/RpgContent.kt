package com.greenopal.quickgames.games.rpg

data class BoonDef(
    val id: String,
    val name: String,
    val description: String,
    val stat: String?,
    val amount: Int,
    val minLevel: Int,
    val maxStacks: Int
)

data class ItemDef(
    val id: String,
    val name: String,
    val slot: String,
    val attack: List<Int> = listOf(0, 0, 0, 0),
    val defense: List<Int> = listOf(0, 0, 0, 0),
    val agility: List<Int> = listOf(0, 0, 0, 0),
    val thorns: Int = 0,
    val critDamagePercent: Int = 100
)

data class MonsterDef(
    val id: String,
    val name: String,
    val tier: Int,
    val xp: Int,
    val maxHp: Int,
    val attack: Int,
    val defense: Int,
    val agility: Int,
    val quirk: String? = null,
    val quirkAmount: Int = 0,
    val quirkEvery: Int = 0
)

object RpgContent {
    const val GRID_SIZE = 5
    const val SQUARE_COUNT = GRID_SIZE * GRID_SIZE
    const val TOTAL_PICKS = 20
    const val MAX_LEVEL = 9
    const val TURN_DEADLINE_HOURS = 24

    const val HERO_MAX_HP = 20
    const val HERO_ATTACK = 5
    const val HERO_DEFENSE = 3
    const val HERO_AGILITY = 4

    const val DAMAGE_ROLL_MIN = -1
    const val DAMAGE_ROLL_MAX = 2
    const val MIN_DAMAGE = 1
    const val HERB_HEAL = 12
    const val HERB_THRESHOLD_PERCENT = 40
    const val MAX_HERBS = 3

    const val WEAPON = "WEAPON"
    const val ARMOR = "ARMOR"
    const val CHARM = "CHARM"

    val draftPattern = listOf(0, 1, 1, 0)
    val pairTiers = listOf(1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 4)
    const val CENTER_TIER = 4

    val xpThresholds = listOf(8, 18, 30, 44, 60, 78, 98, 120)
    val critDenominators = listOf(16, 10, 7)
    val herbChancePercentByTier = listOf(25, 35, 50, 50)
    val convertXpByTier = listOf(2, 4, 6, 8)

    val boons: List<BoonDef> = listOf(
        BoonDef("VIGOR", "Vigor", "+6 max HP", "maxHp", 6, 2, 99),
        BoonDef("MIGHT", "Might", "+2 Attack", "attack", 2, 2, 99),
        BoonDef("IRON", "Iron", "+2 Defense", "defense", 2, 2, 99),
        BoonDef("SWIFT", "Swift", "+2 Agility", "agility", 2, 2, 99),
        BoonDef("KEEN_EYE", "Keen Eye", "Crits more often", null, 0, 3, 2),
        BoonDef("AMBUSH", "Ambush", "+4 Attack on your opening strike if you act first", null, 4, 3, 1),
        BoonDef("BERSERK", "Berserk", "+3 Attack while below half HP", null, 3, 4, 1)
    )
    val boonsById: Map<String, BoonDef> = boons.associateBy { it.id }
    val plainBoons = setOf("VIGOR", "MIGHT", "IRON", "SWIFT")

    val items: List<ItemDef> = listOf(
        ItemDef("BROADSWORD", "Broadsword", WEAPON, attack = listOf(2, 4, 5, 7)),
        ItemDef("RAVAGER_AXE", "Ravager Axe", WEAPON, attack = listOf(3, 5, 7, 10), defense = listOf(0, -1, -1, -2)),
        ItemDef("PLATE", "Plate", ARMOR, defense = listOf(1, 2, 3, 5)),
        ItemDef("SPIKED_MAIL", "Spiked Mail", ARMOR, defense = listOf(1, 2, 2, 3), thorns = 1),
        ItemDef("WIND_CHARM", "Wind Charm", CHARM, agility = listOf(2, 3, 4, 6)),
        ItemDef("FANG_CHARM", "Fang Charm", CHARM, agility = listOf(0, 1, 1, 2), critDamagePercent = 150)
    )
    val itemsById: Map<String, ItemDef> = items.associateBy { it.id }
    val slots = listOf(WEAPON, ARMOR, CHARM)
    val archetypesBySlot: Map<String, List<String>> = slots.associateWith { slot ->
        items.filter { it.slot == slot }.map { it.id }
    }

    val monsters: List<MonsterDef> = listOf(
        MonsterDef("ooze", "Ooze", 1, 8, 8, 4, 1, 2),
        MonsterDef("cave-bat", "Cave Bat", 1, 8, 6, 4, 0, 6),
        MonsterDef("sewer-rat", "Sewer Rat", 1, 8, 9, 5, 1, 3),
        MonsterDef("bonewalker", "Bonewalker", 2, 14, 16, 7, 3, 4),
        MonsterDef("dire-wolf", "Dire Wolf", 2, 14, 14, 8, 2, 5, quirk = "alwaysFirst"),
        MonsterDef("imp", "Imp", 2, 14, 13, 7, 2, 7),
        MonsterDef("stone-golem", "Stone Golem", 3, 22, 30, 9, 7, 3, quirk = "halveSmallHits"),
        MonsterDef("wraith", "Wraith", 3, 22, 24, 11, 4, 8),
        MonsterDef("black-knight", "Black Knight", 3, 22, 28, 10, 6, 5),
        MonsterDef("wyrm", "Wyrm", 4, 34, 44, 13, 7, 7, quirk = "breath", quirkAmount = 8, quirkEvery = 3),
        MonsterDef("lich", "Lich", 4, 34, 36, 15, 6, 9),
        MonsterDef("ogre-lord", "Ogre Lord", 4, 34, 50, 14, 8, 4)
    )
    val monstersById: Map<String, MonsterDef> = monsters.associateBy { it.id }
    val rosterByTier: Map<Int, List<MonsterDef>> = monsters.groupBy { it.tier }

    fun quirkDescription(monster: MonsterDef): String? = when (monster.quirk) {
        "alwaysFirst" -> "Always strikes first"
        "halveSmallHits" -> "Halves weak hits"
        "breath" -> "Breathes fire for ${monster.quirkAmount} every ${monster.quirkEvery} turns"
        else -> null
    }

    fun itemLabel(item: Item): String = "${itemsById.getValue(item.archetype).name} T${item.tier}"

    fun itemEffects(item: Item): String {
        val def = itemsById.getValue(item.archetype)
        val index = item.tier - 1
        return buildList {
            if (def.attack[index] != 0) add(signed(def.attack[index]) + " Atk")
            if (def.defense[index] != 0) add(signed(def.defense[index]) + " Def")
            if (def.agility[index] != 0) add(signed(def.agility[index]) + " Agi")
            if (def.thorns != 0) add("thorns ${def.thorns}")
            if (def.critDamagePercent != 100) add("crits x${def.critDamagePercent / 100}.${def.critDamagePercent % 100 / 10}")
        }.joinToString(", ")
    }

    private fun signed(value: Int): String = if (value > 0) "+$value" else value.toString()
}
