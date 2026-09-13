# Quick WW2 — Platform Revision (MVP build spec)

This document amends `quick-ww2-agent-spec.md` so that Quick WW2 can be built against the platform exactly as it exists in this repo (see `IMPLEMENTATION-PLAN.md` §0). Where the two conflict, this document wins. The spec remains authoritative for the map (§2), units (§3), turn structure (§4), modes (§1a), starting forces (§8), bots (§10 Phase 2), and UI intent (§9). Everything below is written so a coding agent can execute it without further design decisions; defaults for the few remaining judgment calls are in §12.

Prerequisite: `IMPLEMENTATION-PLAN.md` §2 (P0) is complete. In particular P0.2 (per-submission seeds), P0.3 (frozen `configJson` on the game doc), P0.4 (`logs` flow into `GameScreen`), P0.5 (variant picker), and P0.7 (shared assets).

---

## 1. Module layout

```
games/quickww2/
  build.gradle.kts                       copy of games/tictactoe/build.gradle.kts + generateSharedAssets(shared-assets/quickww2)
  src/commonMain/kotlin/com/greenopal/quickgames/games/quickww2/
    Model.kt                             enums, RegionDef/MapDef, UnitStack/RegionState, GameState, Move/TurnSubmission/Placement, BattleLog/CombatRound, GameConfig
    Assets.kt                            decode SharedAssets.MAP_JSON / CONFIG_JSON, MapDef.validate()
    Rules.kt                             pure functions: movement legality, reachability (BFS), transport capacity, placement legality
    Combat.kt                            resolveBattle(attackers, defenders, config, rng): BattleLog
    Engine.kt                            QuickWw2Engine: initialState / validateTurn / applyTurn / checkVictory
    Definition.kt                        QuickWw2Definition : GameDefinition, QuickWw2Engine adapter : GameEngine (JSON boundary)
    TurnBuilder.kt                       client-side staging (pure, no Compose)
    ui/                                  QuickWw2Ui : GameUi and screens (§7)
  src/androidUnitTest/kotlin/.../
    RulesTest.kt CombatTest.kt EngineTest.kt DeterminismTest.kt TurnBuilderTest.kt
    QuickWw2VectorTest.kt                exports test-vectors/quickww2.json
    balance/Bots.kt balance/BalanceHarness.kt   gated by -Pbalance=true
shared-assets/quickww2/map.json
shared-assets/quickww2/config.json
functions/src/games/quickww2/
    model.ts rules.ts combat.ts engine.ts definition.ts   mirror of the Kotlin files, same function names
functions/test/quickww2.vectors.test.ts (or extend vectors.test.ts to iterate every file in test-vectors/)
test-vectors/quickww2.json
```

Registration: `GameRegistry(listOf(TicTacToeDefinition, QuickWw2Definition))` and `GameUiRegistry(listOf(TicTacToeUi, QuickWw2Ui))` in `MainActivity.kt` and `MainViewController.kt`; `registry.ts` adds `quickWw2Definition`. `GameType.QUICK_WW2` already exists with id `quickWw2`; the queue ids are `quickWw2_classic`, `quickWw2_tanks-and-soldiers`, `quickWw2_navy-and-air`, `quickWw2_army-battle`.

---

## 2. Shared assets

### `shared-assets/quickww2/map.json`

One object `{ "version": 1, "regions": [ ... ] }`. Region order in the file is the canonical **map order** used for battle resolution and every deterministic iteration. Each region:

```json
{ "id": "germany", "name": "Germany", "type": "land", "income": 6, "isPort": true,
  "startOwner": "axis", "capitalOf": "axis",
  "adjacentLand": ["western-europe", "eastern-europe", "southern-europe"],
  "adjacentSea": ["baltic", "north-sea"],
  "x": 540, "y": 300 }
```

- `type`: `"land"` or `"sea"`. Sea zones have `income 0`, `isPort false`, `startOwner null`, `capitalOf null`; their `adjacentLand` lists the land they touch and `adjacentSea` the sea zones they touch (spec §2 sea table).
- `capitalOf`: `"axis"` for germany, `"allies"` for russia, `null` elsewhere.
- `x`, `y`: schematic layout coordinates on a 1000×700 virtual canvas for the node-map UI (§7). Land ordering: put the twelve land territories then the four sea zones, in the order of the spec tables.
- Load-time validation (`MapDef.validate()`, run by both engines on every decode and covered by tests with corrupted fixtures): all ids unique; every adjacency reference exists; `adjacentLand` of a land region contains only land, `adjacentSea` only sea; adjacency is symmetric (`a ∈ adj(b) ⇔ b ∈ adj(a)`) across both lists; exactly one capital per side; every land region reachable from every other over the combined graph.

### `shared-assets/quickww2/config.json`

```json
{
  "version": 1,
  "units": {
    "INFANTRY":  { "cost": 3, "attack": 1, "defense": 2, "move": 1, "domain": "land" },
    "ARTILLERY": { "cost": 4, "attack": 2, "defense": 2, "move": 1, "domain": "land" },
    "TANK":      { "cost": 5, "attack": 3, "defense": 2, "move": 2, "domain": "land" },
    "FIGHTER":   { "cost": 8, "attack": 3, "defense": 3, "move": 3, "domain": "air" },
    "WARSHIP":   { "cost": 7, "attack": 3, "defense": 3, "move": 2, "domain": "sea" }
  },
  "casualtyOrder": ["INFANTRY", "ARTILLERY", "TANK", "FIGHTER", "WARSHIP"],
  "rollOrder":     ["INFANTRY", "ARTILLERY", "TANK", "FIGHTER", "WARSHIP"],
  "artilleryBoostedInfantryAttack": 2,
  "fighterAttackRange": 2,
  "fighterRelocateRange": 3,
  "warshipCapacity": 2,
  "dieSides": 6,
  "startingTreasury": { "axis": 0, "allies": 0 },
  "startingForces": {
    "germany": [ { "type": "INFANTRY", "count": 4 }, { "type": "ARTILLERY", "count": 1 }, { "type": "TANK", "count": 2 }, { "type": "FIGHTER", "count": 1 } ],
    "baltic":  [ { "type": "WARSHIP", "count": 1, "owner": "axis" } ]
  },
  "variants": {
    "classic":            { "displayName": "Classic",            "turnCap": 12, "buildableUnits": ["INFANTRY", "ARTILLERY", "TANK", "FIGHTER", "WARSHIP"], "unitStats": {}, "incomeOverrides": {} },
    "tanks-and-soldiers": { "displayName": "Tanks and Soldiers", "turnCap": 12, "buildableUnits": ["INFANTRY", "TANK"],                                 "unitStats": {}, "incomeOverrides": {} },
    "navy-and-air":       { "displayName": "Navy and Air",       "turnCap": 12, "buildableUnits": ["WARSHIP", "FIGHTER"],                               "unitStats": {}, "incomeOverrides": {} },
    "army-battle":        { "displayName": "Army Battle",        "turnCap": 12, "buildableUnits": ["INFANTRY"],                                          "unitStats": {}, "incomeOverrides": {} }
  }
}
```

- `startingForces` lists every entry from spec §8. Land entries take their owner from the region's `startOwner`; sea entries must carry `"owner"`.
- `unitStats` is `{ "<UNIT>": { "cost"?, "attack"?, "defense"?, "move"? } }` overrides; `incomeOverrides` is `{ "<regionId>": <int> }`. These are the spec §11.5 levers.
- `GameDefinition.configJson(variant)` returns the JSON of the base object with `variants` removed and the chosen variant's fields merged at top level plus `"variant": "<id>"`. That string is what P0.3 freezes on the game doc; the engine only ever sees this merged form (`GameConfig`). `variantDisplayName(variant)` returns `displayName`. Turn deadline is 72 h on the TS definition (`turnDeadlineHours`), not per variant.

---

## 3. Engine contract mapping

| Platform call | Quick WW2 implementation |
|---|---|
| `initialStateJson(configJson, players, seed)` | `sides = { axis: players[playerIndex 0].uid, allies: players[playerIndex 1].uid }` (matchmaking already randomises order); owners from `startOwner`; units from `startingForces`; treasuries from `startingTreasury`; `gameRound 1`, `activeSide "axis"`. `seed` unused. |
| `activePlayer(stateJson, configJson)` | `state.sides[state.activeSide]`. |
| `validate(stateJson, submissionJson, playerId, configJson)` | Decode (malformed → `["malformedSubmission"]`); `playerId` must equal `sides[activeSide]` (`wrongSide`); then `validateTurn` (§5). Returns every error found, in the order of §5.9, deduplicated. |
| `apply(stateJson, submissionJson, playerId, seed, configJson)` | `applyTurn` (§5); `EngineResult(stateJson, logJson, status = complete if winner != null else active, winnerUid = sides[winner])`. `logJson` is never null for this game (§4.3). |

Kotlin `QuickWw2Engine` exposes the typed API from spec §6 (`initialState`, `validateTurn`, `applyTurn`, `checkVictory`) and the `GameEngine` adapter wraps it with kotlinx.serialization (`Json { encodeDefaults = true; ignoreUnknownKeys = true }`, same as tic-tac-toe). The TS twin mirrors the typed functions one-to-one so vectors exercise identical code paths.

---

## 4. JSON shapes (the boundary that vectors and the client see)

### 4.1 State

```json
{
  "sides": { "axis": "uid-a", "allies": "uid-b" },
  "gameRound": 1,
  "activeSide": "axis",
  "regions": {
    "germany":   { "owner": "axis",  "units": [ { "type": "INFANTRY", "owner": "axis", "count": 4 } ] },
    "north-sea": { "owner": null,    "units": [ { "type": "WARSHIP", "owner": "allies", "count": 1 } ] }
  },
  "treasuries": { "axis": 0, "allies": 0 },
  "winner": null,
  "winReason": null
}
```

- `regions` has an entry for every region in map order; `units` is sorted by (`owner` axis-first, then `rollOrder`), zero-count stacks removed. Sorting is part of the contract so state JSON is canonical for vector comparison.
- Sea zones may hold `WARSHIP` stacks and land-unit stacks (cargo) of one side only (§5.4 guarantees this). Fighters are never in sea zones.
- `winner`: `"axis" | "allies" | null`; `winReason`: `"capitalCaptured" | "economicAtTurnCap" | null`. Forfeits and timeouts are platform status and never appear here.
- Dropped from the spec's `GameState`: `gameId`, `round` (renamed `gameRound`), `pendingPlacements`, `status`.

### 4.2 Submission

```json
{
  "side": "axis",
  "gameRound": 1,
  "purchases": { "INFANTRY": 2, "TANK": 1 },
  "combatMoves":    [ { "unitType": "TANK", "count": 1, "from": "eastern-europe", "to": "ukraine", "path": ["ukraine"], "cargo": [] } ],
  "noncombatMoves": [ { "unitType": "INFANTRY", "count": 2, "from": "germany", "to": "eastern-europe", "path": ["eastern-europe"], "cargo": [] } ],
  "placements":     [ { "unitType": "INFANTRY", "regionId": "germany" }, { "unitType": "INFANTRY", "regionId": "germany" }, { "unitType": "TANK", "regionId": "germany" } ]
}
```

- `side` and `gameRound` are client intent guards: they must equal the state's `activeSide` and `gameRound` (`wrongSide`, `wrongRound`).
- `path` lists the regions entered after `from`, ending with `to`. Fighter moves may pass `path: []`; the engine computes reachability itself (§5.3).
- `cargo` is only meaningful on `WARSHIP` moves: the land unit types travelling with the moving warships (one entry per unit, e.g. `["INFANTRY", "INFANTRY"]`).
- All four sections may be empty; an entirely empty turn is legal and is how the e2e pass-bot plays.

### 4.3 Log (one per applied submission, never null)

```json
{
  "side": "axis", "gameRound": 1,
  "submission": { ...the applied submission verbatim... },
  "battles": [
    { "regionId": "ukraine", "attacker": "axis", "seed": 3141592653, "amphibious": false, "cancelled": false,
      "rounds": [
        { "attackerRolls": [2, 5, 6], "defenderRolls": [1, 4], "attackerHits": 1, "defenderHits": 1,
          "attackerLosses": { "INFANTRY": 1 }, "defenderLosses": { "INFANTRY": 1 } }
      ],
      "attackerLosses": { "INFANTRY": 1 }, "defenderLosses": { "INFANTRY": 3, "TANK": 1 },
      "conqueror": "axis" }
  ],
  "cargoLost": { "north-sea": { "INFANTRY": 2 } },
  "incomeCollected": 20,
  "winner": null, "winReason": null
}
```

Battles appear in resolution order (§5.5). `cancelled: true` marks a staged amphibious land battle that never happened because its sea battle was lost; it has `rounds: []`. Client replay steps are the flattened `rounds` across `battles`.

---

## 5. Rules clarifications (each clause below is a named test and a vector)

Numbers in brackets are the vector names to use, so coverage is checkable.

### 5.1 Phase order and staging semantics

`applyTurn` processes: purchase → combat moves → combat resolution → noncombat moves → placement → income → victory/turn pass. `validateTurn` validates the same sequence against a **staging copy** of the state: every move in `combatMoves` is applied to the staging copy in list order and must be legal at its point in the list; the same for `noncombatMoves` after combat resolution is *assumed* (see 5.2). Moved units are tracked in the staging copy as `movedCounts[(regionId, unitType, owner)]`; a unit that has moved cannot be moved again this turn (`unitMovedTwice`). Units that took part in a combat move are marked moved and are excluded from noncombat moves. [staging-order-dependent-move, unit-moved-twice-land, unit-moved-twice-fighter]

### 5.2 Validation cannot know combat outcomes

Noncombat moves are validated against the post-combat-move staging state **before** combat resolves, with these rules: a noncombat move may not originate from a region that is a combat destination this turn (its units are fighting), and may not enter a region that is a combat destination (`invalidNoncombatDestination`). At apply time, after resolution, every noncombat move is re-checked; if a destination is no longer friendly (cannot happen under the rule above but is guarded) the whole submission is rejected with `postCombatMoveIllegal`. [noncombat-into-contested-rejected, noncombat-from-contested-rejected]

### 5.3 Movement

- **Land units** (INFANTRY, ARTILLERY, TANK): `path.size ≤ move`; each step goes to a region in the current region's `adjacentLand`; intermediate regions must be owned by the mover and contain no enemy units (`enemyPassThrough`, `illegalPath`). Combat move: `to` is enemy-owned or contains enemy units (`invalidCombatDestination`). Noncombat move: `to` is owned by the mover and contains no enemy units; a territory captured this turn counts as owned. [infantry-one-step, tank-through-friendly-attack, tank-through-enemy-rejected, tank-three-steps-rejected, land-into-sea-without-warship-rejected]
- **Loading** (noncombat only): a land unit moves from a land region into a sea zone in its `adjacentSea` that holds ≥ 1 friendly warship; `path = [zone]`; consumes the unit's whole move. Capacity: after all noncombat moves, for every sea zone and side, land units ≤ `warshipCapacity × warships` (`transportOverCapacity`, `noTransportInZone`). No port is required to load or unload. [load-two-infantry, load-over-capacity-rejected, load-no-warship-rejected]
- **Unloading**: noncombat, from a sea zone to a friendly land region in the zone's `adjacentLand`; combat (amphibious), to an enemy land region in the zone's `adjacentLand`. `path = [land]`. [unload-noncombat, amphibious-basic]
- **Warships**: `path.size ≤ 2`, each step in `adjacentSea`; may not pass **through** a zone holding enemy warships (`enemyPassThrough`); a combat move may **end** in one (that is what starts a sea battle); a noncombat move may not (`invalidNoncombatDestination`). A warship move with `cargo` moves that many land units of the listed types along with it; the cargo must exist in `from` and capacity must hold in `to`. Cargo left behind when the last friendly warship leaves a zone is illegal (`transportOverCapacity` at end of phase). [warship-two-steps, warship-through-enemy-rejected, warship-with-cargo, warship-abandons-cargo-rejected]
- **Fighters**: combat move to any region (land or sea) whose BFS distance from `from` over the combined adjacency graph, ignoring ownership, is ≤ `fighterAttackRange` (`fighterOutOfRange`); the target must contain enemy units or be enemy-owned land. After resolution every surviving attacking fighter returns to `from`. Noncombat: relocate to a friendly land region within `fighterRelocateRange` BFS steps (`fighterInvalidDestination`). Fighters never end a turn in a sea zone. [fighter-range-2-ok, fighter-range-3-rejected, fighter-attacks-sea-zone, fighter-returns-home, fighter-relocate-3]

### 5.4 Battles: participants

- A **land battle** occurs in every land region entered by a combat move. Attackers: all units moved in this turn (land units, offloaded cargo, fighters). Defenders: all units of the other side present.
- A **sea battle** occurs in every sea zone entered by a warship combat move that holds enemy warships, and in every sea zone attacked by fighters. Only WARSHIP and FIGHTER roll; cargo never rolls and is never a casualty choice.
- If all warships of a side in a zone are gone after a sea battle, that side's cargo in the zone dies (`cargoLost` in the log) and every amphibious land battle staged from that zone by that side is cancelled. If both sides lose all warships in the same round, both cargos die. [amphibious-cancelled-on-sea-loss, sea-both-annihilated-both-cargo-die, sea-win-then-amphibious]
- Because placement into a zone with enemy warships is illegal and a warship entering such a zone always fights, a zone never holds warships of both sides after resolution. Assert this as an invariant in the fuzz test.

### 5.5 Battles: resolution order and seeds

- Resolve all sea battles in map order, then all land battles in map order. `turnRng = SeededRng(seed)`; for each battle in that order `battleSeed = turnRng.nextUInt()`, `battleRng = SeededRng(battleSeed)`; the log records `battleSeed`. A cancelled battle consumes no seed. [battle-order-sea-before-land, per-battle-seed-independence]
- Each round: attackers roll one die per unit in `rollOrder` (boosted infantry first among infantry); defenders likewise; `roll = rng.nextInt(dieSides) + 1`, attackers first then defenders. A unit hits when `roll ≤ value`. Attack/defense values come from `units` with `unitStats` overrides; attacking infantry count as boosted up to `min(artillery, infantry)` among surviving attackers, recomputed every round. Hits are applied after both sides roll; each side removes hits in `casualtyOrder`; excess hits are wasted. Repeat while both sides have rolling units. [artillery-boost-recomputed, casualty-order, simultaneous-annihilation-land, overkill-hits-wasted]
- A land battle is won by the attacker if any attacking **land** unit survives; then ownership flips and survivors (including offloaded cargo) stay. If only fighters survive, ownership does not flip. Defenders keep the territory when all attackers are dead. No retreats. [only-fighters-survive-no-flip]
- Capturing a capital ends the game immediately: `winner`, `winReason = "capitalCaptured"`, remaining phases (noncombat, placement, income) are skipped and the log still contains the battles. [capital-capture-ends-turn-early]

### 5.6 Purchases and placement

- `purchases` keys must be `buildableUnits` (`unbuildableUnit`); total cost ≤ treasury (`overspend`); negative counts are `malformedSubmission`.
- `placements` must be exactly the multiset of purchases (`placementsMismatchPurchases`). Land and air units go to the mover's capital only (`placementNotCapital`). Warships go to a sea zone in the `adjacentSea` of a port land region owned by the mover (`placementNotPortAdjacent`) that holds no enemy warships after combat (`placementIntoEnemyZone`). A capital captured this turn is not a placement target (you place at your own capital, which you always own during your own turn). [purchase-and-place, overspend-rejected, unbuildable-in-mode-rejected, warship-placement-port, warship-placement-enemy-zone-rejected, placement-mismatch-rejected]

### 5.7 Income, turn pass, turn cap

- Income = Σ over land regions owned by the mover of `incomeOverrides[id] ?: income`; added to the treasury after placement. Logged as `incomeCollected`. [income-with-override]
- Turn pass: `axis → allies` in the same `gameRound`; `allies → axis` with `gameRound + 1`.
- Turn cap check happens once, after the **Allies** turn of `gameRound == turnCap` has collected income: higher income (as defined above, computed for both sides) wins; tie → higher unit value (Σ `cost` with overrides × count over all units on the board, cargo included); tie → Allies. `winReason = "economicAtTurnCap"`. [turn-cap-income-win, turn-cap-tie-unit-value, turn-cap-tie-allies, turn-cap-not-checked-after-axis]

### 5.8 Determinism

`applyTurn` is a pure function of `(state, submission, seed, config, map)`. Test: play 200 random legal turns (using a random legal-turn generator built on `TurnBuilder`) twice from the same seed list and compare serialized states byte-for-byte after every turn.

### 5.9 Error codes (stable strings, in reporting order)

`malformedSubmission, gameComplete, wrongSide, wrongRound, unknownUnitType, unknownRegion, unbuildableUnit, overspend, notEnoughUnits, illegalPath, enemyPassThrough, invalidCombatDestination, invalidNoncombatDestination, noTransportInZone, transportOverCapacity, fighterOutOfRange, fighterInvalidDestination, unitMovedTwice, placementNotCapital, placementNotPortAdjacent, placementIntoEnemyZone, placementsMismatchPurchases, postCombatMoveIllegal`. The client string table maps each to a sentence.

---

## 6. Test vectors

`QuickWw2VectorTest` follows `TicTacToeVectorTest` exactly (build from code, write when absent or `-Dvectors.regenerate=true`, assert equal). The file has:

- `game: "quickWw2"`, `engineVersion` from the definition, `assetHashes` for both assets (P0.7).
- `initialStates`: one per variant.
- `vectors`: one per bracketed name in §5 (≥ 60 named clauses), plus a scripted 6-round CLASSIC game (`scripted-classic-<n>` for every turn) that ends in capital capture, plus one full game per other mode ending at the turn cap. States for later turns are the previous vector's expected output, so the exporter chains them.

`functions/test/vectors.test.ts` is generalized to load every `test-vectors/*.json` whose `game` is in the registry (rng vectors have their own test). The TS test also asserts `assetHashes`.

---

## 7. Client

### 7.1 `TurnBuilder` (pure Kotlin, commonMain, unit-tested)

Holds `state`, `config`, `map`, `mySide`, and a mutable `TurnSubmission`. Operations: `setPurchase(type, count)`, `addCombatMove(move)`, `addNoncombatMove(move)`, `removeMove(index)`, `setPlacements(list)`, `autoPlace()` (land/air to capital; warships to the first legal port-adjacent zone). Each mutation is applied to a copy and validated with `QuickWw2Engine.validateTurn`; if invalid, the mutation is refused and the error codes are returned to the caller. `stagedState()` returns the staging state for rendering (units at their staged positions, arrows derived from moves). `legalCombatDestinations(from, unitType)` and `legalNoncombatDestinations(from, unitType)` enumerate targets by trying each region through `validateTurn`, so the UI never re-implements rules.

### 7.2 Screens (`QuickWw2Ui : GameUi`)

Rendering uses `PixelPalette` and the existing typography; no new theme work. Board layout comes from `x`/`y` in `map.json` on a 1000×700 canvas scaled uniformly to the available width.

1. **Map screen** — `Canvas`: sea zones as rounded rectangles in `PixelPalette.accent` at low alpha, land as rectangles filled by owner (axis `danger`, allies `primary`, neutral `surface`), adjacency drawn as thin lines, capital marked with a star glyph, unit stacks as `"4i 1a 2t 1f"` labels (i/a/t/f/w), income badge. Tap a region → detail panel (units by owner, income, port). Tap-tap: select a friendly region, pick unit type + count in the panel, tap a highlighted destination (from `TurnBuilder.legal*Destinations`); a phase toggle chooses combat vs noncombat staging. Staged moves draw arrows; regions with staged combat get a badge. Only rendered as interactive when `game.isMyTurn(myUid)` and no replay is pending.
2. **Purchase sheet** — rows only for `config.buildableUnits`, cost, +/- stepper, treasury remaining; rejects overspend via `TurnBuilder`.
3. **Turn review** — lists purchases, combat moves, noncombat moves, placements (with `autoPlace()` applied and editable for warships); single **Submit** calls `onSubmit(json)`; the builder state is kept in `remember(game.id, game.round)` so a rejection restores it.
4. **Combat replay** — when `logs` gains an id not yet replayed this session and its `side` differs from the side last replayed or the log is newer than the last seen, run `rememberReplayState(stepCount = total rounds, stepMillis = 2000)` over the flattened rounds: per step show region name, both dice lists, hits, casualties; a **Skip** button calls `skipToEnd()`. When `finished`, show a summary card per battle (conqueror or repelled, cancelled amphibious, cargo lost). Own turns replay too (the player has not seen the dice). Only after the replay finishes does the map become interactive.
5. **End screen** — `game.status` complete/forfeited: winner side, `winReason` or forfeit, final map (non-interactive), back to home.

Rejections arrive through the shell banner (P0.4); the game screen additionally maps codes to sentences and, when the code names a move index, highlights it in the review list.

---

## 8. Unit tests (D1 gate)

`RulesTest`: every movement clause in §5.3 (positive and negative). `CombatTest`: §5.4–5.5 with fixed seeds; a test that a battle's outcome depends only on its own `battleSeed`. `EngineTest`: purchases/placement/income/turn pass/turn cap/capital capture, every error code at least once, mode restrictions per variant. `DeterminismTest`: §5.8. `TurnBuilderTest`: every illegal move class is refused with the same code the engine reports; `autoPlace` produces a legal submission. `AssetsTest`: map validation passes on the shipped asset and fails on each corrupted fixture (missing symmetry, unknown id, two capitals). Fuzz: 300 random games through `TurnBuilder`'s random-turn generator asserting after every turn: unit counts ≥ 0, no zone with both sides' warships, cargo ≤ capacity, treasuries ≥ 0, exactly one active side, game ends by `turnCap`.

---

## 9. Balance harness (D3)

- Lives in `androidUnitTest/balance/`, runs only with `-Pbalance=true` (plumbed like `vectors.regenerate`); `-PbalanceGames=N` (default 500) and `-PbalanceModes=classic,...` (default all).
- Bots implement `fun turn(state, config, map, side, rng): TurnSubmission` using only `TurnBuilder` and public engine functions. Shared strength heuristic: `attackStrength = Σ attack` vs `defenseStrength = Σ defense` of the units that would meet; a bot attacks when the ratio ≥ its threshold. Thresholds and purchase weights live in a `BotConfig` per bot so bot tuning is visibly separate from game tuning.
  - **LandRush**: buy TANK else INFANTRY; each turn attack the next region on the shortest land path to the enemy capital with everything in range when ratio ≥ 1.2; noncombat: move rear units one step toward the front.
  - **Turtle**: buy INFANTRY (+1 ARTILLERY per 3 infantry when buildable); attack only at ratio ≥ 2.0; hold border regions; stack the capital.
  - **SeaRaider**: buy WARSHIP then FIGHTER; load infantry from port territories; amphibious strike on united-kingdom, scandinavia, north-africa when defenders ≤ 2 units; fighters attack weak adjacent regions.
  - **Balanced**: alternate LandRush and Turtle purchase policies by round parity; attack at ratio ≥ 1.5.
  - When a bot's preferred unit is unbuildable in the mode, fall back down its own list, then to any buildable unit, then bank.
- Runner: for each mode, every ordered bot pairing (16 including mirror-self), N games each, seeds `0 until N`; collects axis win rate, win reason distribution, median end round, per-pairing win matrix. Writes `build/reports/quickww2-balance-<mode>.md` and prints a summary.
- Asserts (only when gated): per mode, axis win rate across all games in [0.45, 0.55]; no bot with win rate ≥ 0.5 against every other bot from both sides; turn-cap endings < 20% of games. For MVP the assertions are hard for `classic` and soft (report, no fail) for the other modes.
- Tuning loop: change only `config.json` values (`unitStats`, `incomeOverrides`, `turnCap`, `startingForces`), regenerate vectors, rerun. Record every change and the before/after numbers in §12 of this file. Rule changes are flagged back to Greg, never made.

---

## 10. End-to-end (D2/D5)

- `e2e/run.mjs` gains a `quickWw2` section after the tic-tac-toe checks: both users queue into `quickWw2_classic`; assert initial state (`activeSide axis`, treasuries 0, germany has 4 INFANTRY, `configJson` present on the doc, `variant classic`); Axis submits an empty turn → `logs/0-<uid>` exists with `battles: []`, `incomeCollected 20`, `activePlayerId` flips, `round 1`; Allies submits an overspend turn → `logs/1-<uid>-rejected` with `overspend`, submission deleted, `round` unchanged.
- Scripted game: a fresh game is created, and the e2e uses the admin SDK (already used for the forfeit check) to set the game doc's `seed` to the base seed the exporter used (`7`). Quick WW2's initial state does not depend on the seed, so overriding it after creation is safe. Because the emulators run with `QUICKGAMES_DETERMINISTIC_SEEDS=1`, the server derives each turn's seed as `seedForRound(7, round)`, which is exactly how `QuickWw2VectorTest` derives the seeds of the `scripted-classic-<n>` vectors. The e2e then submits each scripted submission in order and compares the resulting `stateJson` and `logJson` to the vector's expected values. `seedForRound` is ported to Kotlin in `core:engine` (32-bit semantics: `(base | 0) ^ ((round * 0x9e3779b9) | 0)` then unsigned) and covered by an rng vector. Finally assert `status complete`, `winner`, and that `sweepNow` still forfeits a stalled Quick WW2 game.
- `e2e/opponent.mjs` takes `--game quickww2 --variant classic` and plays empty turns (pass-bot). This is the opponent for the Android/iOS UI sessions in D4/D5.
- Root `package.json` `e2e` script sets the env var: `QUICKGAMES_DETERMINISTIC_SEEDS=1 firebase emulators:exec ...`.

---

## 11. Sub-phase checklist

- [ ] D1.1 `shared-assets/quickww2/{map,config}.json`; `generateSharedAssets` wired; `AssetsTest`
- [ ] D1.2 `Model.kt`, `Rules.kt`, `Combat.kt`, `Engine.kt` with the tests of §8
- [ ] D1.3 `Definition.kt` adapter; `QuickWw2VectorTest` exporting `test-vectors/quickww2.json` with every §5 name
- [ ] D1.4 `DeterminismTest`, fuzz
- [ ] D2.1 TS twin files; generalized vector test; asset hash check; `registry.ts`
- [ ] D2.2 e2e section and pass-bot; `seedForRound` in `core:engine` + rng vector
- [ ] D3.1 Bots, harness, gating, reports; classic passes; other modes recorded in §12
- [ ] D4.1 `TurnBuilder` + tests
- [ ] D4.2 Screens 1–5; replay; rejection mapping; variant names in the picker
- [ ] D4.3 Android emulator full game vs pass-bot; screenshots of each screen
- [ ] D5.1 iOS simulator ↔ Android game on emulators
- [ ] D5.2 Both apps against the real project; three games (capture, turn cap, forfeit)
- [ ] D5.3 Tic-tac-toe vectors and e2e unchanged

---

## 12. Defaults chosen and balance record

Defaults (proceed unless overridden):
1. Loading and unloading need no port; ports only gate warship placement.
2. Noncombat moves may enter territories captured this turn.
3. Fighters may attack sea zones; a sea zone cleared by fighters alone has no owner and nothing else changes.
4. Cargo never rolls and is never chosen as a casualty in sea battles.
5. Turn cap is checked only after the Allies turn of round `turnCap`.
6. Unit value tiebreak counts every unit on the board at its (overridden) cost, cargo included.
7. Replay is shown for the player's own turn as well as the opponent's.
8. Schematic node-map board at MVP; illustrated map later.
9. A single 72 h deadline for all modes.

Balance record (fill in during D3; one row per config change):

| Date | Mode | Change | Axis win rate before → after | Turn-cap % before → after | Dominant bot before → after |
|---|---|---|---|---|---|
