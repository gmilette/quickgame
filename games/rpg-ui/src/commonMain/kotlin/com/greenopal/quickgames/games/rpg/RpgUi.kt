package com.greenopal.quickgames.games.rpg

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.greenopal.quickgames.core.engine.SeededRng
import com.greenopal.quickgames.core.model.GameDoc
import com.greenopal.quickgames.core.model.GameStatus
import com.greenopal.quickgames.core.model.GameType
import com.greenopal.quickgames.core.ui.GameUi
import com.greenopal.quickgames.core.ui.PixelPalette
import com.greenopal.quickgames.core.ui.rememberReplayState

private const val REPLAY_STEP_MILLIS = 450L
private const val WIN_CHANCE_SAMPLES = 200

object RpgUi : GameUi {
    override val gameType = GameType.MINI_RPG

    @Composable
    override fun GameScreen(game: GameDoc, myUid: String, onSubmit: (String) -> Unit) {
        val state = remember(game.stateJson) { RpgJson.json.decodeFromString<RpgState>(game.stateJson) }
        val opponentUid = state.heroes.keys.firstOrNull { it != myUid } ?: myUid
        val names = remember(game.players, myUid) {
            game.players.associate { it.uid to if (it.uid == myUid) "You" else it.displayName }
        }
        val me = state.heroes[myUid] ?: return
        val opponent = state.heroes.getValue(opponentUid)
        val myTurn = game.isMyTurn(myUid)

        var selected by remember(state.pickIndex) { mutableStateOf<Int?>(null) }
        var boonChoices by remember(state.pickIndex) { mutableStateOf(mapOf<Int, String>()) }
        var lootDecision by remember(state.pickIndex) { mutableStateOf<String?>(null) }
        var submitted by remember(state.pickIndex) { mutableStateOf(false) }

        val choicesComplete = boonChoices.size == me.pendingOffers.size &&
            (me.pendingLoot == null || lootDecision != null)

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = statusText(game, state, myUid, myTurn, names[opponentUid] ?: "Opponent"),
                style = MaterialTheme.typography.titleMedium,
                color = if (myTurn) PixelPalette.primary else PixelPalette.text
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroPanel("You", me, Modifier.weight(1f))
                HeroPanel(names[opponentUid] ?: "Opponent", opponent, Modifier.weight(1f))
            }

            val finalBattle = state.finalBattle
            if (finalBattle != null) {
                key("final") { FinalBattlePanel(finalBattle, myUid, opponentUid, names) }
            }
            val encounter = state.lastEncounter
            if (encounter != null) {
                key(encounter.pickIndex) { EncounterPanel(encounter, names) }
            }

            if (state.phase == "hunt" && game.status == GameStatus.ACTIVE) {
                if (myTurn) {
                    me.pendingOffers.forEachIndexed { index, offer ->
                        BoonOffer(offer, boonChoices[index]) { boonChoices = boonChoices + (index to it) }
                    }
                    me.pendingLoot?.let { loot ->
                        LootChoice(me, loot, lootDecision) { lootDecision = it }
                    }
                }
                Grid(state, myUid, selected, enabled = myTurn && !submitted) { selected = it }
                val square = selected?.let { state.squares[it] }
                if (square != null) {
                    val monster = RpgContent.monstersById.getValue(square.monsterId)
                    val preview = remember(me, boonChoices, lootDecision, choicesComplete) {
                        if (choicesComplete) previewHero(me, boonChoices, lootDecision) else me
                    }
                    val winChance = remember(preview, monster) { winChance(preview, monster) }
                    MonsterPanel(monster, winChance)
                    ActionButton(
                        label = when {
                            submitted -> "FIGHTING..."
                            !choicesComplete -> "MAKE YOUR CHOICES ABOVE FIRST"
                            else -> "ATTACK ${monster.name.uppercase()}"
                        },
                        enabled = myTurn && choicesComplete && !submitted,
                        selected = false,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        submitted = true
                        val turn = HuntTurn(
                            squareId = square.id,
                            boonChoices = me.pendingOffers.indices.map { boonChoices.getValue(it) },
                            lootDecision = if (me.pendingLoot != null) lootDecision else null
                        )
                        onSubmit(RpgJson.json.encodeToString(turn))
                    }
                } else if (myTurn) {
                    Text("Tap a square to see the monster.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    private fun statusText(game: GameDoc, state: RpgState, myUid: String, myTurn: Boolean, opponentName: String): String {
        val picksLeft = RpgContent.TOTAL_PICKS - state.pickIndex
        return when {
            game.status == GameStatus.FORFEITED && game.winnerUid == myUid -> "Opponent forfeited - you win!"
            game.status == GameStatus.FORFEITED -> "Forfeited - you lose"
            state.winner == myUid -> "You won the final battle!"
            state.winner != null -> "$opponentName won the final battle"
            myTurn -> "Your pick - $picksLeft picks left before the final battle"
            else -> "Waiting for $opponentName - $picksLeft picks left"
        }
    }

    private fun previewHero(hero: Hero, boonChoices: Map<Int, String>, lootDecision: String?): Hero {
        val withBoons = Progression.applyBoons(hero, hero.pendingOffers.indices.map { boonChoices.getValue(it) })
        return Progression.resolveLoot(withBoons, lootDecision, SeededRng(1L)).copy(pendingOffers = emptyList())
    }

    private fun winChance(hero: Hero, monster: MonsterDef): Int {
        var wins = 0
        for (sample in 1..WIN_CHANCE_SAMPLES) {
            val outcome = Combat.fight(
                Combatant.ofHero("hero", hero),
                Combatant.ofMonster(monster),
                SeededRng(sample * 7919L),
                finalBattle = false
            )
            if (outcome.winner == "hero") wins += 1
        }
        return wins * 100 / WIN_CHANCE_SAMPLES
    }

    @Composable
    private fun HeroPanel(name: String, hero: Hero, modifier: Modifier) {
        val stats = Stats.effective(hero)
        val nextXp = RpgContent.xpThresholds.getOrNull(hero.level - 1)
        Column(modifier.background(PixelPalette.surface).padding(8.dp)) {
            Text("$name  Lv ${hero.level}", style = MaterialTheme.typography.titleMedium, color = PixelPalette.secondary)
            Text("HP ${hero.hp}/${hero.maxHp}", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (nextXp != null) "XP ${hero.xp}/$nextXp" else "XP ${hero.xp} (max level)",
                style = MaterialTheme.typography.bodyMedium
            )
            Text("Atk ${stats.attack}  Def ${stats.defense}  Agi ${stats.agility}", style = MaterialTheme.typography.bodyMedium)
            for (slot in RpgContent.slots) {
                val item = hero.itemIn(slot)
                Text(
                    if (item != null) RpgContent.itemLabel(item) else "- no ${slot.lowercase()} -",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (item != null) PixelPalette.text else PixelPalette.text.copy(alpha = 0.4f)
                )
            }
            Text("Herbs ${hero.herbs}", style = MaterialTheme.typography.labelMedium)
            if (hero.boons.isNotEmpty()) {
                val boons = hero.boons.groupingBy { it }.eachCount().entries.joinToString(", ") { (id, count) ->
                    val boonName = RpgContent.boonsById.getValue(id).name
                    if (count > 1) "$boonName x$count" else boonName
                }
                Text(boons, style = MaterialTheme.typography.labelMedium, color = PixelPalette.accent)
            }
        }
    }

    @Composable
    private fun Grid(state: RpgState, myUid: String, selected: Int?, enabled: Boolean, onSelect: (Int) -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (row in 0 until RpgContent.GRID_SIZE) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (col in 0 until RpgContent.GRID_SIZE) {
                        val square = state.squares[row * RpgContent.GRID_SIZE + col]
                        val monster = RpgContent.monstersById.getValue(square.monsterId)
                        val claimed = square.claimedBy != null
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .background(if (claimed) PixelPalette.background else PixelPalette.surface)
                                .border(
                                    width = 2.dp,
                                    color = when {
                                        square.id == selected -> PixelPalette.text
                                        claimed -> PixelPalette.surface
                                        else -> tierColor(square.tier)
                                    }
                                )
                                .clickable(enabled = enabled && !claimed) { onSelect(square.id) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (claimed) {
                                Text(
                                    if (square.claimedBy == myUid) "YOU" else "OPP",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = PixelPalette.text.copy(alpha = 0.35f)
                                )
                            } else {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("T${square.tier}", style = MaterialTheme.typography.labelLarge, color = tierColor(square.tier))
                                    Text(
                                        monster.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun tierColor(tier: Int): Color = when (tier) {
        1 -> PixelPalette.primary
        2 -> PixelPalette.accent
        3 -> PixelPalette.secondary
        else -> PixelPalette.danger
    }

    @Composable
    private fun MonsterPanel(monster: MonsterDef, winChance: Int) {
        Column(Modifier.fillMaxWidth().background(PixelPalette.surface).padding(8.dp)) {
            Text(
                "${monster.name}  (tier ${monster.tier})",
                style = MaterialTheme.typography.titleMedium,
                color = tierColor(monster.tier)
            )
            Text(
                "HP ${monster.maxHp}  Atk ${monster.attack}  Def ${monster.defense}  Agi ${monster.agility}",
                style = MaterialTheme.typography.bodyMedium
            )
            RpgContent.quirkDescription(monster)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = PixelPalette.danger)
            }
            Text("Reward: ${monster.xp} XP and a tier ${monster.tier} item", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Your chance to win: about $winChance%",
                style = MaterialTheme.typography.bodyLarge,
                color = if (winChance >= 60) PixelPalette.primary else PixelPalette.danger
            )
        }
    }

    @Composable
    private fun BoonOffer(offer: List<String>, chosen: String?, onChoose: (String) -> Unit) {
        Column(Modifier.fillMaxWidth().background(PixelPalette.surface).padding(8.dp)) {
            Text("LEVEL UP! Choose a boon (also heals you fully)", style = MaterialTheme.typography.labelLarge, color = PixelPalette.secondary)
            Spacer(Modifier.height(6.dp))
            for (boonId in offer) {
                val boon = RpgContent.boonsById.getValue(boonId)
                ActionButton(
                    label = "${boon.name}: ${boon.description}",
                    enabled = true,
                    selected = chosen == boonId,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) { onChoose(boonId) }
            }
        }
    }

    @Composable
    private fun LootChoice(hero: Hero, loot: Item, decision: String?, onDecide: (String) -> Unit) {
        val slot = RpgContent.itemsById.getValue(loot.archetype).slot
        val current = hero.itemIn(slot)
        Column(Modifier.fillMaxWidth().background(PixelPalette.surface).padding(8.dp)) {
            Text("LOOT: keep or swap?", style = MaterialTheme.typography.labelLarge, color = PixelPalette.secondary)
            Text("Found: ${RpgContent.itemLabel(loot)} (${RpgContent.itemEffects(loot)})", style = MaterialTheme.typography.bodyMedium)
            if (current != null) {
                Text(
                    "Wearing: ${RpgContent.itemLabel(current)} (${RpgContent.itemEffects(current)})",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val oldXp = current?.let { RpgContent.convertXpByTier[it.tier - 1] } ?: 0
                ActionButton("EQUIP (+$oldXp XP for old)", true, decision == "EQUIP", Modifier.weight(1f)) { onDecide("EQUIP") }
                ActionButton(
                    "SELL (+${RpgContent.convertXpByTier[loot.tier - 1]} XP)",
                    true,
                    decision == "CONVERT",
                    Modifier.weight(1f)
                ) { onDecide("CONVERT") }
            }
        }
    }

    @Composable
    private fun EncounterPanel(encounter: Encounter, names: Map<String, String>) {
        val monster = RpgContent.monstersById.getValue(encounter.monsterId)
        val heroName = names[encounter.uid] ?: "Opponent"
        val lines = encounter.turns.map { turn ->
            val actor = if (turn.actor == MONSTER_ACTOR) monster.name else heroName
            val target = if (turn.actor == MONSTER_ACTOR) heroName else monster.name
            turnLine(turn, actor, target)
        }
        val replay = rememberReplayState(lines.size, REPLAY_STEP_MILLIS)
        Column(
            Modifier.fillMaxWidth().background(PixelPalette.surface).clickable { replay.skipToEnd() }.padding(8.dp)
        ) {
            Text(
                "$heroName vs ${monster.name} (tier ${monster.tier})",
                style = MaterialTheme.typography.labelLarge,
                color = PixelPalette.secondary
            )
            BattleLines(lines, replay.index)
            if (replay.finished) {
                Text(encounterSummary(encounter, heroName), style = MaterialTheme.typography.bodyLarge, color = resultColor(encounter.won))
            } else {
                Text("tap to skip", style = MaterialTheme.typography.labelMedium, color = PixelPalette.text.copy(alpha = 0.5f))
            }
        }
    }

    @Composable
    private fun FinalBattlePanel(battle: FinalBattle, myUid: String, opponentUid: String, names: Map<String, String>) {
        val lines = battle.turns.map { turn ->
            val other = if (turn.actor == myUid) opponentUid else myUid
            turnLine(turn, names[turn.actor] ?: "Opponent", names[other] ?: "Opponent")
        }
        val replay = rememberReplayState(lines.size, REPLAY_STEP_MILLIS)
        Column(
            Modifier.fillMaxWidth().background(PixelPalette.surface).clickable { replay.skipToEnd() }.padding(8.dp)
        ) {
            Text("THE FINAL BATTLE", style = MaterialTheme.typography.titleMedium, color = PixelPalette.danger)
            Text(
                battle.startHp.entries.joinToString("   ") { (uid, hp) -> "${names[uid] ?: "Opponent"} HP $hp" },
                style = MaterialTheme.typography.bodyMedium
            )
            BattleLines(lines, replay.index)
            if (replay.finished) {
                val won = battle.winner == myUid
                Text(
                    if (won) "You are victorious!" else "${names[battle.winner] ?: "Opponent"} is victorious.",
                    style = MaterialTheme.typography.titleMedium,
                    color = resultColor(won)
                )
            } else {
                Text("tap to skip", style = MaterialTheme.typography.labelMedium, color = PixelPalette.text.copy(alpha = 0.5f))
            }
        }
    }

    @Composable
    private fun BattleLines(lines: List<String>, shown: Int) {
        for (line in lines.take(shown).takeLast(6)) {
            Text(line, style = MaterialTheme.typography.bodyMedium)
        }
    }

    private fun resultColor(won: Boolean): Color = if (won) PixelPalette.primary else PixelPalette.danger

    private fun turnLine(turn: BattleTurn, actor: String, target: String): String {
        val hit = if (actor == "You") "hit" else "hits"
        return when (turn.kind) {
            "herb" -> "$actor ${if (actor == "You") "eat" else "eats"} an herb. HP ${turn.actorHp}."
            "breath" -> "$actor breathes fire for ${turn.damage}!"
            else -> buildString {
                if (turn.critical) append("Excellent move! ")
                append("$actor $hit $target for ${turn.damage}.")
                if (turn.thorns > 0) append(" Thorns sting for ${turn.thorns}.")
            }
        }
    }

    private fun encounterSummary(encounter: Encounter, heroName: String): String {
        if (!encounter.won) {
            return "$heroName ${if (heroName == "You") "were" else "was"} defeated. HP restored, nothing gained."
        }
        return buildString {
            append("Victory! +${encounter.xpGained} XP.")
            encounter.dropItem?.let { item ->
                append(" Found ${RpgContent.itemLabel(item)}")
                append(if (encounter.autoEquipped) " (equipped)." else " (decide next turn).")
            }
            if (encounter.dropHerb) append(" Found an herb.")
            if (encounter.herbUsedAfter) append(" Ate an herb.")
            if (encounter.levelUps > 0) append(" LEVEL UP!")
        }
    }

    @Composable
    private fun ActionButton(label: String, enabled: Boolean, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
        Box(
            modifier = modifier
                .background(if (selected) PixelPalette.primary.copy(alpha = 0.25f) else PixelPalette.background)
                .border(
                    width = 2.dp,
                    color = when {
                        selected -> PixelPalette.secondary
                        enabled -> PixelPalette.primary
                        else -> PixelPalette.surface
                    }
                )
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                color = if (enabled) PixelPalette.text else PixelPalette.text.copy(alpha = 0.4f)
            )
        }
    }
}
