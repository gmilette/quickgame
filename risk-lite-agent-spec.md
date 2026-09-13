# Risk-Lite — Implementation Specification for Coding Agent

A multiplayer, turn-based territory conquest game for Android, written in Kotlin. This document is the authoritative spec. Where behavior is not specified here, choose the simplest option and flag it in the PR description rather than inventing rules.

---

## 1. Goals and Non-Goals

### Goals
- A complete, deterministic rules engine as a pure Kotlin module with no Android dependencies
- Fully data-driven maps: any territory count, any region count, loaded from JSON
- Fast, decisive gameplay: battles auto-resolve in one action, no retreat, games finish in 20–30 minutes
- Local pass-and-play first, then online multiplayer for 2–4 players
- A developer-only map generation tool for producing and approving maps into a rotation

### Non-Goals (do not build)
- Risk cards / card trading (replaced by the capture bonus)
- Retreat or per-round battle decisions
- Player-facing map creation or editing (map generation is a dev tool, never shipped in the APK)
- AI opponents (v1 is human-only; auto-skip handles absent players)
- iOS, alliances/diplomacy mechanics, chat, monetization
- Do not use the name "Risk", the classic Risk map, or any Hasbro artwork anywhere in the product

---

## 2. Complete Game Rules

These rules must be implemented exactly. All randomness comes from a seeded RNG stored in game state.

### 2.1 Players and Map
- 2–4 players.
- The game is played on a `MapDefinition` selected from the approved rotation. The engine must work for any valid map: N territories (12–40 supported), M regions (2–8 supported).

### 2.2 Setup
1. Shuffle all territories with the seeded RNG and deal them round-robin starting with player 0. With unequal division, earlier players receive one extra territory.
2. Each player's total starting armies = `2 × territoriesOwned`. Place 1 army on each owned territory, then distribute the remainder one at a time round-robin across the player's owned territories in seeded-RNG-shuffled order.
3. Capitals: each player's capital is auto-assigned to their owned territory with the greatest number of friendly adjacent territories; break ties by lowest territory id. The capital flag stays on the territory permanently even if captured.
4. Player 0 (lobby host is index 0; local play order = join order) takes the first turn. Turn order is ascending player index, skipping eliminated players.

### 2.3 Turn Structure
Each turn has three phases in fixed order: `REINFORCE → ATTACK → FORTIFY`. A player may end the ATTACK phase at any time and may skip FORTIFY.

### 2.4 Reinforcement
At the start of a player's turn:
```
reinforcements = 2
             + min(territoriesCapturedOnPlayersPreviousTurn, 3)
             + sum(region.bonus for each region fully owned by the player)
```
- "Captured on previous turn" means territories taken by battle during that player's own last turn, counted at that turn's end. First turn of the game: capture bonus is 0.
- The player places all reinforcements before attacking, split however they like across owned territories via one or more `PlaceArmies` actions. The phase advances to ATTACK only when `pendingReinforcements == 0` and the player submits `EndReinforcePhase` (or automatically once the last army is placed).

### 2.5 Attack (auto-resolved, no retreat)
- Legality: `from` is owned by the current player with `armies ≥ 2`; `to` is adjacent to `from` (per the map's adjacency) and owned by another player.
- On `Attack(from, to)` the engine resolves the entire battle immediately, with no player input mid-battle:

```
while (defenderArmies > 0 && attackerArmies > 1) {
    attackerDice = min(3, attackerArmies - 1)
    defenderDice = min(2, defenderArmies)
    roll attackerDice and defenderDice d6 with the seeded RNG
    sort both sets descending, compare pairwise for min(attackerDice, defenderDice) pairs
    for each pair: if attacker > defender, defenderArmies -= 1 else attackerArmies -= 1
}
```
- Ties on a die comparison always favor the defender.
- Outcome A — capture (`defenderArmies == 0`): ownership of `to` transfers to the attacker. The attacker must then submit `OccupyCaptured(from, to, moveCount)` with `1 ≤ moveCount ≤ from.armies - 1` before any other action. `to.armies = moveCount`. The UI defaults the slider to `from.armies - 1`. Increment the attacker's capture counter for this turn.
- Outcome B — repelled (`attackerArmies == 1`): `from` keeps 1 army, `to` keeps its survivors. No occupy step.
- Either way the engine emits a `BattleResult` event: attacker losses, defender losses, rounds, captured flag. Players never see individual dice.
- The player may declare unlimited attacks per turn while legal attacks exist.
- Eliminating a player: when a player's last territory is captured, they are eliminated and skipped in turn order.

### 2.6 Fortify
- At most one fortify per turn: `Fortify(from, to, armies)` where both territories are owned by the current player, connected by a path of the player's own territories, `armies ≤ from.armies - 1`, `armies ≥ 1`. Submitting `EndTurn` without fortifying is allowed.

### 2.7 Winning
Checked after every state change. A player wins immediately when either:
- they own every capital territory on the map (including their own), or
- all other players are eliminated.

Losing your own capital does not eliminate you; you may fight on and recapture it.

### 2.8 Battle Odds Hint
`game-core` must expose `fun captureProbability(attackerArmies: Int, defenderArmies: Int): Double` — the probability that `Attack` results in capture given the full auto-resolve loop above. Implement as an exact dynamic-programming table over states `(a, d)` using the standard per-round dice outcome probabilities, precomputed lazily up to 40×40 and cached. The UI shows this before the player confirms an attack, bucketed as: <35% "Long shot", 35–65% "Coin flip", >65% "Favored", >90% "Overwhelming".

---

## 3. Repository and Module Structure

Gradle multi-module, Kotlin 2.x, JDK 17.

```
risk-lite/
  game-core/        pure Kotlin (jvm), zero Android deps: rules engine, models, odds table
  mapgen/           pure Kotlin (jvm) CLI: developer map generation tool, depends on game-core
  app/              Android app, Jetpack Compose, depends on game-core
  maps/             approved MapDefinition JSON files + manifest.json (bundled into app assets)
```

Conventions:
- Kotlin only. No code comments anywhere.
- `kotlinx.serialization` for all JSON.
- All game randomness flows through a `DiceRoller` interface backed by `kotlin.random.Random(seed)`; no `Random.Default` anywhere in game-core.
- Tests: JUnit 5 in every module. game-core must have no `android.*` or `androidx.*` imports (enforce with a Konsist or lint check).
- Immutable data classes throughout game-core; the reducer never mutates.

---

## 4. game-core Contract

### 4.1 Models

```kotlin
@Serializable data class MapDefinition(
    val mapId: String,
    val name: String,
    val seed: Long,
    val territories: List<TerritoryDef>,
    val adjacency: Map<String, Set<String>>,
    val regions: List<RegionDef>
)

@Serializable data class TerritoryDef(
    val id: String,
    val name: String,
    val regionId: String,
    val shape: String,
    val labelX: Float,
    val labelY: Float
)

@Serializable data class RegionDef(val id: String, val name: String, val bonus: Int, val colorHex: String)

@Serializable data class Player(val id: String, val displayName: String, val colorHex: String, val eliminated: Boolean)

@Serializable data class TerritoryState(val owner: String, val armies: Int, val isCapital: Boolean)

@Serializable enum class TurnPhase { REINFORCE, ATTACK, OCCUPY, FORTIFY, GAME_OVER }

@Serializable data class GameState(
    val gameId: String,
    val mapId: String,
    val players: List<Player>,
    val territories: Map<String, TerritoryState>,
    val currentPlayerIndex: Int,
    val phase: TurnPhase,
    val pendingReinforcements: Int,
    val capturesThisTurn: Int,
    val capturesLastTurnByPlayer: Map<String, Int>,
    val pendingOccupy: PendingOccupy?,
    val fortifyUsed: Boolean,
    val rngState: Long,
    val turnNumber: Int,
    val winner: String?
)

@Serializable data class PendingOccupy(val from: String, val to: String)

sealed interface GameAction {
    val playerId: String
    data class PlaceArmies(override val playerId: String, val territory: String, val count: Int) : GameAction
    data class EndReinforcePhase(override val playerId: String) : GameAction
    data class Attack(override val playerId: String, val from: String, val to: String) : GameAction
    data class OccupyCaptured(override val playerId: String, val from: String, val to: String, val armies: Int) : GameAction
    data class EndAttackPhase(override val playerId: String) : GameAction
    data class Fortify(override val playerId: String, val from: String, val to: String, val armies: Int) : GameAction
    data class EndTurn(override val playerId: String) : GameAction
}

sealed interface GameEvent {
    data class BattleResult(val from: String, val to: String, val attackerLosses: Int, val defenderLosses: Int, val rounds: Int, val captured: Boolean) : GameEvent
    data class PlayerEliminated(val playerId: String, val byPlayerId: String) : GameEvent
    data class GameWon(val playerId: String, val byCapitals: Boolean) : GameEvent
}
```

### 4.2 Engine API

```kotlin
object Engine {
    fun newGame(map: MapDefinition, players: List<Player>, seed: Long, gameId: String): GameState
    fun reduce(map: MapDefinition, state: GameState, action: GameAction): ReduceResult
    fun legalActions(map: MapDefinition, state: GameState): List<GameAction>
    fun captureProbability(attackerArmies: Int, defenderArmies: Int): Double
}

sealed interface ReduceResult {
    data class Ok(val state: GameState, val events: List<GameEvent>) : ReduceResult
    data class Rejected(val reason: RejectionReason) : ReduceResult
}
```

Validation rules for `reduce` (reject, never throw): action from a non-current or eliminated player; wrong phase (Attack during REINFORCE, anything but OccupyCaptured during OCCUPY, etc.); illegal territory references; army counts out of bounds; second fortify; placing more than `pendingReinforcements`.

`rngState` must advance deterministically: identical `(map, state, action)` always produces an identical result. Serialize the RNG by storing the next seed after each consumption.

---

## 5. mapgen — Developer Map Generation Tool

CLI, never shipped in the APK. Invocation:

```
mapgen generate --seed 42 --territories 20 --regions 4 --out ./candidates/
mapgen validate ./candidates/map-42.json
mapgen approve ./candidates/map-42.json --maps-dir ../maps/
```

### Generation pipeline
1. Poisson-disc sample N points in a 1000×700 canvas (minimum spacing derived from N).
2. Compute the Voronoi diagram; apply 2 Lloyd relaxation iterations; each cell is a territory, its polygon serialized to the `shape` SVG path string, its centroid to `labelX/labelY`.
3. Adjacency = cells sharing a Voronoi edge of length > 8 units (avoids point-touch adjacencies).
4. Regions: pick M seed cells maximally spread apart, region-grow by repeatedly assigning the unassigned territory adjacent to the smallest region, until all assigned. Bonus per region = `max(1, round(territoryCount / 3 + externalBorderCount / 4))`.
5. Names: generate from a bundled word list (deterministic from seed).

### Validation (run automatically; a map failing any check is rejected before human review)
- Adjacency graph fully connected
- Every region contiguous
- Region sizes within ±50% of `territories / regions`
- No territory with more than 6 neighbors
- No single territory whose removal disconnects the graph (no cut vertices)
- Territory and region counts match the requested config

### Approval
- `generate` also emits `map-<seed>-preview.svg`: region-colored map with territory names, adjacency lines, and a stats block (region bonuses, border counts).
- `approve` copies the JSON into `maps/` and appends the mapId to `maps/manifest.json` (`{"version": n, "maps": ["..."]}`). The app only lists maps present in the manifest.
- Ship 3 approved maps of different sizes (≈14, ≈20, ≈28 territories) as the initial rotation.

---

## 6. Android App

Jetpack Compose, single-activity, MVVM with `StateFlow`. Min SDK 26, target latest stable.

### Screens
1. **Home**: New local game, New online game, Join game (Phase 3+), Settings.
2. **Game setup (local)**: pick map from rotation (preview thumbnail rendered from shapes), pick 2–4 players with names/colors, start.
3. **Game screen**:
   - Map canvas: territories drawn from parsed `shape` paths, filled with owner color (region color at 30% blend), 1px borders, army count badge at `labelX/labelY`, star icon on capitals. Pinch-zoom and pan.
   - Hit-testing via `Path.contains` on tap, using the inverse of the current zoom/pan transform.
   - Phase banner showing current player, phase, and remaining reinforcements.
   - REINFORCE: tap an owned territory to open a +/- stepper, confirm placement.
   - ATTACK: tap owned source (highlight legal targets), tap target → confirmation sheet showing army counts and the odds-hint bucket → Confirm launches the battle.
   - Battle presentation: a 1.5s animated sequence (shaking swords / rolling dice imagery, no real dice values) then the `BattleResult` card: "Captured!" or "Repelled", losses per side. On capture, show the occupy slider (min 1, default max).
   - FORTIFY: source/target selection with path-connectivity highlighting, army slider, or Skip.
   - Pass-and-play interstitial between turns ("Hand the device to <player>").
   - Game over screen with winner and per-player stats (territories held over time if cheap to track, else just final counts).

### ViewModel rules
- The ViewModel holds `GameState` and calls `Engine.reduce` directly for local play; all UI state derives from `GameState` + emitted events. No game logic in Compose code.

---

## 7. Online Multiplayer (Phase 3–4)

Backend: Firebase — Anonymous Auth + Firestore + Cloud Functions (validation) + FCM (turn notifications). Async-first: players take turns whenever; both online = effectively real-time via snapshot listeners.

### Firestore schema
```
games/{gameId}:
  mapId, status (lobby|active|finished), hostUid, createdAt, joinCode (6 chars A-Z0-9),
  playerUids: [uid], playerMeta: {uid: {name, colorHex}},
  state: <GameState JSON>, stateVersion: Int
games/{gameId}/actions/{n}: {playerId, action JSON, resultingVersion, createdAt}
```

### Move protocol
1. Client validates the action locally with `Engine.reduce` (optimistic UI).
2. Client writes the action to a Cloud Function (callable) with `expectedVersion = stateVersion`.
3. The function loads the game doc in a transaction, re-runs `Engine` validation logic, rejects on version mismatch or illegal action, otherwise writes new `state`, increments `stateVersion`, appends to `actions`.
4. All clients receive the new state via snapshot listener; local optimistic state is replaced by server state whenever versions diverge.
5. For v1 the Cloud Function may perform structural validation only (turn ownership, version, phase) with full rules re-validation deferred; note this as accepted risk for a friends-only game. Design the function boundary so full validation can be added without client changes.

### Lifecycle
- Lobby: host creates game (picks map), others join by code, host starts when 2–4 joined.
- Turn notification: FCM push to the next player on every turn change.
- Timeout: if the current player takes no action for 24h, the server auto-plays `EndReinforcePhase`(after auto-placing pending reinforcements evenly)/`EndAttackPhase`/`EndTurn` as needed to pass the turn. Two consecutive timeouts eliminate the player (territories become neutral defenders that never reinforce; adjust win checks so neutral capitals count as "not owned by any player" — a capitals win then requires owning all non-neutral capitals plus capturing any neutral-held capitals).
- Reconnection is free: state lives in Firestore; the app just re-subscribes.

---

## 8. Implementation Phases and Acceptance Criteria

Complete phases in order. Each phase must pass its criteria before the next begins.

### Phase 1 — game-core rules engine
- [ ] All models, `Engine.newGame`, `Engine.reduce`, `Engine.legalActions`, `Engine.captureProbability` implemented per Sections 2 and 4
- [ ] One hand-written 14-territory, 4-region `MapDefinition` in `maps/` for testing
- [ ] Unit tests covering: setup determinism, every rejection rule, reinforcement math (base, capture bonus cap, region bonuses), battle resolution edge cases (2v1, 2v9, dice-count degradation, defender ties), occupy bounds, fortify connectivity, elimination, both win conditions
- [ ] Determinism test: same seed + same action list ⇒ byte-identical serialized final state, run twice
- [ ] Fuzz test: 1,000 games played to completion by choosing uniformly among `legalActions`, asserting invariants after every action: every territory ≥ 1 army, army totals change only via battles/reinforcements, exactly one current player, game terminates ≤ 500 turns
- [ ] `captureProbability` sanity: monotone in attacker count, decreasing in defender count; `captureProbability(2,1) ≈ 0.417` (single 1v1 die round win chance 15/36, hmm verify: with 2 attackers, 1 die vs 1 die repeated until someone drops — compute from the DP, assert against an independent Monte Carlo run within ±0.01 rather than a hardcoded constant)

### Phase 1.5 — mapgen tool
- [ ] `generate`, `validate`, `approve` commands per Section 5
- [ ] All automatic validation checks implemented and unit-tested with adversarial fixtures
- [ ] SVG preview renders correctly for seeds 1–10
- [ ] game-core fuzz test extended to run its 1,000 games across 10 generated maps of varied sizes
- [ ] 3 maps approved into `maps/manifest.json`

### Phase 2 — local pass-and-play app
- [ ] All screens in Section 6; any manifest map playable end to end by 2–4 local players
- [ ] Map rendering, zoom/pan, hit-testing correct on all approved maps
- [ ] Odds hint shown before every attack confirmation
- [ ] Battle animation + result card; occupy slider; pass-device interstitial
- [ ] Full game completable with rotation only through Engine actions; process death mid-game restores state (persist GameState JSON to local storage on every reduce)

### Phase 3 — online play
- [ ] Firebase project wiring, anonymous auth, schema and move protocol per Section 7
- [ ] Two devices can complete a full game; version-conflict handling proven by simultaneous submissions in a test
- [ ] FCM turn notifications; 24h timeout auto-pass implemented server-side

### Phase 4 — lobby
- [ ] Create/join by 6-character code, lobby screen, host-start, map choice by host

### Phase 5 — polish
- [ ] Sound, haptics on battle result, capture/eliminated/win animations, per-player stats screen, settings (mute, name)

---

## 9. Open Questions (non-blocking, current defaults in parentheses)
- Capital placement: keep auto-assign or let players pick during setup? (auto-assign)
- Occupy step: keep the slider or always auto-move all-but-one to speed play? (slider with all-but-one default)
- Reinforcement base of 2: revisit after playtesting on larger maps — flat 2 may be too slow on 28+ territories; the constant must live in one place (`GameConfig`) so it is tunable per map size later.
