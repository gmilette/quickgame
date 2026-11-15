package com.greenopal.quickgames.games.rpg

import com.greenopal.quickgames.core.engine.SeededRng

const val MONSTER_ACTOR = "monster"

object Stats {
    fun effective(hero: Hero): EffectiveStats {
        var attack = hero.attack
        var defense = hero.defense
        var agility = hero.agility
        var thorns = 0
        var critDamagePercent = 100
        for (item in hero.gear()) {
            val def = RpgContent.itemsById.getValue(item.archetype)
            val index = item.tier - 1
            attack += def.attack[index]
            defense += def.defense[index]
            agility += def.agility[index]
            thorns += def.thorns
            if (def.critDamagePercent > critDamagePercent) critDamagePercent = def.critDamagePercent
        }
        val keenEye = hero.boons.count { it == "KEEN_EYE" }.coerceAtMost(RpgContent.critDenominators.size - 1)
        return EffectiveStats(
            attack = attack.coerceAtLeast(0),
            defense = defense.coerceAtLeast(0),
            agility = agility.coerceAtLeast(0),
            thorns = thorns,
            critDenominator = RpgContent.critDenominators[keenEye],
            critDamagePercent = critDamagePercent
        )
    }

    fun herbThreshold(maxHp: Int): Int = (maxHp * RpgContent.HERB_THRESHOLD_PERCENT + 99) / 100
}

class Combatant(
    val id: String,
    var hp: Int,
    val maxHp: Int,
    val attack: Int,
    val defense: Int,
    val agility: Int,
    val critDenominator: Int,
    val critDamagePercent: Int,
    val thorns: Int,
    val berserk: Int,
    val ambush: Int,
    var herbs: Int,
    val level: Int,
    val quirk: String?,
    val quirkAmount: Int,
    val quirkEvery: Int
) {
    var actions = 0
    var actedFirst = false

    companion object {
        fun ofHero(uid: String, hero: Hero): Combatant {
            val stats = Stats.effective(hero)
            return Combatant(
                id = uid,
                hp = hero.hp,
                maxHp = hero.maxHp,
                attack = stats.attack,
                defense = stats.defense,
                agility = stats.agility,
                critDenominator = stats.critDenominator,
                critDamagePercent = stats.critDamagePercent,
                thorns = stats.thorns,
                berserk = if ("BERSERK" in hero.boons) RpgContent.boonsById.getValue("BERSERK").amount else 0,
                ambush = if ("AMBUSH" in hero.boons) RpgContent.boonsById.getValue("AMBUSH").amount else 0,
                herbs = hero.herbs,
                level = hero.level,
                quirk = null,
                quirkAmount = 0,
                quirkEvery = 0
            )
        }

        fun ofMonster(monster: MonsterDef): Combatant = Combatant(
            id = MONSTER_ACTOR,
            hp = monster.maxHp,
            maxHp = monster.maxHp,
            attack = monster.attack,
            defense = monster.defense,
            agility = monster.agility,
            critDenominator = RpgContent.critDenominators[0],
            critDamagePercent = 100,
            thorns = 0,
            berserk = 0,
            ambush = 0,
            herbs = 0,
            level = 0,
            quirk = monster.quirk,
            quirkAmount = monster.quirkAmount,
            quirkEvery = monster.quirkEvery
        )
    }
}

data class BattleOutcome(
    val first: String,
    val turns: List<BattleTurn>,
    val winner: String,
    val reason: String
)

object Combat {
    private const val MAX_ACTIONS = 400

    fun fight(a: Combatant, b: Combatant, rng: SeededRng, finalBattle: Boolean): BattleOutcome {
        val aFirst = when {
            b.quirk == "alwaysFirst" -> false
            a.agility != b.agility -> a.agility > b.agility
            else -> rng.nextInt(2) == 0
        }
        var attacker = if (aFirst) a else b
        var target = if (aFirst) b else a
        attacker.actedFirst = true
        val first = attacker.id
        val turns = mutableListOf<BattleTurn>()

        repeat(MAX_ACTIONS) {
            turns += act(attacker, target, rng)
            if (finalBattle) {
                useHerbs(target, attacker, turns)
                useHerbs(attacker, target, turns)
            }
            if (a.hp <= 0 || b.hp <= 0) {
                return finish(a, b, first, turns, rng, finalBattle)
            }
            val swap = attacker
            attacker = target
            target = swap
        }
        return finish(a, b, first, turns, rng, finalBattle)
    }

    private fun act(attacker: Combatant, target: Combatant, rng: SeededRng): BattleTurn {
        attacker.actions += 1
        val breath = attacker.quirk == "breath" &&
            attacker.quirkEvery > 0 &&
            attacker.actions % attacker.quirkEvery == 0
        var critical = false
        val damage: Int
        if (breath) {
            damage = attacker.quirkAmount
        } else {
            critical = rng.nextInt(attacker.critDenominator) == 0
            val roll = RpgContent.DAMAGE_ROLL_MIN +
                rng.nextInt(RpgContent.DAMAGE_ROLL_MAX - RpgContent.DAMAGE_ROLL_MIN + 1)
            var attack = attacker.attack
            if (attacker.berserk > 0 && attacker.hp * 2 < attacker.maxHp) attack += attacker.berserk
            if (attacker.ambush > 0 && attacker.actedFirst && attacker.actions == 1) attack += attacker.ambush
            var dealt = if (critical) {
                (attack / 2 + roll) * attacker.critDamagePercent / 100
            } else {
                attack / 2 - target.defense / 4 + roll
            }
            dealt = dealt.coerceAtLeast(RpgContent.MIN_DAMAGE)
            if (!critical && target.quirk == "halveSmallHits" && dealt < 4) {
                dealt = (dealt / 2).coerceAtLeast(1)
            }
            damage = dealt
        }
        target.hp -= damage
        attacker.hp -= target.thorns
        return BattleTurn(
            kind = if (breath) "breath" else "strike",
            actor = attacker.id,
            damage = damage,
            critical = critical,
            thorns = target.thorns,
            actorHp = attacker.hp,
            targetHp = target.hp
        )
    }

    private fun useHerbs(user: Combatant, other: Combatant, turns: MutableList<BattleTurn>) {
        val threshold = Stats.herbThreshold(user.maxHp)
        while (user.hp > 0 && user.hp < threshold && user.herbs > 0) {
            user.herbs -= 1
            user.hp = (user.hp + RpgContent.HERB_HEAL).coerceAtMost(user.maxHp)
            turns += BattleTurn("herb", user.id, 0, false, 0, user.hp, other.hp)
        }
    }

    private fun finish(
        a: Combatant,
        b: Combatant,
        first: String,
        turns: List<BattleTurn>,
        rng: SeededRng,
        finalBattle: Boolean
    ): BattleOutcome {
        val aDown = a.hp <= 0
        val bDown = b.hp <= 0
        return when {
            aDown && !bDown -> BattleOutcome(first, turns, b.id, "finalBattle")
            bDown && !aDown -> BattleOutcome(first, turns, a.id, "finalBattle")
            !finalBattle -> BattleOutcome(first, turns, b.id, "finalBattle")
            -a.hp != -b.hp -> BattleOutcome(first, turns, if (-a.hp > -b.hp) a.id else b.id, "overkill")
            a.level != b.level -> BattleOutcome(first, turns, if (a.level > b.level) a.id else b.id, "level")
            else -> BattleOutcome(first, turns, if (rng.nextInt(2) == 0) a.id else b.id, "coin")
        }
    }
}

object Progression {
    fun newHero(): Hero = Hero(
        level = 1,
        xp = 0,
        hp = RpgContent.HERO_MAX_HP,
        maxHp = RpgContent.HERO_MAX_HP,
        attack = RpgContent.HERO_ATTACK,
        defense = RpgContent.HERO_DEFENSE,
        agility = RpgContent.HERO_AGILITY,
        boons = emptyList(),
        weapon = null,
        armor = null,
        charm = null,
        herbs = 0,
        pendingOffers = emptyList(),
        pendingLoot = null
    )

    fun grantXp(hero: Hero, amount: Int, rng: SeededRng): Hero {
        var result = hero.copy(xp = hero.xp + amount)
        while (result.level < RpgContent.MAX_LEVEL && result.xp >= RpgContent.xpThresholds[result.level - 1]) {
            val level = result.level + 1
            val eligible = RpgContent.boons
                .filter { def -> def.minLevel <= level && result.boons.count { it == def.id } < def.maxStacks }
                .map { it.id }
            val plain = eligible.filter { it in RpgContent.plainBoons }
            val firstOffer = plain[rng.nextInt(plain.size)]
            val others = rng.shuffle(eligible - firstOffer).take(2)
            result = result.copy(level = level, pendingOffers = result.pendingOffers + listOf(listOf(firstOffer) + others))
        }
        return result
    }

    fun applyBoons(hero: Hero, choices: List<String>): Hero {
        if (choices.isEmpty()) return hero
        var result = hero
        for (choice in choices) {
            val def = RpgContent.boonsById.getValue(choice)
            result = when (def.stat) {
                "maxHp" -> result.copy(maxHp = result.maxHp + def.amount)
                "attack" -> result.copy(attack = result.attack + def.amount)
                "defense" -> result.copy(defense = result.defense + def.amount)
                "agility" -> result.copy(agility = result.agility + def.amount)
                else -> result
            }
            result = result.copy(boons = result.boons + choice)
        }
        return result.copy(hp = result.maxHp, pendingOffers = emptyList())
    }

    fun resolveLoot(hero: Hero, decision: String?, rng: SeededRng): Hero {
        val loot = hero.pendingLoot ?: return hero
        val slot = RpgContent.itemsById.getValue(loot.archetype).slot
        return if (decision == "EQUIP") {
            val old = hero.itemIn(slot)
            val equipped = hero.withItem(slot, loot).copy(pendingLoot = null)
            if (old == null) equipped else grantXp(equipped, RpgContent.convertXpByTier[old.tier - 1], rng)
        } else {
            grantXp(hero.copy(pendingLoot = null), RpgContent.convertXpByTier[loot.tier - 1], rng)
        }
    }

    fun autoResolve(hero: Hero, rng: SeededRng): Hero {
        var result = hero
        while (result.pendingOffers.isNotEmpty() || result.pendingLoot != null) {
            if (result.pendingOffers.isNotEmpty()) {
                result = applyBoons(result, result.pendingOffers.map { it.first() })
            }
            if (result.pendingLoot != null) {
                result = resolveLoot(result, "CONVERT", rng)
            }
        }
        return result
    }
}

object MapGen {
    fun generateSquares(seed: Long): List<Square> {
        val rng = SeededRng(seed)
        val tiers = rng.shuffle(RpgContent.pairTiers)
        val tierBySquare = IntArray(RpgContent.SQUARE_COUNT)
        for (pair in tiers.indices) {
            tierBySquare[pair] = tiers[pair]
            tierBySquare[RpgContent.SQUARE_COUNT - 1 - pair] = tiers[pair]
        }
        tierBySquare[RpgContent.SQUARE_COUNT / 2] = RpgContent.CENTER_TIER
        return tierBySquare.mapIndexed { id, tier ->
            val roster = RpgContent.rosterByTier.getValue(tier)
            Square(id, tier, roster[rng.nextInt(roster.size)].id, null)
        }
    }
}
