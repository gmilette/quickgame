# Mini RPG — Implementation Plan

A 2-player competitive mini RPG for the shared multiplayer platform (KMP + Firebase, game-agnostic match layer). Players race to grow stronger over 10 rounds of monster hunting on a shared map, then settle it in a final head-to-head battle. Visual and tonal target: Dragon Warrior (NES) — first-person monster battles, scrolling message box, chunky pixel tiles, limited palette.

This plugs into the existing platform as `gameType: "miniRpg"`. Auth, matchmaking, match documents, and the `submitMove` pipeline are already built; this plan covers only the game engine, content, and UI.

---

## 1. Game Design

### Match structure

1. **Hunt phase — 10 rounds.** Each round, both players secretly and simultaneously pick a map square to attack. Picks are revealed together, encounters auto-resolve, results are shown, next round begins.
2. **Final battle — 1 fight.** The two characters fight each other with everything they've built. Winner takes the match.

### The map

- A shared grid (recommend 5×5 = 25 squares for 10 rounds × 2 players = up to 20 clears, leaving scarcity pressure).
- Every square holds a monster with a visible **tier** (1–4). Higher tier = harder fight, more XP, better loot. Monster species and exact stats are visible on inspection — no hidden information about the map itself. The hidden information is *what your opponent will pick*.
- A cleared square is **consumed** — it becomes empty terrain for the rest of the match. The pool of good targets shrinks every round, so square selection gets tighter as the match progresses.
- Tier layout is roughly symmetric (mirrored halves) so neither player has a positional advantage. The map is generated per-match from a seed.

### Square selection and contention

Both players move on the same map and pick simultaneously (hidden until reveal — the exact mechanism the RPS pipeline was built to prove).

**Collision rule (recommended):** if both players pick the same square, both fight that monster independently, but the rewards are **halved for each** (round down, minimum 1 XP). The square is consumed either way.

This makes selection a genuine mind-game: the juicy tier-4 dragon is worth avoiding if you think your opponent wants it too, and deliberately crashing the opponent's obvious pick is a legitimate denial play. Alternatives if halving feels wrong in playtesting: full rewards to both (no contention pressure), or winner-of-a-speed-roll takes all (swingier).

### Encounter resolution

- **Win:** gain the monster's XP and roll on its loot table. Damage taken **persists** into future rounds.
- **Lose:** gain nothing, but HP resets to full for the next round. There is no death — losing costs you the round, not the run.
- Combat is fully auto-resolved in one action (same philosophy as the Risk game): the engine loops attack exchanges until one side drops, then emits a `BattleResult` plus a turn-by-turn log the client replays as an animated Dragon Warrior-style battle.

Design note worth watching in playtesting: because a loss fully heals, a player at 3 HP can intentionally feed themselves to a tier-4 monster to reset. That costs them a full round of progression, which is probably a fair price — it becomes a real strategic option rather than an exploit. If it proves too strong, change loss recovery to 50% HP.

### Character progression

One character class, four base stats, with builds emerging from level-up choices ("boons") and item archetypes rather than subclasses.

| Stat | Base |
|---|---|
| Max HP | 20 |
| Attack | 5 |
| Defense | 3 |
| Agility | 4 |

**Pacing goal: a level nearly every win.** XP thresholds per level: 8, 18, 30, 44, 60, 78, 98, 120 (levels 2–9). Monster XP by tier: 8 / 14 / 22 / 34. A player winning 7–8 hunts finishes around level 7–9. Combined with guaranteed loot on every win, every round ends with a level-up choice, a drop, or both.

**Level-up = pick one of three boons.** Each level-up presents three options drawn from the boon pool, seeded server-side, always including at least one plain stat boost. The choice is submitted alongside the next round's square pick, so it never costs a network round-trip.

Boon pool:

- **Vigor** — +6 max HP
- **Might** — +2 Attack
- **Iron** — +2 Defense
- **Swift** — +2 Agility
- **Keen Eye** — crit chance 1/16 → 1/10 → 1/7 (stackable)
- **Ambush** — your first strike each battle deals +4 damage (only pays if you strike first — synergizes with Swift/Wind Charm)
- **Berserk** — +3 Attack while below half HP (anti-synergy with herbs, deliberate synergy with damage-persistence)

Boons are gated by level where needed so no trap picks are offered early. **Level-up fully heals** (DW tradition, and it keeps damage-persistence from being purely punishing). Agility decides who strikes first in any battle, including the final one.

**Intended build archetypes** (the sim harness pits these against each other):

- **Crit assassin** — Keen Eye ×2 + Might + Fang Charm + Ravager Axe. Crits ignore defense: the designated tank-killer. Feast-or-famine in hunts, monstrous in the final battle.
- **Alpha striker** — Swift ×2 + Ambush + Wind Charm + Broadsword. Guaranteed first strike plus a +4 opener; reliable hunter, falls off in long fights against Vigor stacks.
- **Juggernaut** — Vigor ×2–3 + Iron + Plate + Spiked Mail. Wins by attrition and thorns. Note: with the min-damage floor of 1, Defense can't zero out chip damage, so tanks buy HP first, Def second.
- **Blood knight** — Berserk + Might + Vigor + Ravager Axe. Deliberately fights damaged; Vigor widens the below-half band where Berserk is live. Highest risk.

Soft counter-wheel: crits beat juggernaut, juggernaut beats alpha striker, alpha striker beats blood knight. Builds are public state, so counter-drafting mid-match — and contesting a snowballing opponent's squares — is part of the game.

### Loot

Three equipment slots plus consumables. Each slot has two **archetypes** per tier, so every drop is a keep-or-swap decision with a real tradeoff, not a strict upgrade:

| Slot | Reliable archetype (tier 4 values) | Aggressive archetype (tier 4 values) |
|---|---|---|
| Weapon | *Broadsword* +7 Atk | *Ravager Axe* +10 Atk, −2 Def |
| Armor | *Plate* +5 Def | *Spiked Mail* +3 Def, thorns: attacker takes 1 per strike |
| Charm | *Wind Charm* +6 Agi | *Fang Charm* +2 Agi, crits deal +50% damage |

Lower tiers scale the numbers down but keep the archetype identity (e.g., tier 2 Ravager Axe is +5 Atk, −1 Def). Thorns may need to scale to 2 at the top tier to keep the juggernaut viable — a sim question.

- **Herb** (consumable: restore 12 HP, auto-used between rounds if HP is below 40%; max 3 held)

Each monster tier maps to a loot table; every win drops something. Picking up an item you won't use converts to a small XP bonus instead, so no drop is ever dead.

### Combat math

Dragon Warrior-flavored formula, tuned for short fights (3–8 exchanges):

```
damage = max(1, attack / 2 - defense / 4 + roll(-1..2))
```

- Higher agility acts first; ties go to a coin flip rolled once at battle start.
- 1-in-16 chance per strike of a **critical** (ignore defense) — rare enough to be dramatic, matching DW's "excellent move!"
- Monsters use the same stat block shape as players, so one combat routine serves hunts and the final battle.

### The final battle

After round 10, the two characters fight using the identical auto-resolve routine. Herbs auto-fire mid-battle once each when the holder drops below 40% (max 3, mirroring DW's herb economy). Both players enter at whatever HP they finished round 10 with — the last few hunt rounds become a tension between grabbing one more level and preserving HP for the showdown. Winner of the battle wins the match; if both somehow reach 0 in the same exchange, higher remaining-overkill margin wins, then higher level, then coin flip.

---

## 2. Architecture Fit

The platform's seam requires: a server-side engine in Cloud Functions, a `publicState`/private-state shape, and Kotlin client models + screens against `MatchRepository`. Nothing in auth, matchmaking, or networking changes.

### State model

Square picks are the only hidden information, and only for the duration of a round. Reuse the RPS pattern exactly: pending picks live in per-player private subdocuments unreadable to the opponent; when both are in, the function resolves the round and writes results to public state.

```
matches/{matchId}
  gameType: "miniRpg"
  publicState:
    phase: "hunt" | "finalBattle" | "complete"
    round: 1..10
    mapSeed
    squares: [{ id, tier, monsterId, cleared }]
    players: {
      uid: { level, xp, hp, maxHp, attack, defense, agility,
             boons: [boonId], weapon: { tier, archetype },
             armor: { tier, archetype }, charm: { tier, archetype },
             herbs, pendingBoonChoices: [boonId] | null }
    }
    lastRoundResults: [{ uid, squareId, collided, battleResult }]
  private/{uid}/pendingPick: { squareId, boonId }
```

Everything except the pending pick is public by design — you can always see your opponent's level, gear, and HP. Scouting their build is part of picking your squares.

### Engine placement

- **Server (TypeScript, `functions/src/games/miniRpg.ts`):** authoritative rules — map generation from seed, pick validation, collision detection, combat resolution, loot rolls, level-ups, phase transitions. Same file layout as `rps.ts`.
- **Client (Kotlin, shared KMP module):** models mirroring public state, plus a *replay-only* combat visualizer that animates the server's battle log. The client never computes outcomes.
- All randomness is server-side from a per-match seeded PRNG so battles are reproducible from `(mapSeed, round, uid)` — this makes the 1,000-random-games fuzz test deterministic and debuggable, the same discipline as the Risk engine milestone.

### Kotlin client models

```kotlin
data class MiniRpgPublicState(
    val phase: Phase,
    val round: Int,
    val mapSeed: Long,
    val squares: List<Square>,
    val players: Map<String, CharacterState>,
    val lastRoundResults: List<RoundResult>
)

data class Square(
    val id: Int,
    val tier: Int,
    val monsterId: String,
    val cleared: Boolean
)

data class CharacterState(
    val level: Int,
    val xp: Int,
    val hp: Int,
    val maxHp: Int,
    val attack: Int,
    val defense: Int,
    val agility: Int,
    val boons: List<Boon>,
    val weapon: Item?,
    val armor: Item?,
    val charm: Item?,
    val herbs: Int,
    val pendingBoonChoices: List<Boon>?
)

enum class Boon { VIGOR, MIGHT, IRON, SWIFT, KEEN_EYE, AMBUSH, BERSERK }

data class Item(
    val slot: LootSlot,
    val tier: Int,
    val archetype: ItemArchetype
)

enum class ItemArchetype {
    BROADSWORD, RAVAGER_AXE,
    PLATE, SPIKED_MAIL,
    WIND_CHARM, FANG_CHARM
}

data class RoundResult(
    val uid: String,
    val squareId: Int,
    val collided: Boolean,
    val battle: BattleResult
)

data class BattleResult(
    val won: Boolean,
    val xpGained: Int,
    val lootTier: Int?,
    val lootSlot: LootSlot?,
    val log: List<BattleTurn>
)

data class BattleTurn(
    val actor: Actor,
    val damage: Int,
    val critical: Boolean,
    val herbUsed: Boolean,
    val hpAfterAttacker: Int,
    val hpAfterDefender: Int
)
```

### Move types

```kotlin
sealed interface MiniRpgMove {
    data class PickSquare(val squareId: Int, val boonChoice: Boon?) : MiniRpgMove
    data class ReadyForFinalBattle(val boonChoice: Boon?) : MiniRpgMove
}
```

`boonChoice` is required whenever `pendingBoonChoices` is non-null (validated server-side against the offered three) and must be null otherwise. The final battle fires automatically once both players have acked round 10 — the ack also carries any final boon choice from a round-10 level-up so it applies before the showdown.

---

## 3. Content

### Monster roster (12 species, 3 per tier)

Small roster, big personality — DW got enormous mileage out of palette-swapped slimes. Each monster is a stat block + sprite + one flavor quirk:

| Tier | Examples | Quirk ideas |
|---|---|---|
| 1 | Slime, Bat, Rat | none — pure tutorial fodder |
| 2 | Skeleton, Wolf, Imp | Wolf strikes first regardless of agility |
| 3 | Golem, Wraith, Knight | Golem halves non-critical damage below 4 |
| 4 | Dragon, Lich, Ogre Lord | Dragon breath: every 3rd turn, fixed 8 damage ignoring defense |

Quirks are one special rule each, implemented as flags in the stat block, not bespoke code paths. Ship tier 1–2 quirkless; add quirks in the polish phase if fights feel samey.

### Map generation

Trivial compared to the Risk Voronoi tool — a seeded shuffle:

1. Fix the tier distribution for a 5×5 board: 8× tier 1, 8× tier 2, 6× tier 3, 3× tier 4.
2. Place tiers mirror-symmetrically (12 mirrored pairs + center square, which is always tier 4 — the contested centerpiece).
3. Assign a random species per square from the tier's roster.

No approval tooling needed; symmetry guarantees fairness by construction. Pure function of the seed, lives in the server engine.

---

## 4. Pixel Presentation (Dragon Warrior feel)

- **Rendering:** Jetpack Compose `Canvas` over a 16×16 tile grid, integer-scaled with nearest-neighbor filtering (`FilterQuality.None`). Render to a fixed 256×224 virtual resolution and scale up — never draw at device resolution, that's what kills the pixel look.
- **Palette:** lock all art to a NES-like 4-colors-per-sprite constraint with a shared ~16 color master palette. Consistency of constraint matters more than the exact colors.
- **Map screen:** top-down tile grid, monster sprites sitting on their squares with a small tier pip, both player avatars visible, cleared squares become cracked-earth tiles. Cursor-based square selection (d-pad feel) rather than free tap — tap moves the cursor, second tap confirms.
- **Battle screen:** the DW signature — first-person view, monster sprite centered on a black backdrop with a decorative frame, player stats window top-left, scrolling message box on the bottom third ("Slime attacks! Thou art dealt 3 damage!"). The client replays `BattleTurn` entries at ~800ms per line with a tap-to-fast-forward.
- **Level-up screen:** on level-up, the classic fanfare jingle plays and a three-panel boon choice appears in the DW window style, each panel showing the boon name, its effect, and a one-line synergy hint ("Strikes true with the Fang Charm"). The choice locks in with next round's square pick.
- **Reveal moment:** each round, show both cursors sliding to their picks simultaneously before battles play out — the collision (or near-miss) is the round's dramatic beat, give it a beat of silence then a sting.
- **Text:** monospace pixel font, message box types character-by-character with the classic blip sound.
- **Audio:** chiptune loops (map theme, battle theme, final battle theme) + square-wave SFX. Defer to polish phase.

Faux-archaic DW dialect ("Thou hast gained 12 experience!") is a cheap, high-yield flavor win — keep a tiny string table so it's easy to tone down if it wears thin.

---

## 5. Implementation Phases

**Phase 1 — Rules engine (server, TS) + simulation harness.**
Map generation, combat resolution, XP/loot/boon level-ups, collision handling, phase transitions. Definition of done: a harness plays 1,000 seeded random matches to completion with zero invalid states, and prints aggregate stats (win rate by first-pick tier strategy, average final level, final-battle length). The harness also implements four **archetype bots** (crit assassin, alpha striker, juggernaut, blood knight — each greedily drafting its boons/items and picking squares by expected value) and runs a round-robin; any head-to-head above ~60% win rate flags a balance problem in the boon/item constants. Verify the pacing goal holds: median levels gained per match ≥ 6.

**Phase 2 — Kotlin models + local replay.**
Public-state models, a fake in-memory `MatchRepository`, and the battle-log replayer running against canned server output. No art yet — rectangles and text.

**Phase 3 — Pixel UI, vs. bot.**
Map screen, battle screen, reveal sequence, all sprites and tiles. A greedy bot (pick highest-tier winnable square) as the local opponent. This is the phase where the DW feel gets dialed in.

**Phase 4 — Platform integration.**
Register `miniRpg` in the `gameType` switch, wire `PickSquare` through `submitMove`, private pending-pick subdocuments + security rules (copy the RPS rules), reveal on both-picks-in. Verify with two anonymous emulator sessions.

**Phase 5 — Final battle + polish.**
Final battle flow and ack, monster quirks, herbs, audio, dialect strings, disconnect/timeout handling (auto-pick lowest uncleared tier square after N hours, consistent with the platform's async-play posture).

---

## 6. Risks and Open Decisions

- **Collision reward rule** (halved vs. full vs. winner-takes-all) — halved is the recommendation; decide after Phase 1 sim data shows how often optimal bots collide.
- **Intentional-loss healing** — monitor in Phase 1 sims; fallback is 50% restore on loss.
- **Snowballing** — the player who wins early tier-3 fights may run away with it. Levers if sims confirm: XP thresholds steepen faster, or trailing player (lower level at round start) gets +10% loot-tier odds. Don't add either preemptively.
- **Misbuild floor** — choice-based level-ups mean a player can build badly and get stomped, which feels worse than losing to dice. Mitigations already in the design: every offer includes a plain stat boost, boons are level-gated, numbers are small. If the round-robin shows any archetype under ~40% overall, buff its key boon/item rather than adding new pieces.
- **Thorns scaling** — with only one retaliation effect in the pool, Spiked Mail may need thorns 2 at tier 4 to keep the juggernaut viable. Sim question.
- **10 rounds × battle replays = pacing risk.** Replays must be skippable from day one, and the opponent's battle should be watchable as a compressed summary (result card) with optional full replay, not forced viewing.
- **IP note:** Dragon Warrior/Dragon Quest sprites, monster names (Slime in its DQ visual form), fonts, and music are protected. The *conventions* (first-person battles, message box, archaic dialect) are not. All art and names must be original.
