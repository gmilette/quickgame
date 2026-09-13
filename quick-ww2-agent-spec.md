# Quick WW2 — Agent Implementation Spec

Condensed Axis & Allies-style strategy game for the existing KMP + Firebase multiplayer platform. 1v1 asynchronous, cross-platform (Android/iOS). One player is Axis, the other Allies. Turns alternate: the active player submits their whole turn, resolved immediately on submit, then the opponent goes. Perfect information — the opponent's full state is always visible.

Reuse from platform: Firebase Auth, matchmaking, GitLive SDK setup, Cloud Functions deployment structure, Compose Multiplatform UI shell. All game rules live in the shared engine module; Cloud Functions call the same engine compiled to run server-side validation (TypeScript reimplementation of validation/resolution with shared test vectors, per the platform plan).

Target game length: 8–12 rounds, played async over days. All tuning values live in `GameConfig` — nothing hardcoded.

---

## 1. Game overview

- Two sides: **Axis** (moves first each round) and **Allies**.
- Map: fixed hand-authored board, 12 land territories + 4 sea zones (defined in §2). Shipped as a static JSON asset; no map generator.
- Each territory produces income (IPCs). Spend income on units, move armies, resolve dice combat, capture territory.
- **Win conditions**, checked after every turn:
  1. Capture the enemy capital → immediate win.
  2. Turn cap reached (default 12 rounds): higher current income wins; tie → higher total unit value on board; tie → Allies win (compensation for moving second).
  3. Resignation or turn timeout (default 72h per turn) → forfeit.

## 1a. Game modes

Four modes at launch, selected at matchmaking (players are only matched within the same mode). All modes share identical rules and map; they differ only in which units can be purchased. Starting forces are always unchanged (§8) — unbuildable unit types still exist at setup but cannot be replaced, making them finite attrition assets.

- **CLASSIC** — all five unit types buildable.
- **TANKS_AND_SOLDIERS** — buildable: INFANTRY, TANK. Simplified entry mode: every purchase decision is guns vs. speed.
- **NAVY_AND_AIR** — buildable: WARSHIP, FIGHTER. Land armies are irreplaceable, so ground forces must be spent carefully while navies and air power contest the map; games lean on income control and the turn-cap economic victory.
- **ARMY_BATTLE** — buildable: INFANTRY. Pure attrition infantry war; starting tanks, artillery, fighters, and warships are the only specialists either side will ever have.

A mode is purely a `GameConfig` preset (`buildableUnits` plus any overrides); the engine has no mode-specific branches. New modes must be addable by config alone.

## 2. The map

Region ids are stable strings used in state, moves, and the map asset.

### Land territories

| id | Name | Income | Start owner | Port | Land adjacency | Sea adjacency |
|---|---|---|---|---|---|---|
| `germany` | Germany (Axis capital) | 6 | Axis | yes | western-europe, eastern-europe, southern-europe | baltic, north-sea |
| `western-europe` | Western Europe | 4 | Axis | yes | germany, southern-europe | north-sea, atlantic |
| `southern-europe` | Southern Europe | 3 | Axis | no | germany, western-europe, balkans | mediterranean |
| `eastern-europe` | Eastern Europe | 3 | Axis | no | germany, balkans, ukraine | baltic |
| `balkans` | Balkans | 2 | Axis | no | southern-europe, eastern-europe, ukraine, middle-east | mediterranean |
| `north-africa` | North Africa | 2 | Axis | no | middle-east | mediterranean, atlantic |
| `russia` | Russia (Allied capital) | 6 | Allies | yes | ukraine, caucasus, scandinavia | baltic |
| `united-kingdom` | United Kingdom | 5 | Allies | yes | (none — island) | north-sea, atlantic |
| `ukraine` | Ukraine | 3 | Allies | no | eastern-europe, balkans, russia, caucasus | (none) |
| `caucasus` | Caucasus | 2 | Allies | no | russia, ukraine, middle-east | (none) |
| `middle-east` | Middle East | 2 | Allies | no | caucasus, balkans, north-africa | mediterranean |
| `scandinavia` | Scandinavia | 2 | Allies | no | russia | north-sea, baltic |

Starting income: Axis 20, Allies 20.

### Sea zones

| id | Adjacent land | Adjacent sea |
|---|---|---|
| `north-sea` | united-kingdom, scandinavia, western-europe, germany | atlantic, baltic |
| `atlantic` | united-kingdom, western-europe, north-africa | north-sea, mediterranean |
| `baltic` | germany, scandinavia, eastern-europe, russia | north-sea |
| `mediterranean` | southern-europe, balkans, north-africa, middle-east | atlantic |

Sea zones have no owner and produce no income; control is by presence only. Adjacency is symmetric; the engine must validate the map asset for symmetry at load time.

## 3. Units

| Type | Cost | Attack | Defense | Move | Domain | Special |
|---|---|---|---|---|---|---|
| INFANTRY | 3 | 1 | 2 | 1 | land | Attack becomes 2 when paired with artillery (§5) |
| ARTILLERY | 4 | 2 | 2 | 1 | land | Boosts one infantry per artillery in the same attack |
| TANK | 5 | 3 | 2 | 2 | land | — |
| FIGHTER | 8 | 3 | 3 | 3 | air | Based in land territories; returns to launch territory after combat (§4.3) |
| WARSHIP | 7 | 3 | 3 | 2 | sea | Carries up to 2 land units (§4.4) |

Dice: d6. A unit hits when its roll ≤ its attack (attacking) or defense (defending). All stats are `GameConfig` values.

## 4. Turn structure

Each player turn is submitted as a single atomic `TurnSubmission` and processed in this fixed order. Axis takes the first turn of each round; a round is one Axis turn followed by one Allies turn.

### 4.1 Purchase
Spend banked treasury on units. Bought units are held aside and placed in phase 4.5. Overspending is a validation failure.

### 4.2 Combat moves
Moves that enter a region containing enemy units or an enemy-owned land territory.

- Land units move up to their move value through friendly/own territories; the final step may enter an enemy region. Tanks may pass through one friendly territory then attack (2 moves total). No moving through enemy territories.
- Multiple origins may attack the same region (one combined battle per region).
- Sea combat: warships moving into a sea zone containing enemy warships initiates sea combat. Sea zones with enemy presence block movement through (not into).
- Amphibious assault: land units aboard a warship may offload into an adjacent enemy land territory. If the sea zone contains enemy warships, sea combat resolves first; if all friendly warships in that zone die, their cargo dies and the land assault is cancelled. Surviving assaults resolve as normal land combat (attackers cannot retreat back to sea).
- Fighters may attack any region within 2 steps of a friendly land territory they occupy. After combat they automatically return to their launch territory.

### 4.3 Combat resolution (server-side, seeded)
For each contested region, resolve automatically with no player decisions (same principle as Risk-lite: no per-round choices):

1. Compute hit values. Combined arms: each attacking artillery upgrades one attacking infantry to attack 2 for the whole battle.
2. Each round: all attackers roll, all defenders roll simultaneously; casualties removed after both sides roll.
3. Casualty order (both sides, fixed): INFANTRY → ARTILLERY → TANK → FIGHTER → WARSHIP. Boosted infantry die before unboosted is NOT tracked — infantry are interchangeable; boost count is recomputed each round from surviving artillery.
4. Repeat until one side has no units. No retreats.
5. Attacker wins a land battle → territory ownership flips to attacker (requires at least one surviving land unit; if only fighters survive, territory does not flip and fighters return home). Defender keeps territory otherwise.
6. Capturing a capital ends the game immediately.

RNG: seeded server-side; the seed and per-round rolls are written into the `BattleLog` so clients replay the identical battle.

### 4.4 Noncombat moves
Remaining movement for units that did not fight: repositioning through friendly regions, loading/unloading warships (load or unload consumes the land unit's entire move), fighters relocating up to 3 between friendly land territories.

### 4.5 Placement
- Land units and fighters: capital only.
- Warships: any sea zone adjacent to a friendly port territory (see port column, §2). Placement into a sea zone containing enemy warships is illegal.
- No placement limit per territory.

### 4.6 Collect income
Treasury += sum of income of owned territories. Then victory/turn-cap check, then turn passes.

## 5. Kotlin data models (shared module)

```kotlin
enum class Side { AXIS, ALLIES }

enum class RegionType { LAND, SEA }

enum class UnitType(val cost: Int, val attack: Int, val defense: Int, val move: Int, val domain: Domain) {
    INFANTRY(3, 1, 2, 1, Domain.LAND),
    ARTILLERY(4, 2, 2, 1, Domain.LAND),
    TANK(5, 3, 2, 2, Domain.LAND),
    FIGHTER(8, 3, 3, 3, Domain.AIR),
    WARSHIP(7, 3, 3, 2, Domain.SEA)
}

enum class Domain { LAND, AIR, SEA }

data class RegionDef(
    val id: String,
    val name: String,
    val type: RegionType,
    val income: Int,
    val isPort: Boolean,
    val startOwner: Side?,
    val adjacentLand: List<String>,
    val adjacentSea: List<String>
)

data class MapDef(val regions: List<RegionDef>)

data class UnitStack(val type: UnitType, val owner: Side, val count: Int)

data class RegionState(val owner: Side?, val units: List<UnitStack>)

data class GameState(
    val gameId: String,
    val round: Int,
    val activeSide: Side,
    val regions: Map<String, RegionState>,
    val treasuries: Map<Side, Int>,
    val pendingPlacements: Map<Side, List<UnitType>>,
    val status: GameStatus,
    val winner: Side?
)

enum class GameStatus { ACTIVE, COMPLETE, FORFEITED }

data class Move(
    val unitType: UnitType,
    val count: Int,
    val from: String,
    val to: String,
    val path: List<String>,
    val cargo: List<UnitType> = emptyList()
)

data class TurnSubmission(
    val gameId: String,
    val side: Side,
    val round: Int,
    val purchases: Map<UnitType, Int>,
    val combatMoves: List<Move>,
    val noncombatMoves: List<Move>,
    val placements: List<Placement>
)

data class Placement(val unitType: UnitType, val regionId: String)

data class BattleLog(
    val regionId: String,
    val seed: Long,
    val rounds: List<CombatRound>,
    val attackerLosses: Map<UnitType, Int>,
    val defenderLosses: Map<UnitType, Int>,
    val conqueror: Side?
)

data class CombatRound(
    val attackerRolls: List<Int>,
    val defenderRolls: List<Int>,
    val attackerHits: Int,
    val defenderHits: Int
)

data class UnitStatsOverride(
    val cost: Int? = null,
    val attack: Int? = null,
    val defense: Int? = null,
    val move: Int? = null
)

enum class GameMode { CLASSIC, TANKS_AND_SOLDIERS, NAVY_AND_AIR, ARMY_BATTLE }

data class GameConfig(
    val mode: GameMode = GameMode.CLASSIC,
    val turnCap: Int = 12,
    val turnTimeoutHours: Int = 72,
    val buildableUnits: Set<UnitType> = UnitType.entries.toSet(),
    val unitStats: Map<UnitType, UnitStatsOverride> = emptyMap(),
    val startingForces: Map<String, List<UnitStack>>,
    val incomeOverrides: Map<String, Int> = emptyMap()
) {
    companion object {
        fun forMode(mode: GameMode): GameConfig
    }
}
```

`forMode` returns the CLASSIC config with `buildableUnits` restricted per §1a: TANKS_AND_SOLDIERS → `{INFANTRY, TANK}`, NAVY_AND_AIR → `{WARSHIP, FIGHTER}`, ARMY_BATTLE → `{INFANTRY}`.

## 6. Engine API (shared module, pure functions, deterministic)

```kotlin
interface QuickWw2Engine {
    fun initialState(gameId: String, config: GameConfig, map: MapDef): GameState
    fun validateTurn(state: GameState, submission: TurnSubmission, config: GameConfig, map: MapDef): ValidationResult
    fun applyTurn(state: GameState, submission: TurnSubmission, seed: Long, config: GameConfig, map: MapDef): TurnResult
    fun checkVictory(state: GameState, config: GameConfig): VictoryResult?
}

sealed class ValidationResult {
    object Valid : ValidationResult()
    data class Invalid(val errors: List<String>) : ValidationResult()
}

data class TurnResult(val newState: GameState, val battleLogs: List<BattleLog>)

data class VictoryResult(val winner: Side, val reason: VictoryReason)

enum class VictoryReason { CAPITAL_CAPTURED, ECONOMIC_AT_TURN_CAP, FORFEIT, TIMEOUT }
```

Requirements:
- `applyTurn(state, submission, seed, ...)` must be fully deterministic for a given seed. Same call on client and server yields identical state — clients replay rather than trust a diff.
- `validateTurn` checks: correct side and round, affordability, purchases restricted to `config.buildableUnits`, legal paths (movement range, domain, no enemy pass-through), transport capacity, fighter range and return legality, placement rules, no unit moved twice.
- Validation failures reject the entire submission; no partial application.

## 7. Firebase protocol (alternating turns)

Firestore layout:

```
games/{gameId}
  mode, config, mapVersion, players: {axis: uid, allies: uid},
  state (serialized GameState), status, turnDeadline
games/{gameId}/turns/{round}-{side}
  submission (TurnSubmission), submittedAt
games/{gameId}/battleLogs/{round}-{side}
  logs: List<BattleLog>
```

Flow:
1. Matchmaking (existing platform layer) pairs players who queued for the same mode, creates the game doc with `GameConfig.forMode(mode)`, assigns sides randomly, writes `initialState`.
2. Active player's client writes a turn submission doc. Firestore rules: only the active player may write, only to the current `{round}-{side}` path, state doc is server-writable only.
3. Cloud Function trigger validates via the engine; on success generates a seed, applies the turn, writes new state + battle logs + next `turnDeadline`; on failure marks the submission rejected with errors (client surfaces them; illegal submissions should be prevented client-side, server is authoritative).
4. Opponent's client listens on the game doc; on state change it replays new battle logs, then it's their turn.
5. Scheduled function forfeits games past `turnDeadline`.

Uses the standard platform submission pipeline (see the platform implementation plan §4): only the active player may submit, and resolution fires immediately. Collection naming follows the platform layout; the `turns`/`battleLogs` names above map to `submissions`/`logs`.

## 8. Starting forces (initial values — Phase 2 tunes these)

Axis: germany 4 INFANTRY, 1 ARTILLERY, 2 TANK, 1 FIGHTER · western-europe 2 INFANTRY, 1 TANK · southern-europe 2 INFANTRY, 1 ARTILLERY · eastern-europe 3 INFANTRY, 1 TANK · balkans 1 INFANTRY · north-africa 2 INFANTRY, 1 TANK · baltic 1 WARSHIP · mediterranean 1 WARSHIP

Allies: russia 4 INFANTRY, 1 ARTILLERY, 2 TANK, 1 FIGHTER · ukraine 3 INFANTRY, 1 TANK · caucasus 2 INFANTRY · united-kingdom 3 INFANTRY, 1 ARTILLERY, 1 TANK, 1 FIGHTER · middle-east 2 INFANTRY · scandinavia 1 INFANTRY · north-sea 1 WARSHIP · atlantic 1 WARSHIP

Both sides start with treasury 0 and collect at the end of their first turn.

## 9. UI requirements (Compose Multiplatform)

Retro pixel aesthetic consistent with the mini RPG (Dragon Warrior NES style adapted to a strategic map).

Screens:
1. **Map screen** — board rendered from the map JSON; tap region for unit/income detail panel; drag or tap-tap to stage moves with arrow overlays; badge on regions with staged combat.
2. **Purchase sheet** — bottom sheet listing units, costs, treasury remaining; staged purchases shown in the placement preview. Only `buildableUnits` are listed (in TANKS_AND_SOLDIERS, the sheet shows just infantry and tanks — no disabled rows for the others).
3. **Turn review** — summary of purchases, combat moves, noncombat moves, placements; single Submit; nothing is sent until Submit.
4. **Combat replay** — when a turn resolves (own or opponent's), replay each battle from `BattleLog`: per-round dice results and casualties, skippable, ~2s per combat round.
5. **End screen** — winner, reason, final map.

Client-side validation mirrors `validateTurn` so illegal moves are unstageable; server remains authoritative.

## 10. Implementation phases & acceptance criteria

### Phase 1 — Engine
Shared module: models, map asset + loader (with adjacency symmetry validation), full turn pipeline, combat resolver, victory checks.

Accept when:
- Unit tests cover: every movement legality case, transport load/offload, amphibious cancellation on sea-combat loss, artillery boost recomputation per round, casualty order, capital capture, turn-cap tiebreakers, double-move rejection, overspend rejection.
- Determinism test: 1,000 random turns applied with fixed seeds produce identical states across two runs.
- Shared test vectors exported (JSON) for the TypeScript server implementation to consume.

### Phase 2 — Simulation harness & balance
Bot strategies: LandRush (tanks toward enemy capital), Turtle (defense + economy to turn cap), SeaRaider (warships + amphibious strikes on UK/Scandinavia/North Africa), Balanced. Round-robin, each pairing mirrored (both sides), ≥500 games per pairing. Run the full suite once per game mode (SeaRaider still runs in TANKS_AND_SOLDIERS using starting warships only).

Accept when (per mode):
- Axis overall win rate across mirrored pairings is 45–55%.
- No single strategy beats every other strategy from both sides (no dominant strategy).
- Median game ends by round 12 in <20% of games via turn cap (most games end by capital capture).
- Tuning happens only via `GameConfig` values and §8 starting forces; rule changes require flagging back to Greg.

### Phase 3 — Firebase integration
Cloud Function validation/resolution passing the Phase 1 test vectors, Firestore schema + security rules, turn deadline forfeits, wired into the standard platform submission pipeline.

Accept when: two emulator clients play a full scripted game end-to-end; illegal submissions rejected server-side; state identical between server result and client replay; timeout forfeit fires.

### Phase 4 — UI
All five screens on Android and iOS.

Accept when: a full game is playable between a physical Android device and an iOS simulator (or vice versa); battle replays match server logs; staged-move UI prevents every illegal move class from Phase 1 tests.

### Phase 5 — Polish
Resign flow, push notification on turn ready (platform layer), reconnection/resume mid-replay, spectator-safe rendering of finished games.

## 11. Open questions (defaults chosen — proceed unless overridden)

1. **Shore bombardment** (warships pre-strike in amphibious assaults): excluded from v1 to keep combat rules minimal. Revisit if SeaRaider underperforms in Phase 2.
2. **Turn cap 12** and 72h turn timeout: defaults, tune via `GameConfig`.
3. **Fighter attack range 2 / relocate 3** with auto-return: chosen to eliminate landing-crash rules. If fighters are too strong, reduce attack range to 1 before touching stats.
4. **Ties at turn cap go to Allies** as second-mover compensation. Replace with a tuned income handicap if Phase 2 shows first-mover advantage >55%.
5. **Restricted modes keep starting forces unchanged**; only purchases are limited. Known per-mode risks, each with a config-only tuning lever (no rule changes without flagging back to Greg):
   - TANKS_AND_SOLDIERS: once Axis warships are sunk, UK's 5 income is unreachable → Allied Turtle may dominate at turn cap. Lever: `incomeOverrides["united-kingdom"] = 3`.
   - NAVY_AND_AIR: territory only flips with a surviving land unit, so once starting armies deplete, capital capture becomes impossible and every game hits the turn cap. Acceptable if games stay decisive on income; if they stall, lever: `turnCap = 8` for this mode.
   - ARMY_BATTLE: infantry attack 1 vs defense 2 favors defenders → possible stalemates. Lever: `unitStats[INFANTRY] = UnitStatsOverride(attack = 2)` in this mode only.
