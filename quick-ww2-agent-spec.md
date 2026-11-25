# Quick WW2 — Agent Implementation Spec

Revised 2026-11-20. This is the single source for Quick WW2. It replaces the previous spec and `quick-ww2-platform-revision.md`, which is deleted. `IMPLEMENTATION-PLAN.md` §3 sequences the work and points here for every rule.

A short Axis & Allies-style strategy game for the quickgames platform. 1v1, asynchronous, Android and iOS. Exactly two sides, **Axis** and **Allies**; each player controls one whole side (there are no separate nations). Turns alternate, the active player submits the whole turn at once, the server resolves it immediately, then the opponent plays. Perfect information.

Target length: 6–10 rounds, played over days. Every tuning value lives in `Ww2Content` (Kotlin constants); no rules code reads a literal number.

---

## 0. Platform facts this spec builds on

- One engine, written in Kotlin. The Cloud Run server (`server/`) and both apps run the same `GameDefinition`. There is no TypeScript implementation, no parity test vectors and no shared-asset pipeline.
- Module pattern: `games/quickww2` (engine: `jvm` + Android + iOS, no Compose) and `games/quickww2-ui` (Compose screen). Register in `server/.../Registry.kt`, `MainActivity.kt` and `MainViewController.kt`; add the module to `server/build.gradle.kts` and `settings.gradle.kts`. `GameType.QUICK_WW2` (`quickWw2`) already exists.
- Contract (`core/engine/.../GameEngine.kt`): `GameEngine { activePlayer, validate, apply }` over JSON strings; `GameDefinition { gameType, displayName, configVariants, engineVersion, turnDeadlineHours, configJson(variant), engine(), initialStateJson(configJson, players, seed), publicStateJson }`.
- The lobby is platform, not game code. Games start from the generic start/join lobby (`openGames`, `core:ui` and `server/OpenGames.kt`). The game module only supplies a `GameDefinition` and never touches matchmaking. The server shuffles host and guest, so `playerIndex 0` (who plays Axis) is random.
- The server generates a hidden dice seed per submission and passes it to `apply`. Clients never see a seed before the dice are rolled.
- There is no read path for `logs/` in the client. What happened last turn travels in the public state as `lastTurn` (as `hexstead` does with `lastTurnEvents`).
- Resign, push notifications and the deadline sweep already exist. The sweep runs weekly, so a 72 h deadline is enforced up to about a week late.
- Config is re-read from `configJson(variant)` on every submission (not frozen per game). A tuning deploy changes games in progress. Accepted while testing between two phones.

---

## 1. Game overview

- **Axis** moves first each round. A round is one Axis turn then one Allies turn.
- Map: one fixed, hand-authored board of 8 land territories and 2 sea zones (§3), defined as Kotlin constants. Map data is kept separate from rules so more maps can be added later as data.
- Territories produce income. Spend it on units, move, fight with dice, capture territory.
- **Win conditions**, checked during and after every turn:
  1. Capture the enemy capital → immediate win.
  2. Turn cap (default 10 rounds): after the Allies turn of the last round, higher income wins; tie → higher total unit value on the board; tie → Allies.
  3. Resignation or deadline expiry → forfeit (platform status, not engine state).

### Why capitals fall in this version

The first draft let a defender put every purchase on its capital, and infantry defend at 2 for 3 IPCs, so capitals were close to untakeable and most games would have gone to the turn cap. This version changes three things, all tunable:

1. **Small map.** The capitals are two land steps apart (Germany – Poland – Russia), and Britain is one sea zone from Germany. A tank or fighter in a capital threatens the other capital from the start.
2. **Capital placement limit.** At most `capitalPlacementLimit` (default 3) land and air units can be bought per turn, all placed at the capital. Warships are not counted. Extra income banks or goes on quality units and warships, so infantry walls grow slowly.
3. **Both sides start with money.** `startingTreasury` is 12 for each side, so Allies can reinforce on their first turn instead of facing two Axis attacks with starting forces only.

## 2. Game modes

Chosen by the host when starting a game (the variant picker, §11 D0). Same rules and map; only the buildable units differ. Starting forces never change, so unbuildable types still exist at setup as irreplaceable units.

| Variant id | Name | Buildable |
|---|---|---|
| `classic` | Classic | all five |
| `tanks-and-soldiers` | Tanks and Soldiers | INFANTRY, TANK |
| `navy-and-air` | Navy and Air | WARSHIP, FIGHTER |
| `army-battle` | Army Battle | INFANTRY |

A variant is a preset over the base config (`buildableUnits`, plus optional `unitStats`, `incomeOverrides`, `turnCap` overrides). The engine has no variant-specific branches.

## 3. The map

Region ids are stable strings used in state and moves. Regions are listed in **map order**, used for every deterministic iteration (battle order, state serialization).

### Land territories

| id | Name | Income | Start owner | Port | Capital | Land adjacency | Sea adjacency |
|---|---|---|---|---|---|---|---|
| `germany` | Germany | 5 | Axis | yes | Axis | france, italy, poland | north-sea |
| `france` | France | 3 | Axis | yes | — | germany, italy | north-sea, mediterranean |
| `italy` | Italy | 3 | Axis | yes | — | germany, france | mediterranean |
| `poland` | Poland | 2 | Axis | no | — | germany, russia, ukraine | — |
| `russia` | Russia | 5 | Allies | no | Allies | poland, ukraine | — |
| `ukraine` | Ukraine | 2 | Allies | no | — | poland, russia, middle-east | — |
| `britain` | Britain | 4 | Allies | yes | — | — (island) | north-sea |
| `middle-east` | Middle East | 2 | Allies | yes | — | ukraine | mediterranean |

Starting income: Axis 13, Allies 13.

### Sea zones

| id | Adjacent land | Adjacent sea |
|---|---|---|
| `north-sea` | germany, france, britain | mediterranean |
| `mediterranean` | france, italy, middle-east | north-sea |

Sea zones have no owner and no income. Each region also carries `x`, `y` on a 1000×700 virtual canvas for the schematic board.

Shape of the board: a land front (Poland and Ukraine) between the two capitals; Britain reachable only by sea, and able to strike Germany across the North Sea; Italy and the Middle East facing each other across the Mediterranean.

Map validation (run in a test, and at startup in debug builds): ids unique; every reference exists; land lists hold only land and sea lists only sea; adjacency symmetric; exactly one capital per side; every land region reachable from every other over the combined graph.

## 4. Units

| Type | Cost | Attack | Defense | Move | Domain | Notes |
|---|---|---|---|---|---|---|
| INFANTRY | 3 | 1 | 2 | 1 | land | Attacks at 2 when paired with an attacking artillery |
| ARTILLERY | 4 | 2 | 2 | 1 | land | Boosts one attacking infantry |
| TANK | 5 | 3 | 2 | 2 | land | |
| FIGHTER | 8 | 3 | 3 | 3 | air | Lives on land; returns to its launch territory after combat |
| WARSHIP | 7 | 3 | 3 | 2 | sea | Carries up to 2 land units |

d6. A unit hits when its roll ≤ its attack (attacking) or defense (defending).

### Content constants (`Ww2Content.kt`)

```
units: per type cost/attack/defense/move/domain (table above)
casualtyOrder = rollOrder = [INFANTRY, ARTILLERY, TANK, FIGHTER, WARSHIP]
artilleryBoostedInfantryAttack = 2
fighterAttackRange = 2
fighterRelocateRange = 3
warshipCapacity = 2
dieSides = 6
capitalPlacementLimit = 3
startingTreasury = { axis: 12, allies: 12 }
turnCap = 10
startingForces (§9)
variants (§2)
```

`configJson(variant)` serializes the base values merged with the variant's overrides, plus `"variant": "<id>"`. The engine reads only that merged config.

## 5. Turn structure

One submission per turn, processed in this order:

1. **Purchase.** Spend the treasury.
2. **Combat moves**, in list order.
3. **Combat resolution**: all sea battles in map order, then all land battles in map order.
4. **Noncombat moves**, in list order.
5. **Placement**: purchased land and air units appear at the capital; purchased warships appear in `warshipPlacement`.
6. **Income** added to the treasury.
7. Victory and turn-cap check; turn passes.

### 5.1 Submission

```json
{
  "side": "axis",
  "gameRound": 1,
  "purchases": { "INFANTRY": 2, "TANK": 1 },
  "warshipPlacement": null,
  "combatMoves":    [ { "unitType": "TANK", "count": 1, "from": "poland", "to": "ukraine", "via": null, "cargo": [] },
                      { "unitType": "INFANTRY", "count": 2, "from": "britain", "to": "germany", "via": "north-sea", "cargo": [] } ],
  "noncombatMoves": [ { "unitType": "INFANTRY", "count": 2, "from": "germany", "to": "poland", "via": null, "cargo": [] } ]
}
```

- `side` and `gameRound` must match the state (`wrongSide`, `wrongRound`).
- Moves carry no path. The engine works out whether `to` is reachable from `from` under the rules below, so the client never builds paths. The one exception is `via`, the sea zone of a sea crossing (§5.3), which is null for every other move. The staging routine returns it with each legal destination, so the client just copies it.
- `cargo` applies only to WARSHIP moves: one entry per land unit travelling with the warships.
- `warshipPlacement` is required when warships are purchased and must be null otherwise.
- Every section may be empty; an empty turn is legal.

### 5.2 Staging and validation

Validation and application share one staging routine, exposed as a plain Kotlin function so the client can test a candidate move without JSON. Validation runs it without dice; application runs it with the seeded dice.

- Each combat move is applied to a staging copy in list order and must be legal at its point in the list. Moved units are tracked per (region, type, side); a unit that has moved cannot move again (`unitMovedTwice`). Units that made a combat move cannot make a noncombat move.
- Validation cannot know battle outcomes, so a noncombat move may neither start from nor end in a region that is a combat destination this turn (`invalidNoncombatDestination`). At apply time every noncombat move is re-checked after battles; a failure rejects the whole submission (`postCombatMoveIllegal`, a guard that should never fire).
- Any failure rejects the whole submission. The error list is every error found, in §8 order, deduplicated.

### 5.3 Movement

- **Land units.** Up to `move` steps over `adjacentLand`. Every intermediate region must be owned by the mover and free of enemy units (`enemyPassThrough`, `unreachable`). Combat move: `to` is enemy-owned or holds enemy units (`invalidCombatDestination`). Noncombat move: `to` is friendly and holds no enemy units; a territory captured this turn counts as friendly.
- **Sea crossing** (one move, either phase). A land unit moves from a land region to another land region `to` through one sea zone `via` that is adjacent to both. Combat if `to` is enemy (an amphibious assault), noncombat if friendly. The crossing is the unit's whole move. At the end of the combat moves (for combat crossings) or of the noncombat moves (for noncombat crossings), `via` must hold at least one friendly warship (`noTransportInZone`). Warships may move into `via` earlier in the same turn, so a warship can sail to a zone and ferry troops across in one turn. No port is needed.
- **Ferry capacity.** Per sea zone per side per turn: land units crossing through it in either phase, plus cargo still aboard there at the end of the turn, ≤ `warshipCapacity × friendly warships in the zone` (`transportOverCapacity`). A warship that ferried units this turn may not have moved away afterwards.
- **Loading and unloading** (for voyages longer than one zone). Noncombat: a land unit moves from a land region into an adjacent sea zone holding a friendly warship and stays aboard as cargo. A later warship move carries it. Cargo leaves a zone onto an adjacent land region: noncombat if friendly, combat if enemy. Riding along on a warship's move does not count as the cargo's own move.
- **Warships.** Up to 2 steps over `adjacentSea`; may not pass through a zone holding enemy warships. A combat move may end in any reachable zone, with or without enemy warships, so cargo can be carried into position and landed in the same turn. A noncombat move may not end in a zone with enemy warships. A warship move's `cargo` must be aboard in `from`. Leaving cargo with no warship is illegal.
- **Fighters.** Combat: any region within `fighterAttackRange` steps over the combined graph (ownership ignored) that holds enemy units or is enemy land (`fighterOutOfRange`). Surviving attacking fighters return to `from` after resolution. Noncombat: relocate to a friendly land region within `fighterRelocateRange` steps (`fighterInvalidDestination`). Fighters never end a turn at sea.

### 5.4 Purchases and placement

- Only `buildableUnits` (`unbuildableUnit`); total cost ≤ treasury (`overspend`); negative counts are `malformedSubmission`.
- Land and air units bought this turn ≤ `capitalPlacementLimit` (`placementLimitExceeded`). They are placed at the mover's capital. The mover always owns its capital on its own turn, because losing it ends the game.
- Warships go to `warshipPlacement`: a sea zone adjacent to a port the mover owns (`placementNotPortAdjacent`) that holds no enemy warships after combat (`placementIntoEnemyZone`). Missing when warships were bought → `missingWarshipPlacement`.

## 6. Combat

### 6.1 Who fights

- A **land battle** happens in every land region entered by a combat move. Attackers: every unit moved there this turn, including landed cargo and fighters. Defenders: all enemy units there.
- A **sea battle** happens in every sea zone where a combat-moving warship meets enemy warships, and in every sea zone attacked by fighters. Only warships and fighters roll; cargo never rolls and is never taken as a casualty.
- When a side has no warships left in a zone after its sea battle, its cargo there dies, along with every unit crossing through that zone this turn. Any amphibious assault through or from that zone is cancelled (recorded with `cancelled: true` and no rounds). If both sides lose all warships in the same round, both cargos die.
- Amphibious attackers fight like any other attacker, and there are no retreats.
- Invariant (fuzz-tested): after resolution no zone holds warships of both sides.

### 6.2 Resolution

- `turnRng = SeededRng(seed)`; for each battle in resolution order, `battleSeed = turnRng.nextUInt()` and the battle uses `SeededRng(battleSeed)`. A cancelled battle uses no seed.
- Each round, attackers roll one die per unit in `rollOrder` (boosted infantry first), then defenders. Infantry boost = `min(surviving attacking artillery, surviving attacking infantry)`, recomputed every round. Hits are applied after both sides roll; each side removes casualties in `casualtyOrder`; extra hits are wasted. Repeat while both sides have units that roll. No retreats.
- The attacker takes a land region if any attacking **land** unit survives. Ownership flips and survivors stay. If only fighters survive, nothing flips.
- Capturing a capital ends the game at once: the winner is set, the remaining phases (noncombat, placement, income) are skipped, and `lastTurn` still records the battles.

## 7. Income, turn pass, turn cap

- Income = sum of owned land regions' income (with `incomeOverrides`), added after placement.
- Turn pass: Axis → Allies in the same `gameRound`; Allies → Axis with `gameRound + 1`.
- Turn cap: checked once, after the Allies turn of round `turnCap`. Higher income wins; tie → higher unit value (sum of cost × count, every unit on the board, cargo included); tie → Allies. `winReason = "economicAtTurnCap"`.

## 8. Error codes

Stable strings, in reporting order: `malformedSubmission, gameComplete, wrongSide, wrongRound, unknownUnitType, unknownRegion, unbuildableUnit, overspend, placementLimitExceeded, notEnoughUnits, unreachable, enemyPassThrough, invalidCombatDestination, invalidNoncombatDestination, noTransportInZone, transportOverCapacity, fighterOutOfRange, fighterInvalidDestination, unitMovedTwice, missingWarshipPlacement, placementNotPortAdjacent, placementIntoEnemyZone, postCombatMoveIllegal`. The UI maps each to a sentence.

## 9. Starting forces

Axis: germany 3 INFANTRY, 1 ARTILLERY, 1 TANK, 1 FIGHTER · poland 2 INFANTRY, 1 TANK · france 2 INFANTRY · italy 1 INFANTRY, 1 ARTILLERY · mediterranean 1 WARSHIP

Allies: russia 3 INFANTRY, 1 ARTILLERY, 1 TANK, 1 FIGHTER · ukraine 2 INFANTRY, 1 TANK · britain 2 INFANTRY, 1 FIGHTER · middle-east 2 INFANTRY · north-sea 1 WARSHIP

Initial values; the balance harness tunes them.

## 10. Kotlin shape

### 10.1 Files (`games/quickww2/src/commonMain/kotlin/com/greenopal/quickgames/games/quickww2/`)

- `Ww2Content.kt`: map, unit stats, constants, starting forces, variants.
- `Ww2Models.kt`: serializable state, submission, move, battle report and config types.
- `Ww2Rules.kt`: reachability, movement legality, capacity, placement, the staging routine.
- `Ww2Combat.kt`: `resolveBattle(...)` → `BattleReport`.
- `Ww2Engine.kt`: `QuickWw2Engine : GameEngine` and `QuickWw2Definition : GameDefinition` (`configVariants` as §2, `turnDeadlineHours = 72`, `engineVersion = 1`).

JSON: kotlinx.serialization with `Json { encodeDefaults = true; ignoreUnknownKeys = true }`.

### 10.2 State

```json
{
  "sides": { "axis": "uid-a", "allies": "uid-b" },
  "gameRound": 1,
  "activeSide": "axis",
  "regions": {
    "germany":   { "owner": "axis", "units": [ { "type": "INFANTRY", "owner": "axis", "count": 3 } ] },
    "north-sea": { "owner": null,   "units": [ { "type": "WARSHIP", "owner": "allies", "count": 1 } ] }
  },
  "treasuries": { "axis": 12, "allies": 12 },
  "winner": null,
  "winReason": null,
  "lastTurn": null
}
```

- `regions` has every region in map order; `units` sorted by owner (axis first) then `rollOrder`, with no zero stacks. A sea zone holds warships and cargo of one side only.
- `sides` comes from `players` by `playerIndex` (0 = Axis). `initialStateJson` ignores its seed.
- `activePlayer` = `sides[activeSide]`. `validate` also rejects a `playerId` other than the active one (`wrongSide`).
- `apply` returns `status = complete` when `winner` is set, with `winnerUid = sides[winner]`.

### 10.3 `lastTurn`

Overwritten by every applied turn; the client shows it to whoever has not seen it.

```json
{
  "side": "axis", "gameRound": 1,
  "purchases": { "INFANTRY": 2 },
  "battles": [
    { "regionId": "ukraine", "attacker": "axis", "amphibious": false, "cancelled": false,
      "attackers": { "TANK": 1, "INFANTRY": 2 }, "defenders": { "INFANTRY": 2, "TANK": 1 },
      "rounds": [ { "attackerRolls": [2, 5, 6], "defenderRolls": [1, 4, 3], "attackerHits": 1, "defenderHits": 1,
                    "attackerLosses": { "INFANTRY": 1 }, "defenderLosses": { "INFANTRY": 1 } } ],
      "attackerLosses": { "INFANTRY": 1 }, "defenderLosses": { "INFANTRY": 2, "TANK": 1 },
      "conqueror": "axis" }
  ],
  "cargoLost": {},
  "incomeCollected": 13
}
```

Battle seeds are not exposed in state.

## 11. Build phases

- **D0 Platform prerequisites** (in `core:engine`, `core:ui` and `server`, not in the Quick WW2 modules).
  1. Generic variant picker in the lobby: `fun variantName(variant: String): String` on `GameDefinition` (default returns the id). The start screen asks for a variant when any game has more than one; the join list and menu rows show the variant name. Works for every game, not only Quick WW2.
  2. Nothing else: `lastTurn` replaces a logs read path.
  Gate: existing games still pass `npm run e2e`; starting Quick WW2 asks for a variant.
- **D1 Engine** (§10). Tests in `commonTest`:
  - Map validation passes on the shipped map and fails on corrupted copies (broken symmetry, unknown id, two capitals).
  - Every movement case in §5.3, positive and negative.
  - Sea crossings: Britain → Germany via the North Sea in one turn; a warship sails into `via` and ferries troops in the same turn; crossing with no warship rejected; ferry capacity counted across both phases; a ferrying warship cannot sail away afterwards.
  - Cargo: load, unload and over-capacity; cargo carried two zones then landed. Amphibious assaults cancelled and crossing units lost when the warships are sunk; both cargos die on mutual annihilation.
  - Artillery boost recomputed each round, casualty order, wasted overkill hits, only-fighters-survive does not capture, fighters return home.
  - Capital capture ends the turn early; turn cap and both tiebreakers; turn cap not checked after an Axis turn.
  - Purchases: each variant's buildable set, overspend, placement limit, warship placement rules.
  - Every error code at least once; `unitMovedTwice` for land and fighters.
  - A battle's outcome depends only on its own seed.
  - Determinism: 200 random legal turns applied twice from the same seeds give byte-identical state JSON.
  - Fuzz: 300 random games asserting after every turn: counts ≥ 0, no zone with both sides' warships, cargo ≤ capacity, treasuries ≥ 0, game ends by the turn cap.
  Gate: `:games:quickww2:jvmTest` green.
- **D2 Server and e2e.** Register the definition on the server. Extend `e2e/run.mjs`: host and join a `quickWw2` game; a turn with a real attack produces a battle in `lastTurn` and changes ownership or losses; an unbuildable purchase in a restricted variant is rejected; empty turns to the cap produce the economic winner.
  Gate: `npm run e2e` green.
- **D3 Client** (`games/quickww2-ui`), functional rather than pixel art:
  - Schematic board from the region coordinates: tappable tiles colored by owner, income, unit counts (`3i 1a 1t 1f`), capital star, adjacency lines.
  - Staging: tap a region, choose unit type and count, legal destinations highlight (from the engine's staging routine), tap one to stage. Combat or noncombat is decided by the destination. Staged moves list with undo.
  - Buy panel: only buildable units, treasury remaining, placement-limit counter, warship placement zone picker.
  - Submit sends the whole turn; nothing is sent before.
  - Turn report from `lastTurn`: each battle's forces, hits per round, losses, result; income collected. Shown for the opponent's turn and the player's own.
  - End screen: winner and reason.
  Gate: a full game between two Android phones on the real project, including one amphibious landing and one fighter attack.
- **D4 Balance check**, two steps:
  - **Early, right after D1:** a quick bot-vs-bot run (LandRush vs Turtle, both sides, 200 games, classic) to confirm capitals actually fall before the UI is built. Target: under 30% of games reach the turn cap. If not, tune §4 constants and §9 before D3.
  - **Full, after hand play:** bots LandRush (tanks toward the enemy capital), Turtle (defense and economy), SeaRaider (warships and amphibious strikes on Britain, Germany, Italy, Middle East), Balanced; every ordered pairing, 500 games each, per variant; `jvmTest` gated by `-Pbalance=true`. Bots use only the engine's public functions; their thresholds live in a `BotConfig`, separate from game tuning. Accept (hard for classic, report-only for the others): Axis win rate 45–55%; no bot beats every other from both sides; under 20% of games reach the turn cap. Tune only content values; rule changes go back to Greg. Record each change in §13.
- **D5 Polish.** Step-by-step dice playback, pixel-art board, drag-to-move, per-error messages, iOS once the CocoaPods blocker is cleared.

Deploy as for the other games: `./deploy.sh`, then reinstall the app on each phone. Server and app must be updated together; an older server deletes an open game whose type it does not recognise.

## 12. Open questions (defaults chosen — proceed unless overridden)

1. **Last-turn grab at the turn cap.** The cap compares current income after the Allies' final turn, so that turn can be an all-in attack with no reply. Alternative: compare total income collected over the game. Default: current income.
2. **One-turn amphibious assaults** (§5.3 sea crossing). Because Britain can now hit Germany out of nowhere, Germany needs a garrison, and that cost to Axis is part of balance. Lever if it proves too strong: `warshipCapacity = 1`.
3. **Side assignment.** Random. Alternative: the host picks Axis or Allies.
4. **Navy and Air with only two sea zones** may be thin; it stays in the list until the balance check says otherwise.
5. Shore bombardment excluded. Fighters too strong → reduce `fighterAttackRange` to 1 before changing stats. If Axis wins too often, lower Axis `startingTreasury` before touching forces.

## 13. Balance record

| Date | Variant | Change | Axis win rate before → after | Turn-cap % before → after | Dominant bot before → after |
|---|---|---|---|---|---|
