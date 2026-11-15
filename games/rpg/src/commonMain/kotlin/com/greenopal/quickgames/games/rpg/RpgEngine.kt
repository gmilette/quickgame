package com.greenopal.quickgames.games.rpg

import com.greenopal.quickgames.core.engine.EngineResult
import com.greenopal.quickgames.core.engine.EngineValidation
import com.greenopal.quickgames.core.engine.GameDefinition
import com.greenopal.quickgames.core.engine.GameEngine
import com.greenopal.quickgames.core.engine.SeededRng
import com.greenopal.quickgames.core.model.GameStatus
import com.greenopal.quickgames.core.model.GameType
import com.greenopal.quickgames.core.model.PlayerRef

object RpgEngine : GameEngine {

    override fun activePlayer(stateJson: String, configJson: String): String {
        val state = decode(stateJson)
        return state.activePlayer() ?: state.draftOrder.last()
    }

    override fun validate(
        stateJson: String,
        submissionJson: String,
        playerId: String,
        configJson: String
    ): EngineValidation {
        val state = decode(stateJson)
        val turn = decodeTurn(submissionJson)
            ?: return EngineValidation.Invalid(listOf("malformedSubmission"))
        val errors = buildList {
            if (state.phase != "hunt") {
                add("gameComplete")
            } else if (state.activePlayer() != playerId) {
                add("notActivePlayer")
            }
            val square = state.squares.getOrNull(turn.squareId)
            when {
                square == null -> add("squareOutOfRange")
                square.claimedBy != null -> add("squareClaimed")
            }
            val hero = state.heroes[playerId]
            if (hero != null) {
                val offers = hero.pendingOffers
                when {
                    offers.isNotEmpty() && turn.boonChoices.isEmpty() -> add("boonChoicesRequired")
                    offers.isEmpty() && turn.boonChoices.isNotEmpty() -> add("boonChoicesNotOwed")
                    offers.size != turn.boonChoices.size -> add("boonChoiceCountMismatch")
                    offers.indices.any { turn.boonChoices[it] !in offers[it] } -> add("boonNotOffered")
                }
                when {
                    hero.pendingLoot != null && turn.lootDecision == null -> add("lootDecisionRequired")
                    hero.pendingLoot == null && turn.lootDecision != null -> add("noLootPending")
                    turn.lootDecision != null && turn.lootDecision !in listOf("EQUIP", "CONVERT") ->
                        add("invalidLootDecision")
                }
            }
        }
        return if (errors.isEmpty()) EngineValidation.Valid else EngineValidation.Invalid(errors)
    }

    override fun apply(
        stateJson: String,
        submissionJson: String,
        playerId: String,
        seed: Long,
        configJson: String
    ): EngineResult {
        val state = decode(stateJson)
        val turn = RpgJson.json.decodeFromString<HuntTurn>(submissionJson)
        val rng = SeededRng(seed)

        var hero = state.heroes.getValue(playerId)
        hero = Progression.applyBoons(hero, turn.boonChoices)
        hero = Progression.resolveLoot(hero, turn.lootDecision, rng)

        val square = state.squares[turn.squareId]
        val monster = RpgContent.monstersById.getValue(square.monsterId)
        val startHp = hero.hp
        val battleRng = SeededRng(rng.nextUInt().toLong())
        val heroCombatant = Combatant.ofHero(playerId, hero)
        val outcome = Combat.fight(heroCombatant, Combatant.ofMonster(monster), battleRng, finalBattle = false)
        val won = outcome.winner == playerId

        var dropItem: Item? = null
        var dropHerb = false
        var autoEquipped = false
        var xpGained = 0
        val levelBefore = hero.level
        var herbUsedAfter = false
        if (won) {
            hero = hero.copy(hp = heroCombatant.hp)
            val slot = RpgContent.slots[rng.nextInt(RpgContent.slots.size)]
            val archetypes = RpgContent.archetypesBySlot.getValue(slot)
            val item = Item(archetypes[rng.nextInt(archetypes.size)], monster.tier)
            dropItem = item
            val herbRoll = rng.nextInt(100) < RpgContent.herbChancePercentByTier[monster.tier - 1]
            if (herbRoll && hero.herbs < RpgContent.MAX_HERBS) {
                dropHerb = true
                hero = hero.copy(herbs = hero.herbs + 1)
            }
            if (hero.itemIn(slot) == null) {
                autoEquipped = true
                hero = hero.withItem(slot, item)
            } else {
                hero = hero.copy(pendingLoot = item)
            }
            xpGained = monster.xp
            hero = Progression.grantXp(hero, monster.xp, rng)
            if (hero.hp < Stats.herbThreshold(hero.maxHp) && hero.herbs > 0) {
                herbUsedAfter = true
                hero = hero.copy(
                    herbs = hero.herbs - 1,
                    hp = (hero.hp + RpgContent.HERB_HEAL).coerceAtMost(hero.maxHp)
                )
            }
        } else {
            hero = hero.copy(hp = hero.maxHp)
        }

        val encounter = Encounter(
            pickIndex = state.pickIndex,
            uid = playerId,
            squareId = square.id,
            monsterId = monster.id,
            heroStartHp = startHp,
            heroMaxHp = heroCombatant.maxHp,
            won = won,
            turns = outcome.turns,
            xpGained = xpGained,
            dropItem = dropItem,
            dropHerb = dropHerb,
            autoEquipped = autoEquipped,
            levelUps = hero.level - levelBefore,
            herbUsedAfter = herbUsedAfter
        )

        val heroes = state.heroes + (playerId to hero)
        val squares = state.squares.map { if (it.id == square.id) it.copy(claimedBy = playerId) else it }
        val pickIndex = state.pickIndex + 1
        var next = state.copy(pickIndex = pickIndex, squares = squares, heroes = heroes, lastEncounter = encounter)

        if (pickIndex >= RpgContent.TOTAL_PICKS) {
            next = runFinalBattle(next, rng)
        }
        return EngineResult(
            stateJson = RpgJson.json.encodeToString(next),
            logJson = null,
            status = if (next.winner != null) GameStatus.COMPLETE else GameStatus.ACTIVE,
            winnerUid = next.winner
        )
    }

    private fun runFinalBattle(state: RpgState, rng: SeededRng): RpgState {
        val firstUid = state.draftOrder[0]
        val secondUid = state.draftOrder[1]
        val firstHero = Progression.autoResolve(state.heroes.getValue(firstUid), rng)
        val secondHero = Progression.autoResolve(state.heroes.getValue(secondUid), rng)
        val a = Combatant.ofHero(firstUid, firstHero)
        val b = Combatant.ofHero(secondUid, secondHero)
        val outcome = Combat.fight(a, b, SeededRng(rng.nextUInt().toLong()), finalBattle = true)
        return state.copy(
            phase = "complete",
            heroes = mapOf(
                firstUid to firstHero.copy(hp = a.hp.coerceAtLeast(0), herbs = a.herbs),
                secondUid to secondHero.copy(hp = b.hp.coerceAtLeast(0), herbs = b.herbs)
            ),
            finalBattle = FinalBattle(
                first = outcome.first,
                startHp = mapOf(firstUid to firstHero.hp, secondUid to secondHero.hp),
                turns = outcome.turns,
                winner = outcome.winner,
                winReason = outcome.reason
            ),
            winner = outcome.winner,
            winReason = outcome.reason
        )
    }

    private fun decode(stateJson: String): RpgState = RpgJson.json.decodeFromString(stateJson)

    private fun decodeTurn(submissionJson: String): HuntTurn? =
        runCatching { RpgJson.json.decodeFromString<HuntTurn>(submissionJson) }.getOrNull()
}

object RpgDefinition : GameDefinition {
    override val gameType = GameType.MINI_RPG
    override val displayName = "Mini RPG"
    override val configVariants = listOf("standard")
    override val engineVersion = 1
    override val turnDeadlineHours = RpgContent.TURN_DEADLINE_HOURS

    override fun configJson(variant: String): String = """{"variant":"$variant"}"""

    override fun engine(): GameEngine = RpgEngine

    override fun initialStateJson(configJson: String, players: List<PlayerRef>, seed: Long): String {
        val ordered = players.sortedBy { it.playerIndex }.map { it.uid }
        val draftOrder = List(RpgContent.TOTAL_PICKS) { pick ->
            ordered[RpgContent.draftPattern[pick % RpgContent.draftPattern.size]]
        }
        val state = RpgState(
            phase = "hunt",
            pickIndex = 0,
            draftOrder = draftOrder,
            squares = MapGen.generateSquares(seed),
            heroes = ordered.associateWith { Progression.newHero() },
            lastEncounter = null,
            finalBattle = null,
            winner = null,
            winReason = null
        )
        return RpgJson.json.encodeToString(state)
    }
}
