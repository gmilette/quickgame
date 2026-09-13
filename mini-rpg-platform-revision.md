# Mini RPG — Platform Revision (Phase B build spec)

This document amends `mini-rpg-implementation-plan.md` and `mini-rpg-alternating-revision.md` so the game can be built against the platform as it exists in this repo (`IMPLEMENTATION-PLAN.md` §0) after the P0 hardening tasks and the Quick WW2 MVP. Precedence: this document > alternating revision > original plan. The original plan stays authoritative for design intent, numbers not restated here, the build archetypes, the pixel presentation, and the IP note (all names and art must be original; the monster names below are the shipped ones).

Prerequisites: P0.2 per-submission seeds, P0.3 frozen `configJson`, P0.4 `logs` flow into `GameScreen`, P0.6 RNG vectors, P0.7 shared assets. The Quick WW2 revision established the patterns (staging validation, vector naming, harness gating, e2e seed pinning); this document reuses them and only spells out what differs.

---

## 1. Module layout

```
games/rpg/
  build.gradle.kts                       tic-tac-toe template + generateSharedAssets(shared-assets/rpg)
  src/commonMain/kotlin/com/greenopal/quickgames/games/rpg/
    Model.kt        enums, Content (decoded config), SquareState, HeroState, Item, HuntState, HuntTurn, BattleTurn, EncounterLog, TurnLog
    Content.kt      decode SharedAssets.CONTENT_JSON, Content.validate()
    MapGen.kt       generateSquares(seed, content): List<SquareState>
    Stats.kt        effectiveStats(hero, content), critDenominator, thorns, critBonus
    Combat.kt       resolveBattle(a: Combatant, b: Combatant, rng, content): BattleOutcome (shared by hunts and the final battle)
    Progression.kt  grantXp, levelUp offers, loot roll, conversions, herb checks
    Engine.kt       RpgEngine: initialState / validateTurn / applyTurn
    Definition.kt   RpgDefinition : GameDefinition, GameEngine adapter (JSON boundary)
    ui/             RpgUi : GameUi, screens (§7), Strings.kt (dialect table)
  src/androidUnitTest/kotlin/.../
    MapGenTest CombatTest ProgressionTest EngineTest DeterminismTest RpgVectorTest
    balance/Bots.kt balance/BalanceHarness.kt   gated by -Pbalance=true
shared-assets/rpg/content.json
functions/src/games/rpg/{model,content,mapgen,stats,combat,progression,engine,definition}.ts
test-vectors/rpg.json
```

Registration: `RpgDefinition` / `RpgUi` in both app composition roots; `rpgDefinition` in `registry.ts` with `turnDeadlineHours: 24`. `GameType.MINI_RPG` (id `miniRpg`) exists; queue id `miniRpg_standard`.

---

## 2. Shared asset: `shared-assets/rpg/content.json`

```json
{
  "version": 1,
  "grid": { "size": 5, "picksPerPlayer": 10, "draftPattern": ["A", "B", "B", "A"],
            "pairTiers": [1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 4], "centerTier": 4 },
  "hero": { "maxHp": 20, "attack": 5, "defense": 3, "agility": 4, "maxLevel": 9,
            "xpThresholds": [8, 18, 30, 44, 60, 78, 98, 120] },
  "combat": { "damageRollMin": -1, "damageRollMax": 2, "minDamage": 1,
              "critDenominators": [16, 10, 7], "herbHeal": 12, "herbThresholdPercent": 40, "maxHerbs": 3 },
  "boons": {
    "VIGOR":    { "kind": "stat", "stat": "maxHp",   "amount": 6, "minLevel": 2, "maxStacks": 99 },
    "MIGHT":    { "kind": "stat", "stat": "attack",  "amount": 2, "minLevel": 2, "maxStacks": 99 },
    "IRON":     { "kind": "stat", "stat": "defense", "amount": 2, "minLevel": 2, "maxStacks": 99 },
    "SWIFT":    { "kind": "stat", "stat": "agility", "amount": 2, "minLevel": 2, "maxStacks": 99 },
    "KEEN_EYE": { "kind": "crit",    "minLevel": 3, "maxStacks": 2 },
    "AMBUSH":   { "kind": "ambush",  "amount": 4, "minLevel": 3, "maxStacks": 1 },
    "BERSERK":  { "kind": "berserk", "amount": 3, "minLevel": 4, "maxStacks": 1 }
  },
  "plainBoons": ["VIGOR", "MIGHT", "IRON", "SWIFT"],
  "items": {
    "BROADSWORD":  { "slot": "WEAPON", "attack":  [2, 4, 5, 7] },
    "RAVAGER_AXE": { "slot": "WEAPON", "attack":  [3, 5, 7, 10], "defense": [0, -1, -1, -2] },
    "PLATE":       { "slot": "ARMOR",  "defense": [1, 2, 3, 5] },
    "SPIKED_MAIL": { "slot": "ARMOR",  "defense": [1, 2, 2, 3], "thorns": [1, 1, 1, 1] },
    "WIND_CHARM":  { "slot": "CHARM",  "agility": [2, 3, 4, 6] },
    "FANG_CHARM":  { "slot": "CHARM",  "agility": [0, 1, 1, 2], "critDamagePercent": 150 }
  },
  "slots": ["WEAPON", "ARMOR", "CHARM"],
  "archetypesBySlot": { "WEAPON": ["BROADSWORD", "RAVAGER_AXE"], "ARMOR": ["PLATE", "SPIKED_MAIL"], "CHARM": ["WIND_CHARM", "FANG_CHARM"] },
  "loot": { "herbChancePercentByTier": [25, 35, 50, 50], "convertXpByTier": [2, 4, 6, 8] },
  "monsters": {
    "ooze":        { "name": "Ooze",         "tier": 1, "xp": 8,  "maxHp": 8,  "attack": 4,  "defense": 1, "agility": 2 },
    "cave-bat":    { "name": "Cave Bat",     "tier": 1, "xp": 8,  "maxHp": 6,  "attack": 4,  "defense": 0, "agility": 6 },
    "sewer-rat":   { "name": "Sewer Rat",    "tier": 1, "xp": 8,  "maxHp": 9,  "attack": 5,  "defense": 1, "agility": 3 },
    "bonewalker":  { "name": "Bonewalker",   "tier": 2, "xp": 14, "maxHp": 16, "attack": 7,  "defense": 3, "agility": 4 },
    "dire-wolf":   { "name": "Dire Wolf",    "tier": 2, "xp": 14, "maxHp": 14, "attack": 8,  "defense": 2, "agility": 5, "quirk": "alwaysFirst" },
    "imp":         { "name": "Imp",          "tier": 2, "xp": 14, "maxHp": 13, "attack": 7,  "defense": 2, "agility": 7 },
    "stone-golem": { "name": "Stone Golem",  "tier": 3, "xp": 22, "maxHp": 30, "attack": 9,  "defense": 7, "agility": 3, "quirk": "halveSmallHits" },
    "wraith":      { "name": "Wraith",       "tier": 3, "xp": 22, "maxHp": 24, "attack": 11, "defense": 4, "agility": 8 },
    "black-knight":{ "name": "Black Knight", "tier": 3, "xp": 22, "maxHp": 28, "attack": 10, "defense": 6, "agility": 5 },
    "wyrm":        { "name": "Wyrm",         "tier": 4, "xp": 34, "maxHp": 44, "attack": 13, "defense": 7, "agility": 7, "quirk": "breath", "quirkAmount": 8, "quirkEvery": 3 },
    "lich":        { "name": "Lich",         "tier": 4, "xp": 34, "maxHp": 36, "attack": 15, "defense": 6, "agility": 9 },
    "ogre-lord":   { "name": "Ogre Lord",    "tier": 4, "xp": 34, "maxHp": 50, "attack": 14, "defense": 8, "agility": 4 }
  },
  "variants": { "standard": { "displayName": "Standard" } }
}
```

- Item stat arrays are indexed by `tier - 1`; absent stats are 0. Monster stat blocks are initial tuning values; the harness (§9) may change any number here and nothing else.
- `Content.validate()` (both engines, tests with corrupted fixtures): `pairTiers.size == 12`; tier counts 8/8/6/3 including the centre; exactly three monsters per tier; `xpThresholds.size == maxLevel - 1` and strictly increasing; every boon in `plainBoons` has `kind stat`; every archetype's `slot` matches `archetypesBySlot`.
- `configJson("standard")` = `{"variant":"standard"}` merged with the variant's fields; the engine reads content from the embedded asset, not from `configJson`. The frozen `configJson` exists for uniformity and future variants (the "2 picks per turn" lever from the alternating revision §7 would be a variant field `picksPerTurn`, not built now).

---

## 3. Engine contract mapping

| Platform call | Mini RPG implementation |
|---|---|
| `initialStateJson(configJson, players, seed)` | `draftOrder` = 20 uids from `draftPattern` repeated (A = `playerIndex 0`); `squares` from `MapGen.generateSquares(seed)`; identical `HeroState` for both uids from `content.hero`; `pickIndex 0`, `phase "hunt"`. |
| `activePlayer` | `draftOrder[pickIndex]` while `phase == "hunt"`; after completion the platform never asks. |
| `validate` | §5.9 codes, all found, in that order, deduplicated. |
| `apply` | §5 in order: preamble → encounter → rewards → post-encounter herb → advance `pickIndex` → if `pickIndex == 20` auto-resolve pending rewards for both heroes and run the final battle. `EngineResult.status = complete` iff `winner != null`; `logJson` never null. |

---

## 4. JSON shapes

### 4.1 State

```json
{
  "phase": "hunt",
  "pickIndex": 3,
  "draftOrder": ["uid-a", "uid-b", "uid-b", "uid-a", "uid-a", "uid-b", "uid-b", "uid-a", "uid-a", "uid-b", "uid-b", "uid-a", "uid-a", "uid-b", "uid-b", "uid-a", "uid-a", "uid-b", "uid-b", "uid-a"],
  "squares": [ { "id": 0, "tier": 2, "monsterId": "imp", "claimedBy": null }, "... 25 entries in id order ..." ],
  "heroes": {
    "uid-a": { "level": 2, "xp": 8, "hp": 14, "maxHp": 20, "attack": 5, "defense": 3, "agility": 4,
               "boons": ["MIGHT"], "weapon": { "archetype": "BROADSWORD", "tier": 2 }, "armor": null, "charm": null,
               "herbs": 1, "pendingOffers": [ ["VIGOR", "KEEN_EYE", "SWIFT"] ], "pendingLoot": null },
    "uid-b": { "..." : "..." }
  },
  "winner": null,
  "winReason": null
}
```

- `maxHp/attack/defense/agility` are base + boon totals; gear is applied at battle time by `Stats.effectiveStats`. `hp ≤ maxHp` always.
- `pendingOffers` is a list of three-boon offers awaiting choices, in the order the level-ups occurred; `offer[0]` is always a plain stat boon (§5.4). `pendingLoot` is an item awaiting keep-or-swap; loot for an empty slot never becomes pending (§5.5).
- `winReason`: `"finalBattle" | "overkill" | "level" | "coin" | null`.
- `phase`: `"hunt"` or `"complete"`.

### 4.2 Submission (`HuntTurn`)

```json
{ "squareId": 7, "boonChoices": ["VIGOR"], "lootDecision": "EQUIP" }
```

- `squareId`: int 0–24, row-major.
- `boonChoices`: one boon id per entry of `pendingOffers`, in order; must be `[]` when nothing is pending.
- `lootDecision`: `"EQUIP" | "CONVERT"`, required iff `pendingLoot != null`, otherwise must be absent or null. This replaces the alternating revision's `equipChanges` list (Defaults §12.1).

### 4.3 Log (one per applied submission)

```json
{
  "uid": "uid-a", "pickIndex": 3, "squareId": 7,
  "preamble": { "boonsApplied": ["VIGOR"], "healedTo": 26, "lootDecision": "EQUIP", "convertedXp": 0, "levelUps": [] },
  "encounter": {
    "monsterId": "bonewalker", "tier": 2, "seed": 271828182, "won": true,
    "turns": [
      { "kind": "strike", "actor": "hero", "damage": 4, "critical": false, "thorns": 0, "actorHp": 26, "targetHp": 12 },
      { "kind": "strike", "actor": "monster", "damage": 3, "critical": false, "thorns": 1, "actorHp": 11, "targetHp": 23 },
      { "kind": "breath", "actor": "monster", "damage": 8, "critical": false, "thorns": 1, "actorHp": 10, "targetHp": 15 },
      { "kind": "herb",   "actor": "hero", "damage": 0, "critical": false, "thorns": 0, "actorHp": 27, "targetHp": 10 }
    ],
    "xpGained": 14, "drop": { "item": { "archetype": "PLATE", "tier": 2 }, "herb": true, "autoEquipped": true },
    "levelUps": [ { "level": 3, "offer": ["MIGHT", "KEEN_EYE", "AMBUSH"] } ]
  },
  "herbUsedAfter": false,
  "finalBattle": null
}
```

`finalBattle`, present only on the 20th pick's log: `{ "seed", "autoResolved": { "uid-a": { "boons": [...], "lootDecision": null }, "uid-b": { ... } }, "first": "uid-a", "turns": [ ...BattleTurn with actor = uid... ], "winner": "uid-b", "winReason": "finalBattle", "overkill": { "uid-a": 0, "uid-b": 3 } }`. Replay steps are `encounter.turns` followed by `finalBattle.turns`.

---

## 5. Rules clarifications (each bracketed name is a test and a vector)

### 5.1 Map generation

`rng = SeededRng(creationSeed)`; `tiers = rng.shuffle(pairTiers)`; for pair `k` in 0..11, squares `k` and `24 - k` get `tiers[k]`; square 12 gets `centerTier`. Then for each square in id order, `monsterId = rosterByTier[tier][rng.nextInt(3)]` (roster order = content file order). [map-mirror-symmetry, map-tier-counts, map-centre-tier-4, map-seed-determinism]

### 5.2 Turn preamble (before the encounter)

In order: (1) each `boonChoices[i]` is applied to hero stats and `pendingOffers` is cleared; if any boon was applied, `hp = maxHp` (after VIGOR raised it). (2) `lootDecision`: `EQUIP` swaps `pendingLoot` into its slot and converts the old item to `convertXpByTier[oldTier - 1]` XP; `CONVERT` converts `pendingLoot` itself. `pendingLoot` is cleared. (3) Conversion XP may trigger level-ups (§5.4), whose offers are generated now and answered next turn; no heal happens for them yet. [boon-applies-before-encounter, boon-heal-after-vigor, loot-equip-swap-converts-old, loot-convert-xp, loot-convert-triggers-level]

### 5.3 Combat (one routine for hunts and the final battle)

- `effectiveStats = base + gear` per stat, each clamped at ≥ 0. `critDenominator = critDenominators[keenEyeStacks]`. `thorns` and `critDamagePercent` come from gear (0 / 100 when absent). Monsters have no gear and no boons. [negative-defense-clamped]
- Order: the combatant with higher effective agility acts first; on a tie `rng.nextInt(2)` (0 = first-named combatant, which is the hero in hunts and `draftOrder[0]` in the final battle). A monster with `quirk alwaysFirst` acts first with no roll consumed. [combat-agility-first, combat-tie-coinflip, quirk-wolf-first]
- Each action is a **strike** unless the monster's `breath` quirk fires: on the monster's `quirkEvery`-th, `2·quirkEvery`-th … action it deals `quirkAmount` fixed damage ignoring defense, with no crit and no damage roll consumed. [quirk-dragon-breath, quirk-breath-consumes-no-rolls]
- Strike: `crit = rng.nextInt(critDenominator) == 0`; `roll = damageRollMin + rng.nextInt(damageRollMax - damageRollMin + 1)`; `attack` = effective attack, +`BERSERK.amount` if `hp · 2 < maxHp` at that moment, +`AMBUSH.amount` on the striker's first strike of the battle if the striker acted first. Non-crit: `damage = max(minDamage, attack / 2 - defense / 4 + roll)` (integer division, floor). Crit: `damage = max(minDamage, (attack / 2 + roll) · critDamagePercent / 100)` (integer division). A target with `halveSmallHits` halves a non-crit `damage < 4` to `max(1, damage / 2)`. [combat-crit-ignores-defense, combat-keen-eye-stacks, combat-fang-crit-bonus, combat-ambush-first-strike, combat-ambush-not-first-no-bonus, combat-berserk-below-half, combat-min-damage-1, quirk-golem-halves]
- After a strike or breath lands, the striker takes the target's `thorns` (can reach 0). Then herb check for **both** combatants who are heroes, target first: while `0 < hp < ceil(maxHp · herbThresholdPercent / 100)` and `herbs > 0`, consume one herb, `hp = min(maxHp, hp + herbHeal)`, emit a `herb` turn. This applies in the final battle only; in hunts herbs fire after the encounter (§5.6). [combat-thorns, final-battle-herb-midfight, final-battle-two-herbs-same-strike]
- The battle ends when either side's `hp ≤ 0` after thorns. If both are ≤ 0 on the same action: in a hunt the hero **loses**; in the final battle the side with the larger overkill (`-hp`) wins, then higher level, then `rng.nextInt(2)` (0 = first-named). [hunt-simultaneous-death-is-loss, final-battle-overkill-tiebreak, final-battle-level-tiebreak, final-battle-coin-tiebreak]
- RNG consumption order in a battle: optional agility coin flip, then per strike crit roll then damage roll, then optional tiebreak coin. Nothing else. Damage persists into the hero's state. [damage-persists-across-picks]

### 5.4 XP and level-ups

`xp += gained`; while `level < maxLevel` and `xp ≥ xpThresholds[level - 1]`: `level += 1` and an offer is generated: `eligible` = boons with `minLevel ≤ level` and current stacks `< maxStacks`, in content order; `plain = eligible ∩ plainBoons`; `offer[0] = plain[rng.nextInt(plain.size)]`; `offer[1..2] = rng.shuffle(eligible − offer[0]).take(2)`. Multiple level-ups from one gain produce multiple offers. XP keeps accumulating at `maxLevel` with no offers. [multi-level-single-win, level-cap-9, offer-always-has-plain, offer-respects-stacks-and-gates]

### 5.5 Loot

On a hunt win: `slot = slots[rng.nextInt(3)]`, `archetype = archetypesBySlot[slot][rng.nextInt(2)]`, `tier = monster.tier`; herb roll `rng.nextInt(100) < herbChancePercentByTier[tier - 1]` (always consumed; herb granted only if `herbs < maxHerbs`). If the hero's slot is empty the item is equipped immediately (`autoEquipped true`); otherwise it becomes `pendingLoot`. Then XP is granted and offers are rolled (§5.4). RNG order after the battle: slot, archetype, herb, then offers. [loot-auto-equip-empty-slot, loot-becomes-pending, herb-drop-capped-3, loot-rng-order]

### 5.6 Hunt encounter outcome

Win: XP, loot, damage persists. Loss: no XP, no loot, `hp = maxHp`. After either, `squares[squareId].claimedBy = uid`. Post-encounter herb: if the hero won and `hp < ceil(maxHp · 40 / 100)` and `herbs > 0`, use exactly one herb (`herbUsedAfter true`). [pick-basic-win, pick-basic-loss-full-heal, herb-auto-after-encounter, herb-not-used-above-threshold, herb-not-used-after-loss]

### 5.7 Draft advance and final battle

`pickIndex += 1`. If `pickIndex < 20` the turn ends; `activePlayer` may be the same uid as before (snake). If `pickIndex == 20`: for each hero in `draftOrder[0]` then `draftOrder[1]` order, auto-resolve: every pending offer → `offer[0]` (applied with the heal rule of §5.2), pending loot → `CONVERT`; repeat until nothing is pending (conversion XP may create offers, which auto-resolve too). Then `finalSeed = turnRng.nextUInt()`, `SeededRng(finalSeed)` runs §5.3 between the two heroes at their current `hp`, the winner is written, `phase = "complete"`. Unclaimed squares grant nothing. [snake-order-same-player-twice, snake-order-flip, final-battle-triggers-after-pick-20, final-battle-last-pick-auto-boon, final-battle-last-pick-auto-loot, final-battle-auto-resolve-chain, unclaimed-squares-grant-nothing]

### 5.8 Seed consumption per submission

`turnRng = SeededRng(seed)`: preamble offers (§5.2 step 3) → `encounterSeed = turnRng.nextUInt()` and the battle runs on `SeededRng(encounterSeed)` → loot and offers on `turnRng` → `finalSeed` if applicable. The log records `encounterSeed` and `finalSeed`. [seed-consumption-order]

### 5.9 Error codes (reporting order)

`malformedSubmission, gameComplete, notActivePlayer, squareOutOfRange, squareClaimed, boonChoicesRequired, boonChoicesNotOwed, boonChoiceCountMismatch, boonNotOffered, lootDecisionRequired, noLootPending, invalidLootDecision`. [one rejection vector per code]

### 5.10 Determinism

200 random legal games (random square, random offered boon, random loot decision) replayed twice from the same seed list; byte-identical state after every turn.

---

## 6. Test vectors

`RpgVectorTest` mirrors `QuickWw2VectorTest`: `game "miniRpg"`, `engineVersion`, `assetHashes` for `rpg/content.json`, one initial state per seed in `[7, 42, 123456]`, one vector per bracketed name in §5, one rejection per code, and a full scripted game `scripted-standard-<n>` (20 turns from base seed 7, per-turn seeds `seedForRound(7, n)`) ending in a final battle. Hand-crafted states for combat vectors set stats so the clause is unambiguous (for example a tie-agility vector where both combatants have agility 9). The TS vector test picks the file up automatically once `functions/test/vectors.test.ts` iterates `test-vectors/*.json`.

---

## 7. Client

### 7.1 Rendering base (first consumer of the pixel rules; reusable by later games)

`core:ui` gains `PixelCanvas(virtualWidth = 256, virtualHeight = 224) { scope -> }`: computes the largest integer scale that fits, centres, and draws with `FilterQuality.None`. Sprites for this phase are 16×16 bitmaps in a single indexed palette (`PixelPalette` plus up to 16 entries defined in `ui/Palette.kt`); drawn via `drawImage` with integer offsets. Placeholder art (solid tiles with a glyph) is acceptable until the sprite sheet exists; the screen layout must not change when real sprites land.

### 7.2 Screens (`RpgUi : GameUi`)

1. **Draft screen** — 5×5 tile grid (each 32×32 virtual px, 160×160 board) with the monster sprite, a tier pip, cracked-earth tile when claimed (tinted by claimant). Two hero panels (level, hp/maxHp, atk/def/agi, gear icons, herbs, boons) above the board; the opponent's panel is fully visible. Cursor (d-pad feel): first tap moves the cursor, second tap on the same square opens a confirm box showing the monster's stats and reward tier; confirm builds the `HuntTurn`. If `pendingOffers` or `pendingLoot` is non-empty the board is locked until the choice screens (3, 4) are done; the choices are held in a `remember(game.id, game.round)` `TurnDraft` and sent together with the pick.
2. **Battle replay** — first-person layout: black backdrop, monster sprite centred in a frame, hero stats window top-left, message box on the bottom third typing one line per `BattleTurn` from `Strings.kt` ("The Bonewalker attacks! Thou art dealt 3 damage."), `rememberReplayState(stepCount = turns.size, stepMillis = 800)`, tap anywhere to `skipToEnd()`. Own encounters replay in full; the opponent's encounters show a result card (monster, won/lost, XP, drop, level-ups) with a **Watch** button that opens the full replay. Unseen logs are detected by id as in Quick WW2.
3. **Boon choice** — three DW-style panels per pending offer (name, effect, one-line synergy hint from `Strings.kt`), presented in offer order; selection fills `boonChoices`.
4. **Keep-or-swap sheet** — current item vs `pendingLoot` side by side with stat deltas and the conversion XP; fills `lootDecision`.
5. **Final battle + end screen** — the 20th pick's log replays the encounter then the final battle with both heroes' sprites; the end screen shows winner, `winReason` text, both heroes' final builds, back to home.

The dialect strings live in one table (`Strings.kt`) keyed by event kind so tone can be dialled down in one place.

---

## 8. Unit tests (B1 gate)

`MapGenTest` (§5.1), `CombatTest` (every §5.3 clause with hand-set stats and seeds), `ProgressionTest` (§5.4–5.6), `EngineTest` (§5.2, §5.7, every error code, snake order), `DeterminismTest` (§5.10), `ContentTest` (validation on the shipped asset and corrupted fixtures). Fuzz: 300 random games asserting after every turn: `0 ≤ hp ≤ maxHp`, `herbs ≤ 3`, level within 1..9, `pendingOffers` empty for the non-active player unless it is their pick next, claimed squares never unclaimed, `pickIndex ≤ 20`, game completes exactly at 20.

---

## 9. Balance harness (B3)

- `androidUnitTest/balance/`, gated by `-Pbalance=true`, `-PbalanceGames=N` (default 1,000), `-PbalanceGrids=G` (default 50 map seeds).
- Bots pick by score: `score(square) = winProbability · rewardValue(tier, build) − denialWeight · opponentValue(square)`. `winProbability` is estimated by running `Combat.resolveBattle` 24 times with derived seeds (the engine is pure, so this is cheap and exact-in-expectation); `rewardValue` weights XP plus the bot's preference for the slot/archetype it wants; `opponentValue` is the opponent bot's own `rewardValue` for that square (bots know each other's archetype from the visible build). `denialWeight` and archetype preferences live in `BotConfig`.
  - **CritAssassin**: KEEN_EYE ×2 > MIGHT; wants FANG_CHARM, RAVAGER_AXE. **AlphaStriker**: SWIFT ×2 > AMBUSH; WIND_CHARM, BROADSWORD. **Juggernaut**: VIGOR ×3 > IRON; PLATE, SPIKED_MAIL. **BloodKnight**: BERSERK > MIGHT > VIGOR; RAVAGER_AXE. Boon choice = first offered boon in the bot's preference list, else `offer[0]`. Loot decision = EQUIP if the item matches the wanted archetype or the slot value improves the bot's key stat, else CONVERT.
  - **Pivot mode** (§6.4 of the alternating revision): a bot may switch preference list once, at pick 5, to the archetype that counters the opponent's revealed build on the counter-wheel.
- Runs and asserts (hard when gated): (1) 1,000 random-bot games across grids: zero invalid states, median levels gained per hero ≥ 6. (2) Round-robin of the four bots, each ordered pairing with both first-pick assignments, `N` games spread across `G` grids: no head-to-head above 60%; no archetype below 40% overall. (3) First picker's overall win rate in [0.45, 0.55]. (4) Same round-robin with pivot mode on: same bounds. Reports to `build/reports/rpg-balance.md`; every content change and its before/after numbers is recorded in §12.
- Levers in order: monster stat blocks, item arrays, boon amounts, `xpThresholds`, `herbChancePercentByTier`, `draftPattern`, `pairTiers`. Rule changes are flagged to Greg.

---

## 10. End-to-end

- `e2e/run.mjs` gains a `miniRpg` section: queue both into `miniRpg_standard`; assert `draftOrder.length == 20`, 25 squares with the 8/8/6/3 tier counts, both heroes identical; the e2e imports the built TS engine (`functions/lib/games/rpg/definition.js`) and `seedForRound`, reads `seed` from the game doc, and for each of the 20 scripted picks (lowest unclaimed square, `offer[0]` for every pending offer, `CONVERT` for pending loot) computes the expected state and log locally, submits, and compares with Firestore. Also assert one rejection (`squareClaimed`) and that a stalled game is forfeited by `sweepNow`. Deterministic seeds come from the emulator env flag as before.
- `e2e/opponent.mjs --game miniRpg` plays the same lowest-square policy.

---

## 11. Sub-phase checklist

- [ ] B1.1 `content.json`, `generateSharedAssets`, `ContentTest`
- [ ] B1.2 `MapGen`, `Stats`, `Combat`, `Progression`, `Engine` with §8 tests
- [ ] B1.3 `Definition` adapter, `RpgVectorTest` exporting `test-vectors/rpg.json` with every §5 name
- [ ] B1.4 determinism and fuzz
- [ ] B2.1 TS twin, vector test green, asset hash, `registry.ts` with 24 h deadline
- [ ] B2.2 e2e section and opponent policy
- [ ] B3.1 bots, harness, gating, report; all four checks pass; content changes recorded in §12
- [ ] B4.1 `PixelCanvas` in `core:ui`, palette, placeholder sprites
- [ ] B4.2 screens 1–5, `Strings.kt`, replay bookkeeping, `TurnDraft`
- [ ] B4.3 Android emulator full game vs the opponent script; screenshots
- [ ] B5.1 iOS simulator ↔ Android; real project; tic-tac-toe and Quick WW2 vectors unchanged
- [ ] B6 sprite sheet, chiptune loops and SFX (art and audio only; no rules work remains)

---

## 12. Defaults chosen and balance record

1. `lootDecision` (EQUIP/CONVERT on the pending item) replaces the revision's `equipChanges` list; there is no other equipment management.
2. Loot for an empty slot auto-equips at drop time.
3. Multiple level-ups from one XP gain queue multiple offers, all answered on the next turn.
4. Monster quirks and herbs are engine work from B1 (they are config-driven and vector-covered), not polish; polish is art and audio only.
5. Herb between hunts fires once, after a won encounter, below 40% of max HP; in the final battle herbs fire mid-fight, possibly several on one strike, up to the three held.
6. Simultaneous death in a hunt is a loss for the hero; in the final battle overkill, then level, then coin.
7. Ambush applies only when its holder acts first; Berserk is checked per strike against current HP.
8. Crit damage uses the attack roll without defense, scaled by the Fang Charm's percentage.
9. The hunt's post-loss full heal and the level-up heal both restore to the (possibly increased) max HP.
10. Monster stat blocks above are first-pass values for the harness to tune.

Balance record (fill in during B3):

| Date | Change | Median levels before → after | Worst head-to-head before → after | First-picker win rate before → after |
|---|---|---|---|---|
