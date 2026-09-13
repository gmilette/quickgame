# Consolidated Implementation Plan

Working plan for the quickgames platform and its four games. Revised 2026-09-12 against the code as built; the previous revision predated any code. Source documents and precedence:

1. `platform-implementation-plan.md` — master for anything platform-shaped. Where this document records that the built code deliberately differs from it (Reconciliation #10–#14), the built code wins and the master is stale on that point.
2. `quick-ww2-agent-spec.md` as amended by `quick-ww2-platform-revision.md` — the revision wins wherever they conflict. The revision is the agent-executable build spec for the MVP game.
3. `mini-rpg-implementation-plan.md` as amended by `mini-rpg-alternating-revision.md`, both as amended by `mini-rpg-platform-revision.md` (the agent-executable build spec for Phase B) and Reconciliation #15–#18, #25–#26 below.
4. `risk-lite-agent-spec.md` — authoritative for Risk-lite rules, engine contract, and mapgen; superseded on packaging, networking, lifecycle, and RNG (Reconciliation #1–#6, #19–#20).

Global invariants (unchanged): strictly alternating perfect-information turns; games plug into the platform, the platform never knows game rules; server authoritative with Kotlin/TypeScript parity proven by shared JSON test vectors; all tuning in config, never in code paths; seeded server-side RNG persisted in logs for deterministic client replay; no code comments; KMP + Compose Multiplatform + GitLive Firebase SDK + TypeScript Cloud Functions.

---

## 0. Where things stand (verified against the repo on 2026-09-12)

### Built and runtime-verified (Phase A)

- Modules `:core:model`, `:core:engine`, `:core:firebase`, `:core:ui`, `:games:tictactoe`, `:app:android`, `:app:ios`, plus `functions/` (TypeScript). Root `build.gradle.kts` registers `checkModuleDependencies`, wired into every module's `check`, failing on `games→games` and `core→games|app` project dependencies.
- Kotlin contract in `core/engine/.../GameEngine.kt`: `GameEngine { activePlayer, validate, apply }`, `GameDefinition { gameType, displayName, configVariants, configJson(variant), engine(), initialStateJson(configJson, players, seed) }`, `GameRegistry(definitions)`. `SeededRng` (mulberry32 over a 32-bit state, `nextUInt/nextInt(bound)/nextDouble/shuffle`).
- TypeScript twin in `functions/src/engine.ts` with the same shape plus `engineVersion` and `turnDeadlineHours` on the definition. `functions/src/pipeline.ts` (`resolveSubmission`, pure) and `functions/src/index.ts` (`onQueueEntryCreated`, `onSubmissionCreated`, `deadlineSweep`, `sweepNow`).
- Firestore layout as built: `games/{gameId}` (`gameType, variant, players, playerUids, activePlayerId, round, stateJson, status, winner, engineVersion, seed, turnDeadline, createdAt`), `games/{gameId}/submissions/{round}-{uid}` (`payloadJson, submittedAt`), `games/{gameId}/logs/{round}-{uid}` (`logJson, seed, createdAt`) and `logs/{round}-{uid}-rejected` (`type, errors, payloadJson`), `queues/{gameType}_{variant}/entries/{uid}`. `round` is a per-submission counter starting at 0; a rejected submission is deleted so the key is reusable. `firestore.rules` enforces active-player-only submission creation with the exact key. `firestore.indexes.json` has the `status + turnDeadline` composite the sweep needs.
- Tic-tac-toe in Kotlin and TS; `test-vectors/tictactoe.json` (27 vectors) exported by `TicTacToeVectorTest` (regenerate with `-Dvectors.regenerate=true`), consumed by `functions/test/vectors.test.ts` (vitest).
- `:core:ui`: `App()` shell with Home (registry tiles, Your turn / Waiting / Finished lists), Queueing, `GameHostScreen` hosting `GameUi.GameScreen(game, myUid, onSubmit)`; `rememberReplayState(stepCount, stepMillis)` stepper; `PixelPalette` theme.
- `e2e/run.mjs` (30 checks against emulators: matchmaking, apply, rejection, win, draw, forfeit via `sweepNow`); `e2e/opponent.mjs` scripted tic-tac-toe opponent; Android app played through against it.

### Gaps found in this review (each becomes a task in §2)

| # | Gap | Where |
|---|---|---|
| G1 | The repo has zero commits. `node_modules/` (root) and `app/ios/iosApp/Pods/` are untracked and not ignored. | `.gitignore`, git |
| G2 | RNG seeds are predictable by the active player: the game doc's `seed` is readable by both players (rules allow the whole doc) and `seedForRound(seed, round)` is a plain XOR, invertible from any log's `seed`. Harmless for tic-tac-toe; fatal for every RNG game (pick only fights you will win). | `functions/src/pipeline.ts`, `index.ts` |
| G3 | Config is not frozen: `def.configJson(variant)` is re-resolved on every submission, so a tuning deploy changes rules of in-flight games. `engineVersion` is stamped but never read. | `pipeline.ts`, `index.ts` |
| G4 | No read path for `logs/`: `PlatformClient` has no logs flow, `GameUi.GameScreen` receives no logs, rejections never reach the UI. The replay framework is a bare stepper with nothing feeding it. | `core:model`, `core:firebase`, `core:ui` |
| G5 | No variant picker: Home queues into `configVariants.first()`. | `AppShell.kt` |
| G6 | No TypeScript `SeededRng` twin and no RNG vectors; RNG parity is untested. | `functions/src` |
| G7 | No mechanism for game assets (maps, content, config) shared byte-identically by Kotlin and TS. | new |
| G8 | Kotlin `GameDefinition` lacks `engineVersion`; the vector file's `engineVersion` is a literal in the test. | `core:engine` |
| G9 | No CI. Vector parity, `check`, and e2e run manually. | new |
| G10 | iOS `xcodebuild` blocked by the CocoaPods dummy framework advertising device-only (see memory note / §2 P0.10). | `app/ios` |
| G11 | Emulator-only: both apps hardcode `useEmulators = true` with fake credentials; nothing is deployed. `docs/REAL-MULTIPLAYER-SETUP.md` covers the human steps. | `app/*` |
| G12 | Auth is anonymous, not Play Games / Sign in with Apple as the master plan specifies. | `core:firebase` |
| G13 | `turnDeadline` resets on every applied submission, not only when `activePlayerId` changes. Irrelevant for atomic-turn games; matters for Risk-lite's multi-submission turns. | `index.ts` |
| G14 | Stale text in the master plan: `onQueueWrite/onSubmissionWrite/onDeadlineSweep` names, `queues/{gameType}/{variant}/...` path, `object GameRegistry`. The code's versions are fine; the doc is out of date. | docs only |

---

## 1. MVP definition

**MVP = installable Android and iOS builds pointed at a real Firebase project, where two people are matched and play Quick WW2 in any of its four modes to completion asynchronously, with tic-tac-toe kept as the regression canary.**

Assumption (open question #1): Quick WW2 is the first real game. Reasons: the repo directory is `quickww2`; its spec is the only one already shaped for the atomic-submission pipeline exactly as built (one submission per turn, no pipeline change, no map generator, one static map asset); and its combat replay is the intended first consumer of the shared replay framework. Mini RPG and Risk-lite follow, in that order. If Greg wants a different first game, only §3 swaps; §2 is game-agnostic and stays.

In MVP: §2 platform hardening in full; Quick WW2 engine, TS twin, vectors, balance harness (CLASSIC gated, other modes reported), schematic map UI, replay, rejection surfacing, e2e, device play Android↔iOS, real deploy.

Out of MVP (first post-MVP items, in order): push notifications (§6, first), resign, Play Games / Apple sign-in, illustrated map art, audio, profile stats, finished-game browsing beyond the Home list.

MVP gate (all must hold):
1. `./gradlew check` and `npm test` (functions) green in CI; `checkModuleDependencies` still fails on an injected violation.
2. `test-vectors/tictactoe.json`, `test-vectors/rng.json`, `test-vectors/quickww2.json` pass in both languages; the Quick WW2 file has ≥ 150 vectors covering every validation error code and every rules clause listed in the revision §5.
3. e2e (`npm run e2e`) green including the Quick WW2 section; an illegal Quick WW2 turn is rejected server-side and the error reaches the UI.
4. A full Quick WW2 game is played between an Android device/emulator and the iOS simulator through the real Firebase project, ending by capital capture; a second game reaches the turn cap; a third is forfeited by the sweep.
5. Balance harness: CLASSIC passes all three spec §10 Phase 2 criteria; the other three modes have their results and any config levers applied recorded in `quick-ww2-platform-revision.md` §12.
6. Tic-tac-toe vectors unchanged by the Quick WW2 work (isolation regression).

---

## 2. Phase P0 — Platform hardening (game-agnostic; do before the game module)

Do these in order. Each is small; together they are what makes any RNG game safe to build. Where a task changes a Kotlin interface, tic-tac-toe is the only implementer and must be updated in the same change.

**P0.1 Repo hygiene.** Add `node_modules/` and `app/ios/iosApp/Pods/` to `.gitignore` (the existing `functions/node_modules/` and `e2e/node_modules/` entries stay). Make the initial commit. Commit after every P0 task from here on.

**P0.2 Unpredictable per-submission seeds (G2).**
- `resolveSubmission(game, submissionId, payloadJson, seed)` takes the seed as a parameter and stops calling `seedForRound`.
- `onSubmissionCreated` generates the seed at apply time: `crypto.randomInt(0, 0x100000000)` unless `process.env.QUICKGAMES_DETERMINISTIC_SEEDS === "1"`, in which case `seedForRound(game.seed, game.round)` as today. The env var is set only by the e2e script (root `package.json` `e2e` script) so emulator runs stay reproducible.
- Seeds are unsigned 32-bit integers platform-wide: TS generates them in that range; Kotlin receives them as `Long` and `SeededRng` folds to `Int` (already does). Vectors carry them as JSON numbers.
- `seed` stays on the game doc and is used only by `initialStateJson`; after creation it reveals nothing, because later seeds are independent. The log doc keeps `seed` (already written). Add a pipeline unit test that two submissions in the same game get different seeds unless the env var is set.

**P0.3 Freeze config and read engine version (G3).**
- `onQueueEntryCreated` writes `configJson` onto the game doc. `resolveSubmission` uses `game.configJson`, falling back to `def.configJson(game.variant)` only when the field is absent.
- `GameDocDto`/`GameDoc` gain `configJson: String` (default `"{}"` for old docs); `GameUi` receives it through `GameDoc`, so a game screen never re-derives config from the variant.
- `resolveSubmission` compares `game.engineVersion` to `def.engineVersion`; on mismatch it logs a warning and proceeds. Versioned handlers are not built at MVP (Reconciliation #11). Rule: any engine change that alters vectors bumps `engineVersion` on both sides.
- Add `engineVersion: Int` to the Kotlin `GameDefinition`; the vector exporter writes it from the definition (G8).

**P0.4 Logs read path and rejection surfacing (G4).**
- `:core:model`: `data class LogDoc(val id: String, val logJson: String?, val seed: Long?, val type: String?, val errors: List<String>, val createdAtMillis: Long?)`; `PlatformClient.logs(gameId: String): Flow<List<LogDoc>>` sorted by `createdAtMillis` then id. `:core:firebase` implements it with a snapshot listener on `games/{gameId}/logs`.
- `GameUi.GameScreen(game: GameDoc, logs: List<LogDoc>, myUid: String, onSubmit: (String) -> Unit)`; `GameHostScreen` collects `client.logs(gameId)` and passes it. Tic-tac-toe ignores `logs`.
- Rejection surfacing in the shell, once for every game: when `logs` contains an entry with `type == "rejection"` whose id starts with `"${game.round}-$myUid"`, the shell shows its `errors` in a dismissable banner above the game screen. A game screen may additionally interpret the codes.
- Replay bookkeeping is the game screen's job: it decides which logs are new (per-session `remember` of the last replayed log id is enough for MVP).

**P0.5 Variant picker (G5).** Add `fun variantDisplayName(variant: String): String = variant` to the Kotlin `GameDefinition`. Home tile tap: if `configVariants.size > 1`, show a chooser (list of display names) before `Screen.Queueing`; otherwise queue directly as today. Variant ids must not contain `_` (the queue id is `${gameType.id}_$variant`); use lower-kebab-case.

**P0.6 TypeScript `SeededRng` twin and RNG vectors (G6).**
- `functions/src/rng.ts`: mulberry32 identical to the Kotlin class (`state = seed | 0`; the Kotlin fold `(seed xor (seed ushr 32)).toInt()` equals `seed | 0` for seeds < 2^32), with `nextUInt/nextInt(bound)/nextDouble/shuffle`, all using unsigned 32-bit arithmetic (`>>> 0`, `Math.imul`).
- `core/engine` jvm test exports `test-vectors/rng.json`: for seeds `[0, 1, 7, 123456789, 4294967295]`, the first 64 `nextUInt`, 64 `nextInt(6)`, 8 `nextDouble`, and one `shuffle` of `0..19`. `functions/test/rng.test.ts` consumes it. Same regenerate flag convention as tic-tac-toe.

**P0.7 Shared assets (G7).** Canonical, hand-editable JSON lives in `shared-assets/<gameId>/<name>.json`. Both engines embed identical bytes:
- Kotlin: a Gradle task in each game module (`generateSharedAssets`) writes `build/generated/sharedAssets/commonMain/kotlin/<pkg>/SharedAssets.kt` containing `internal object SharedAssets { val MAP_JSON: String = "..." }` with the file content escaped as a Kotlin string literal (`\`, `"`, `$`); the directory is added to `commonMain` `kotlin.srcDir`. Define the task once in the root build script as a reusable function taking the asset directory.
- TS: `functions/package.json` gets `"prebuild": "node scripts/generate-assets.mjs"` and the same in `pretest`, writing `functions/src/generated/assets.ts` (`export const sharedAssets: Record<string, string>`), gitignored. This is what gets deployed; nothing reads `../shared-assets` at runtime.
- Parity: each game's vector file carries `assetHashes: { "<gameId>/<name>.json": "<sha256 of bytes>" }` computed by the Kotlin exporter from the embedded string; the TS vector test recomputes from `sharedAssets` and compares.

**P0.8 CI (G9).** `.github/workflows/ci.yml`, three jobs on push/PR: `gradle` (JDK 17 + Android SDK via `android-actions/setup-android`, `./gradlew check`), `functions` (Node 22, `npm ci && npm test && npm run build`), `e2e` (Node 22 + JDK for the emulators, root `npm ci`, `functions` `npm ci`, `npm run e2e`). iOS build job on `macos-latest` is added after P0.10 lands (`xcodegen`, `pod install`, `xcodebuild` for the simulator).

**P0.9 iOS build unblock (G10).** iOS is switched off by default since 2026-09-13: the `iosArm64/iosSimulatorArm64` targets in every module and the `:app:ios` include in `settings.gradle.kts` are gated on the Gradle property `quickgames.ios=true` (default `false` in `gradle.properties`). Re-enable with `-Pquickgames.ios=true` or by flipping the property when this task starts. Apply the fix from the memory note as a `doLast` on the CocoaPods dummy-framework task in `app/ios/build.gradle.kts` (PlistBuddy adds `iPhoneSimulator` to `CFBundleSupportedPlatforms` of `build/cocoapods/framework/Shared.framework/Info.plist`), then `pod install` in `app/ios/iosApp`, then confirm `xcodebuild -workspace iosApp.xcworkspace -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 16' build` succeeds. The scheme must stay declared in `project.yml`.

**P0.10 Environment switch and real deploy (G11).**
- Android: `USE_EMULATORS` boolean `buildConfigField` from `-PuseEmulators` (default `true`), and `FIREBASE_APP_ID/API_KEY/PROJECT_ID` from Gradle properties (defaults = today's fake values). `QuickGamesApplication` reads them.
- iOS: `mainViewController(useEmulators: Boolean, appId: String, apiKey: String, projectId: String)`; Swift passes values read from `Info.plist` keys populated by `project.yml` settings, defaulting to emulator values.
- Deploy: follow `docs/REAL-MULTIPLAYER-SETUP.md` (human steps: project, Blaze, anonymous auth enabled, register both apps), then `npx firebase deploy --only firestore:rules,firestore:indexes,functions`. Cloud Scheduler for `deadlineSweep` needs Blaze.

**P0.11 Doc sync (G14).** Not a code task: this document's §0 is the description of record for the built layout; do not "fix" the code toward the master plan's stale names.

Deferred to Phase C: G13 (deadline reset rule). Deferred to Phase E: G12 (auth providers).

P0 gate: everything in §0 "built" still passes; `test-vectors/rng.json` passes both sides; tic-tac-toe shows a rejection banner when the e2e injects an occupied-cell move through the UI (or the e2e asserts the `-rejected` log appears in the logs flow order); iOS simulator build green; CI green; a real-project deploy completes and two phones play tic-tac-toe on it.

---

## 3. Phase D — Quick WW2 (the MVP game)

Everything is specified in `quick-ww2-platform-revision.md`. Sub-phases and gates, in order:

- **D1 Engine (Kotlin).** `:games:quickww2` from the tic-tac-toe template; `shared-assets/quickww2/map.json` and `config.json`; models, map loader with symmetry validation, `validateTurn`/`applyTurn`/`checkVictory`, `GameDefinition`/`GameEngine` adapters. Unit tests per revision §8; vectors exported. Gate: all listed rules clauses have a passing test and a vector; determinism test green.
- **D2 TS twin.** `functions/src/games/quickww2.ts` passes `test-vectors/quickww2.json` and the asset hash check; registered in `registry.ts`. Gate: vitest green; e2e Quick WW2 section (revision §10) green.
- **D3 Balance harness.** Bots and harness per revision §9; run per mode; apply config levers only. Gate: MVP gate item 5.
- **D4 Client.** `TurnBuilder`, five screens, replay, rejection handling per revision §7. Gate: full game vs the pass-bot opponent on the Android emulator; every illegal move class from D1 is unstageable.
- **D5 Cross-platform and deploy.** iOS simulator plays against Android; then both against the real project. Gate: MVP gate items 3–4.

---

## 4. Phase B — Mini RPG (`:games:rpg`, post-MVP)

Everything is specified in `mini-rpg-platform-revision.md` (content asset, JSON shapes, rules clauses with vector names, client, harness, e2e, checklist). Summary and corrections to the source documents (details in Reconciliation #15–#18, #25–#26):

1. **Content as a shared asset.** `shared-assets/rpg/content.json` holds the boon pool (with level gates), item archetypes with per-tier values, the 12-monster roster with quirk flags, XP curve, tier distribution 8/8/6/3, herb rules, and the snake `draftOrder` pattern. Both engines embed it (P0.7). `configVariants = ["standard"]`.
2. **Engine (Kotlin, then TS via vectors).** Map from the creation seed (mirrored, center tier 4); `HuntState` with `pickIndex`, `draftOrder`, `squares` indexed by `squareId: Int` 0–24 row-major, `players`; `HuntTurn(squareId, boonChoice?, equipChanges)`; a pending boon applies (with its full heal) before the turn's encounter; encounters resolve with the per-submission seed; unclaimed squares grant nothing; the 20th pick's `apply` also resolves the final battle, and its log carries both `encounter` and `finalBattle`. Last-pick pending rewards follow Reconciliation #15.
3. **Simulation harness** in `androidUnitTest`, gated by `-Pbalance=true` like Quick WW2's: 1,000 seeded matches, zero invalid states, median levels gained ≥ 6; four archetype bots with denial heuristics; round-robin flag > 60%; first-picker win rate 45–55%; sweep many grids; one allowed mid-draft pivot.
4. **Client**: map/draft screen (cursor selection), battle screen replaying `BattleTurn`s through `rememberReplayState` (~800 ms/step, tap to skip; opponent battles as result cards), three-panel boon choice, keep-or-swap sheet, string table. 256×224 virtual canvas with integer scaling.
5. **Platform integration**: registry entry both sides; `turnDeadlineHours = 24` on the TS definition.
6. Polish: sprite sheet and audio only. Monster quirks and herbs are engine work from B1 because they are config-driven and vector-covered.

Gate: harness checks pass; playable end-to-end between two emulator sessions; tic-tac-toe and Quick WW2 vectors untouched.

---

## 5. Phase C — Risk-lite (`:games:risklite` + `:tools:mapgen`)

Spec rules and mapgen carry over intact; packaging, networking, lifecycle, and RNG are replaced (Reconciliation #1–#6, #19–#20):

0. **Pipeline change (the one this phase exists to prove):** `onSubmissionCreated` resets `turnDeadline` only when `activePlayerId` changes (G13). Submission keys stay `{round}-{uid}`; `round` already increments per submission, so no sequence index is needed. `engine.activePlayer` keeps returning the current player through REINFORCE → ATTACK → OCCUPY → FORTIFY until `EndTurn`, elimination, or win.
1. **Engine** per spec §§2, 4 with `rngState` removed: `reduce(map, state, action, seed)` where only `Attack` consumes RNG (the per-submission seed); setup uses the creation seed. `Player` is built from `PlayerRef` (id = uid, `displayName`, `colorHex` from a fixed table by `playerIndex`). `captureProbability` lives in the game module for the client odds hint and is not part of the engine contract. Full spec Phase 1 test list, determinism, 1,000-game fuzz over `legalActions`.
2. **`:tools:mapgen`** (JVM-only, excluded from `checkModuleDependencies`' app graph) per spec §5; `approve` writes into `shared-assets/risklite/maps/<mapId>.json` and `manifest.json`; `configVariants` = manifest map ids (kebab-case). Three approved maps (≈14/20/28) plus one hand-written 14-territory test map.
3. **Platform integration**: TS twin passing vectors; `BattleResult` events rendered as single-step summaries through the replay framework.
4. **UI** per spec §6 in Compose Multiplatform: map canvas from `shape` paths with zoom/pan/hit-testing, phase banner, reinforcement stepper, attack flow with odds buckets, 1.5 s battle animation + result card, occupy slider, fortify with connectivity highlighting, game-over screen. No pass-and-play interstitial.

Gate: spec Phase 1/1.5 criteria; full online game between two emulator sessions; a mid-turn deadline forfeit works; tic-tac-toe, Quick WW2, RPG regressions green.

---

## 6. Phase E — Suite polish

In order: (1) push notifications — `users/{uid}.fcmTokens`, server sends "your turn" when `activePlayerId` changes and "game over" on completion inside `onSubmissionCreated` and the sweep; Android FCM and iOS APNs-via-FCM registration; (2) resign — `games/{gameId}/resignations/{uid}` writable by either player, a function forfeits in favour of the other; (3) auth providers (Play Games, Sign in with Apple) with anonymous-account linking; (4) finished-game browsing with replay from logs; (5) per-game profile stats.

Gate: one account runs concurrent games of all four types with correct notifications; every game's replay renders from server logs.

---

## 7. Reconciliation decisions

Conflicts between documents, and between documents and the built code, resolved as follows.

| # | Conflict | Resolution |
|---|---|---|
| 1 | Risk-lite spec: standalone Android app, own repo, Jetpack Compose, min SDK 26 | Superseded — `:games:risklite` in the KMP monorepo, Compose Multiplatform, both platforms. |
| 2 | Risk-lite spec §7: own Firestore schema, anonymous auth, join codes, lobby, optimistic writes, structural-only validation | Superseded — platform auth, FIFO matchmaking, built layout, full server validation. No lobby/join codes at launch. |
| 3 | Risk-lite spec: local pass-and-play first | Dropped; online via the pipeline is the first playable form. **Flag if still wanted.** |
| 4 | Risk-lite spec: 2–4 players | Launch 2-player; engine keeps 2–4. |
| 5 | Risk-lite spec: 24h auto-pass, two timeouts → elimination + neutral territories | Superseded by the platform's single deadline forfeit. |
| 6 | Platform §3 implies one submission flips `activePlayer`; Risk-lite needs mid-turn results | `activePlayer` may return the same player across submissions until `EndTurn`. **Amended:** no sequence index in keys; `round` is already a per-submission counter. Deadline resets only on `activePlayerId` change (Phase C.0). |
| 7 | RPG original: simultaneous hidden picks, collisions, private subdocuments | Superseded by the revision: open snake draft. |
| 8 | RPG revision §3 mentions "gold" | Slip — rewards are XP + loot only. |
| 9 | Quick WW2 `turns`/`battleLogs` | Map to `submissions`/`logs`. |
| 10 | Platform §4/§6 "seed persisted in logs" vs built `seedForRound` derivation and public `seed` field | Per-submission seed generated server-side at apply time, independent of anything a client can read; log records it; `seed` on the game doc feeds only `initialStateJson`. Deterministic derivation survives only behind the e2e env flag. |
| 11 | Platform §5 "server keeps prior handler versions" | Not built at MVP. Config is frozen on the game doc at creation; engine-version mismatch warns and proceeds; vector-changing engine changes bump `engineVersion`. |
| 12 | Platform §5 `submissions/{round}-{uid}` with per-turn `round` vs games with their own round concept | Keep `round` = submission counter. Game-level rounds are named `gameRound` inside game state; never reuse `round`. |
| 13 | Platform §5 `queues/{gameType}/{variant}/entries/{uid}` vs built `queues/{gameType}_{variant}/entries/{uid}` | Code wins. Variant ids are lower-kebab-case without `_`. |
| 14 | Platform §6 function names, `object GameRegistry` | Code wins (`onQueueEntryCreated`, `onSubmissionCreated`, `deadlineSweep`, `sweepNow`; `class GameRegistry(definitions)`). |
| 15 | RPG: a level-up or loot drop earned on a player's final pick (draft positions 18 and 19) has no later turn to be chosen | Default: auto-resolve at the end of `apply` — pending boon → the offered plain stat boost (every offer has one); dropped loot → equip if the slot is empty, else convert to XP. Lever if it feels bad: one `FinalPrep(boonChoice, equipChanges)` submission per player after pick 20 (adds two round trips). |
| 16 | RPG revision `squareId: String` vs original `Square.id: Int` | `Int`, 0–24 row-major. |
| 17 | RPG: when a chosen boon takes effect | At the start of the turn that carries it, before that turn's encounter, including its full heal. |
| 18 | RPG plan Phase 1 places the harness in TS | Harness is Kotlin (JVM test source set), because the Kotlin engine is the source of truth and vectors carry parity. Same for every game. |
| 19 | Risk-lite spec `rngState` inside `GameState` (would be public and predictable) | Removed; `reduce` takes the per-submission seed; only `Attack` consumes RNG. |
| 20 | Risk-lite spec: Firestore/CLI packaging of maps | Maps are shared assets under `shared-assets/risklite/`, embedded on both sides; `mapgen approve` writes there. |
| 21 | Quick WW2 spec models carry `gameId`, `pendingPlacements`, `status`, `VictoryReason.FORFEIT/TIMEOUT`, `round`, `TurnSubmission.gameId/round` | Dropped or renamed per the revision §4: state gains `sides`, `winReason`, `gameRound`; forfeit/timeout are platform status, not engine results. |
| 22 | Quick WW2 spec leaves battle order, cargo representation, fighter reachability, move-list semantics, turn-cap timing, and tiebreak unit value undefined | Defined in the revision §5; all are vector-covered. |
| 23 | Platform: Play Games / Sign in with Apple | Anonymous auth stays through MVP (account is lost on reinstall — accepted for a friends MVP); providers with linking in Phase E. |
| 24 | Platform §8 order A → B → C → D → E | A → P0 → D → B → C → E. Quick WW2 first (see §1). |
| 25 | RPG revision §4 `equipChanges: List<EquipChange>` | Replaced by `lootDecision: EQUIP \| CONVERT` on the single pending item; loot for an empty slot auto-equips at drop time. |
| 26 | RPG original plan: quirks and herbs deferred to polish | Built in B1; only art and audio remain in B6. |

---

## 8. Open questions for Greg (defaults chosen — proceeding unless overridden)

1. **MVP game is Quick WW2** (§1). Swap to Mini RPG or Risk-lite by reordering §3–§5; P0 is unaffected.
2. **Anonymous auth through MVP** (Reconciliation #23).
3. **Push notifications are the first post-MVP item**, not in MVP. Say the word and they move into P0 (server side is ~30 lines; client registration on both platforms is the real work).
4. **Schematic node-map for the Quick WW2 board** at MVP (rounded rectangles positioned by asset coordinates, adjacency lines), illustrated map later.
5. **Balance gate at MVP**: CLASSIC must pass; other modes report-only with levers applied.
6. Earlier questions still standing: Risk-lite pass-and-play dropped; Risk-lite 2-player only.
