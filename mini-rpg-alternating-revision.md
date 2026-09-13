# Mini RPG — Alternating-Turns Revision

This document supersedes the hunt-phase selection mechanics in the original mini RPG implementation plan. The platform no longer supports simultaneous or hidden submissions (see platform implementation plan §4): every game is strictly alternating, perfect-information, one active player at a time. Hand this document to the implementation agent together with the original plan; where they conflict, this document wins.

## 1. What is removed

- Simultaneous hidden square selection and its private-subdocument mechanics.
- The collision rule (both players targeting the same square, independent fights with halved rewards). With open alternating picks, collisions cannot occur.
- Any mid-hunt PvP contact. Player-vs-player combat now happens exactly once: the final battle.

## 2. What is unchanged (the bulk of the original plan stands)

The shared 5×5 grid and 10 picks per player; consumed squares creating scarcity; auto-resolved monster combat with seeded RNG and a client-replayed battle log; the Dragon Warrior NES aesthetic; hunt stats, gear, and herb inventory carrying into the final battle; herb auto-use and HP carryover from the last hunt pick; the retuned XP curve across levels 2–9; boon-based level-ups (pick one of three); the full boon list including Ambush and Berserk (Forager and Riposte stay removed); dual-archetype items per slot; the four build archetypes and their soft counter-wheel; the final battle resolution rules.

## 3. The hunt phase is now an open snake draft

- All 25 squares' contents (encounter tier and reward profile) are visible to both players from game start. There is no hidden information.
- Players alternate picking one square per turn in **snake order**: A, B, B, A, A, B, B, A … for 20 picks total (10 each). Snake order compensates the second picker; who picks first is decided by the platform's random side assignment at matchmaking.
- On your turn you pick any unclaimed square. The encounter resolves immediately server-side (seeded, logged, replayed on both clients), applying damage, XP, gold, loot, and any level-up exactly as in the original plan.
- Picked squares are consumed. The 5 squares left unclaimed after pick 20 grant nothing to anyone.
- After pick 20 resolves, the final battle triggers automatically, per the original plan.

What replaces the collision mind-game: valuation and denial. Both builds are fully visible as they form, so a pick is always a tradeoff between the best square for your build and taking the square your opponent's build wants most — dual-archetype items make denial picks real decisions rather than griefing.

## 4. Turn submission shape

A hunt turn is one atomic submission by the active player:

```kotlin
data class HuntTurn(
    val squareId: String,
    val boonChoice: String?,
    val equipChanges: List<EquipChange>
)
```

`boonChoice` is required when and only when a level-up from the player's previous encounter is pending; validation rejects a turn that ignores a pending boon or supplies one when none is owed. Equip changes follow the original plan's slot rules and may accompany any pick.

## 5. State additions

```kotlin
data class HuntState(
    val pickIndex: Int,
    val draftOrder: List<String>,
    val squares: Map<String, SquareState>,
    val players: Map<String, HeroState>
)

data class SquareState(val encounter: EncounterDef, val claimedBy: String?)
```

`draftOrder` is the precomputed 20-entry snake sequence; `engine.activePlayer` reads `draftOrder[pickIndex]`. `EncounterDef` and `HeroState` come from the original plan unchanged.

## 6. Balance implications — simulation harness additions

The original Phase 1 harness (archetype bot round-robins, flag any head-to-head matchup above ~60%) still runs. Add:

1. **Draft-position check**: across all matchups, flag if the first picker's overall win rate falls outside 45–55%. Levers, in order: adjust snake pattern, then reward distribution of the grid.
2. **Denial-aware bots**: each archetype bot needs a denial heuristic (weight opponent's best-square value into pick scoring) — a draft harness without denial would validate a game nobody will actually be playing.
3. **Grid generation**: square contents are rolled at game creation from a config-defined distribution (same distribution as the original plan's reward tables). The seed is stored on the game doc; the harness must sweep many grids so balance conclusions don't overfit one layout.
4. Counter-wheel integrity re-check: open drafting lets a player pivot builds mid-hunt in response to the opponent; verify no archetype becomes dominant when bots are allowed one mid-draft pivot.

## 7. Pacing

Twenty alternating picks is more round-trips than the old ten simultaneous rounds. Each pick is a seconds-long decision, so the default per-turn deadline for this game is lowered to 24h (config override per platform plan §10.5, mechanism unchanged). If Phase B playtests feel slow, the config lever is picks-per-turn: 2 squares per turn, 10 turns total, same snake order — flagged, not default.

## 8. Open questions (defaults chosen — proceed unless overridden)

1. **Snake order** over strict A/B alternation: chosen to neutralize first-pick advantage structurally rather than through reward tuning. Harness check §6.1 verifies.
2. **Unclaimed squares grant nothing**: chosen for simplicity; an end-of-draft bonus for the last picker is an available lever if §6.1 shows late-position weakness.
3. **Fully visible grid from game start**: consistent with the platform-wide no-hidden-information rule. A fog-of-war variant would require platform changes and is out of scope.
4. **One pick per turn** with a 24h deadline (§7).
