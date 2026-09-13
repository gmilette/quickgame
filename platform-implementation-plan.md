# Game Platform — Overall Implementation Plan

Master plan for building one KMP codebase that ships a suite of asynchronous cross-platform (Android/iOS) multiplayer games. A player opens one app, signs in once, and can play any game against opponents on either platform.

This document defines everything shared; the per-game documents define the games. The implementation agent receives all of these together:

| Document | Game |
|---|---|
| (this document) | Platform + Tic-tac-toe reference game |
| Mini RPG implementation plan + `mini-rpg-alternating-revision.md` | Mini RPG (grid hunt + final battle); the revision supersedes the plan's hunt-phase selection mechanics |
| `risk-lite-agent-spec.md` | Risk-lite |
| `quick-ww2-agent-spec.md` | Quick WW2 (Axis & Allies condensed, 4 game modes) |

Every game plays identically: strictly alternating turns — one active player takes their turn, then waits while the opponent takes theirs. There are no simultaneous or hidden submissions anywhere in the platform. Tic-tac-toe is the pipeline validation game (replacing the earlier RPS prototype, which is retired) and remains in the codebase as the reference implementation and regression canary. The mini RPG plan predates the alternating-turns decision; `mini-rpg-alternating-revision.md` reworks its hunt phase accordingly and takes precedence where they conflict.

Global conventions: Kotlin Multiplatform + Compose Multiplatform for all client code; GitLive Firebase Kotlin SDK to minimize expect/actual surface; Firebase Auth (Play Games on Android, Sign in with Apple on iOS) producing a platform-agnostic UID; Firestore for state; TypeScript Cloud Functions for authoritative server logic; all tuning data-driven via per-game config objects; no code comments anywhere; the Risk-lite map generator is a developer-only CLI, never compiled into the app.

---

## 1. Non-negotiable architectural rule

**Games plug into the platform; the platform never knows game rules.** Adding a game touches only: a new game module, a registry entry, a server handler registration, and a home-screen tile. It must require zero changes to auth, matchmaking, the move pipeline, or any other game's module. Tic-tac-toe proves this pipeline; every game below consumes it unchanged.

## 2. Module structure

```
:core:model            shared primitives: GameType, PlayerRef, GameStatus, MatchResult
:core:engine           GameEngine interfaces, validation/result types, seeded RNG utility
:core:firebase         auth, matchmaking client, game-doc listeners, submission writer
:core:ui               app shell, home screen, active-games list, shared replay framework, pixel-art theme
:games:tictactoe       reference game, minimal engine + UI
:games:rpg             mini RPG engine + UI
:games:risklite        Risk-lite engine + UI
:games:quickww2        Quick WW2 engine + UI
:tools:mapgen          Risk-lite map generator CLI (JVM only, not in app dependency graph)
:app:android           entry point, Play Games auth actual
:app:ios               entry point, Sign in with Apple actual
functions/             TypeScript Cloud Functions, one deployment, per-game handlers
```

Dependency rule, enforced with a Gradle check: `:games:*` depend on `:core:*`; `:core:*` never depend on `:games:*`; `:games:*` never depend on each other.

## 3. Game registry

```kotlin
enum class GameType { TIC_TAC_TOE, MINI_RPG, RISK_LITE, QUICK_WW2 }

interface GameDefinition {
    val gameType: GameType
    val displayName: String
    val configVariants: List<String>
    fun engine(): GameEngine
    fun initialStateJson(config: String, players: List<PlayerRef>, seed: Long): String
}

interface GameEngine {
    fun activePlayer(stateJson: String, configJson: String): String
    fun validate(stateJson: String, submissionJson: String, playerId: String, configJson: String): EngineValidation
    fun apply(stateJson: String, submissionJson: String, playerId: String, seed: Long, configJson: String): EngineResult
}

object GameRegistry {
    val all: Map<GameType, GameDefinition>
}
```

State, submissions, and config cross the module boundary as JSON strings (kotlinx.serialization inside each game module). The platform stores, routes, and schedules them without parsing them. `configVariants` covers Quick WW2's four modes and Risk-lite's map sizes; a variant id is chosen at matchmaking and resolved to a config inside the game module.

`activePlayer` returns the player id whose turn it is; `apply` is called with that player's single submission and produces the next state, in which `activePlayer` returns the opponent (or the game is over).

## 4. The submission pipeline (one pipeline, every game)

Every game runs the same loop:

1. The game doc carries `activePlayerId` (denormalized from `engine.activePlayer`) and `turnDeadline`.
2. The active player writes their turn as a single submission; security rules reject writes from anyone else.
3. The Cloud Function validates and applies atomically (all-or-nothing; a rejected submission surfaces errors, never partial state), writes the new public state and resolution logs, advances `activePlayerId`, resets `turnDeadline`.
4. Server-side seeded RNG, with the seed persisted in resolution logs, lets clients replay outcomes deterministically instead of trusting diffs.
5. A single scheduled function forfeits any player who is past `turnDeadline` without submitting, in any game.

All games are perfect-information and strictly alternating; there are no hidden moves, private documents, or per-game pipeline branches anywhere.

## 5. Firestore layout (uniform across games)

```
users/{uid}                     profile, per-game stats
queues/{gameType}/{variant}/entries/{uid}
games/{gameId}
  gameType, variant, players, activePlayerId, turnDeadline,
  stateJson, status, winner, engineVersion, createdAt
games/{gameId}/submissions/{round}-{uid}   writable only by the active player
games/{gameId}/logs/{key}                  resolution/battle logs for client replay
```

This layout supersedes collection naming in per-game documents (e.g. Quick WW2's `turns`/`battleLogs` map to `submissions`/`logs`); per-game docs remain authoritative for payload contents. Security rules are written once against this shape and are identical for every game. `engineVersion` is stamped at creation; a game is always resolved by the engine version it started with (server keeps prior handler versions until no live games reference them).

## 6. Cloud Functions

One codebase, one deployment:

- `onQueueWrite` — matchmaking: pair two entries in the same `{gameType, variant}` queue, create the game doc via the game handler's initial state, assign player order/sides randomly.
- `onSubmissionWrite` — routes by `gameType` to the registered handler, which implements the same `activePlayer`/`validate`/`apply` contract as §3 in TypeScript; validates and resolves the turn immediately.
- `onDeadlineSweep` (scheduled) — forfeits expired games of any type.

Parity requirement: every game module exports JSON test vectors (state + submission + seed → expected state) from its Kotlin engine tests; the TypeScript handler must pass the identical vectors in CI. This is the only defense against client/server drift — it is mandatory for every game, every engine change.

## 7. App shell & UI

- **Home screen** — tile per game from `GameRegistry` (Tic-tac-toe, Mini RPG, Risk-lite, Quick WW2), plus a unified "Your turn" list aggregating all active games across game types, sorted by deadline. Tapping a game tile → variant picker (where `configVariants.size > 1`) → queue.
- **Game screens** — each `:games:*` module exposes a Compose entry point `GameScreen(gameId)`; the shell hosts it and owns navigation, connectivity state, and the game-doc listener.
- **Shared replay framework** (`:core:ui`) — a stepper that plays resolution logs with skippable timed steps; the mini RPG battle log and Quick WW2 combat replay are both implementations of it; Risk-lite combat results render through it as single-step summaries.
- **Push notifications** — FCM/APNs "your turn" and "game over" notifications, triggered by the platform (not per game) whenever `activePlayerId` changes.
- **Theme** — shared pixel-art design system (Dragon Warrior NES lineage from the RPG) with per-game palettes.

## 8. Implementation order

Order is chosen so each phase adds exactly one new platform capability, and every game ships against an already-proven pipeline.

### Phase A — Platform consolidation
Restructure the existing code into the §2 modules; build tic-tac-toe as the reference game on `GameRegistry`/`GameEngine` (the RPS prototype and its private-subdocument machinery are removed); test-vector CI harness in place; home screen with tic-tac-toe playable.

Accept when: tic-tac-toe plays end-to-end on both platforms through the new registry (win, draw, forfeit-by-timeout all reachable); its test vectors pass in Kotlin and TypeScript; dependency rule check fails the build on a games→games import; no RPS or hidden-move code remains.

### Phase B — Mini RPG
Per its implementation plan as amended by `mini-rpg-alternating-revision.md` (open snake-draft hunt phase). Includes the simulation harness with the revision's draft-position and denial-bot additions.

Accept when: RPG acceptance criteria met per plan + revision, harness passes the revision's §6 checks, and tic-tac-toe still passes untouched (regression proof that games are isolated).

### Phase C — Risk-lite
Per `risk-lite-agent-spec.md`: first game with complex multi-part turns (attack sequences, reinforcement) on the same pipeline.

Accept when: Risk-lite acceptance criteria met per its spec; deadline forfeit works; tic-tac-toe + RPG regression green.

### Phase D — Quick WW2
Per `quick-ww2-agent-spec.md`, including its four config-variant modes and per-mode balance simulation.

Accept when: Quick WW2 acceptance criteria met per its spec; matchmaking correctly segregates its four variants; all three earlier games regression green.

### Phase E — Suite polish
Unified "Your turn" inbox, push notifications across all games, resign flows, finished-game browsing, per-game stats on the profile.

Accept when: a single account can have concurrent active games of all four types, receives correct notifications for each, and every game's replay renders from server logs.

## 9. Cross-cutting acceptance criteria (apply to every phase)

- A change inside one `:games:*` module never alters another game's test-vector outputs.
- Any device/platform pairing (Android↔iOS, Android↔Android, iOS↔iOS) is playable for every game.
- Server is authoritative everywhere: every client-side validation has a server twin, and rejected submissions surface an error state rather than desync.
- All tuning values (unit stats, XP curves, reinforcement rates, turn caps, timeouts) live in config objects/JSON, never in code paths.
- No code comments.

## 10. Open questions (defaults chosen — proceed unless overridden)

1. **Mini RPG hunt phase redesign — resolved by `mini-rpg-alternating-revision.md`** (open snake draft replacing simultaneous hidden picks; collision rule removed; denial drafting as the new tension). Its own open questions and defaults are listed in that document's §8.
2. **JSON-over-the-boundary** (§3) vs. typed generic `GameDefinition<S, M>`: JSON chosen so `:core:*` stays compile-time ignorant of game types and server/client share vector formats. Revisit only if serialization overhead shows up in profiling.
3. **Phase order C before D**: Risk-lite goes first because its spec predates Quick WW2 and its combat has no multi-round replay, making it the simpler consumer. If Greg prefers Quick WW2 sooner, the phases swap cleanly — both depend only on §4.
4. **Matchmaking is naive FIFO pairing** per queue. No ratings/ELO at launch; per-game stats recorded from day one so ratings can be added later without data loss.
5. **One deadline policy platform-wide** (72h default, per-game override in config). Scheduled sweep frequency: hourly.
