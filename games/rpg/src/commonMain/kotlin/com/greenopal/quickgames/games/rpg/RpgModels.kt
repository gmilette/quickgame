package com.greenopal.quickgames.games.rpg

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Item(
    val archetype: String,
    val tier: Int
)

@Serializable
data class Hero(
    val level: Int,
    val xp: Int,
    val hp: Int,
    val maxHp: Int,
    val attack: Int,
    val defense: Int,
    val agility: Int,
    val boons: List<String>,
    val weapon: Item?,
    val armor: Item?,
    val charm: Item?,
    val herbs: Int,
    val pendingOffers: List<List<String>>,
    val pendingLoot: Item?
) {
    fun itemIn(slot: String): Item? = when (slot) {
        RpgContent.WEAPON -> weapon
        RpgContent.ARMOR -> armor
        else -> charm
    }

    fun withItem(slot: String, item: Item?): Hero = when (slot) {
        RpgContent.WEAPON -> copy(weapon = item)
        RpgContent.ARMOR -> copy(armor = item)
        else -> copy(charm = item)
    }

    fun gear(): List<Item> = listOfNotNull(weapon, armor, charm)
}

data class EffectiveStats(
    val attack: Int,
    val defense: Int,
    val agility: Int,
    val thorns: Int,
    val critDenominator: Int,
    val critDamagePercent: Int
)

@Serializable
data class Square(
    val id: Int,
    val tier: Int,
    val monsterId: String,
    val claimedBy: String?
)

@Serializable
data class BattleTurn(
    val kind: String,
    val actor: String,
    val damage: Int,
    val critical: Boolean,
    val thorns: Int,
    val actorHp: Int,
    val targetHp: Int
)

@Serializable
data class Encounter(
    val pickIndex: Int,
    val uid: String,
    val squareId: Int,
    val monsterId: String,
    val heroStartHp: Int,
    val heroMaxHp: Int,
    val won: Boolean,
    val turns: List<BattleTurn>,
    val xpGained: Int,
    val dropItem: Item?,
    val dropHerb: Boolean,
    val autoEquipped: Boolean,
    val levelUps: Int,
    val herbUsedAfter: Boolean
)

@Serializable
data class FinalBattle(
    val first: String,
    val startHp: Map<String, Int>,
    val turns: List<BattleTurn>,
    val winner: String,
    val winReason: String
)

@Serializable
data class RpgState(
    val phase: String,
    val pickIndex: Int,
    val draftOrder: List<String>,
    val squares: List<Square>,
    val heroes: Map<String, Hero>,
    val lastEncounter: Encounter?,
    val finalBattle: FinalBattle?,
    val winner: String?,
    val winReason: String?
) {
    fun activePlayer(): String? = draftOrder.getOrNull(pickIndex)
}

@Serializable
data class HuntTurn(
    val squareId: Int,
    val boonChoices: List<String> = emptyList(),
    val lootDecision: String? = null
)

object RpgJson {
    val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
}
