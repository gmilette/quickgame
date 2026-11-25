# Squad — Design (as built, 2026-11-21)

A 2-player squad-tactics game for the quickgames platform (`gameType: "squad"`), using the mechanics of 1980s tactical wargames: action points, facing, snap/aimed/auto shots, opportunity fire, directional armour and hidden movement. Original setting, units and maps. **Every number is a starting point to tune by playtesting.**

Code: rules in `games/squad`, screen in `games/squad-ui`. All numbers live in `SquadContent.kt`.

## 1. Decisions

| Topic | Decision |
|---|---|
| Fog of war | Real. Each player is sent only their own view of the state (see §8) |
| Turn | A player plans orders for all their soldiers and submits the whole turn at once; it is resolved on the server and the other player sees what their soldiers saw |
| Modes | **Skirmish** (symmetric points race) and **Raid** (attack vs defend), chosen when the game is hosted |
| Length | 12 rounds (24 turns) at most |
| Left out of v1 | Equipment buying, ammo and reloading, weight/burden, stamina, wounds, morale, doors, destructible walls, choosing deployment |

## 2. Map and movement

- 9 wide × 11 tall. Floor `.`, wall `#` (blocks movement and sight), rubble `:` (slow, gives cover), core `C` (raid only; blocks movement, becomes rubble when destroyed).
- 8 facings. Each 45° turn costs **1 AP**. A move turns the soldier to face its step first (and pays for the turn).
- A step costs **4 AP**, **6** diagonally, **+2** onto rubble. A diagonal step can't cut the corner of a wall or core. Squares with a soldier can't be entered.

## 3. Seeing

- A soldier sees within **45° either side** of its facing, with a clear line (walls block, rubble doesn't), and always notices anything next to it.
- A player sees an enemy only while one of their soldiers does. Enemies that drop out of sight leave a "last seen" marker.
- When a soldier is shot, its side learns where the shot came from.

## 4. Combat

**Shots.** Each costs a percentage of the soldier's AP allowance.

Hit chance = shot accuracy + skill bonus (unit skill × weapon skill factor; halved for auto, doubled for aimed) − 3 per square beyond 4 − 10 if the target is on rubble. Clamped to 5–95%.

Damage = weapon damage × random 50–150% − armour on the side hit − 3 if the target is on rubble.

Side hit: front if the attacker is within 45° of the target's facing, rear if more than 90° away, side otherwise.

**Close combat** (adjacent only): chance = melee accuracy + attacker agility / 2 − defender agility × (½ front, ¼ side, 0 rear). Damage = melee damage × 50–150% − armour.

**Grenades.** Throw at a square in view within 6 squares, cost 25%. Lands on target with chance = agility, otherwise one square off. Explodes at the end of the thrower's turn: 20 × 50–150% − side armour to everyone in the 3×3 area.

**Opportunity fire.** During the opponent's turn, every soldier that can see a moving enemy fires one snap shot per enemy step, while it has at least **half its AP allowance** left and enough for the shot. AP left at the end of your turn is what your soldiers react with; it refills at the start of your next turn.

**Halt on sight.** A moving soldier stops when it spots an enemy its side hadn't seen; its remaining orders that turn are dropped. Other soldiers' orders still run. Orders that can no longer run (blocked, out of AP, target gone or out of view) are skipped and reported to their owner.

### Weapons

| Weapon | Snap AP% / acc | Aimed | Auto (3 shots) | Skill factor | Damage | Melee AP% / acc / dmg |
|---|---|---|---|---|---|---|
| Auto-rifle | 25 / 45 | 50 / 65 | 40 / 30 | 30 | 14 | 30 / 40 / 8 |
| Long rifle | 30 / 35 | 60 / 80 | — | 40 | 20 | 30 / 40 / 8 |
| Heavy beam | 35 / 35 | 65 / 55 | 50 / 25 | 20 | 24 | 35 / 30 / 10 |
| Blade | — | — | — | — | — | 20 / 70 / 20 |

### Soldiers

| Kind | AP | HP | Skill | Agility | Armour F/S/R | Weapon | Grenades |
|---|---|---|---|---|---|---|---|
| Sergeant | 36 | 30 | 60 | 55 | 6/4/2 | Auto-rifle | 1 |
| Trooper | 36 | 26 | 50 | 50 | 6/4/2 | Auto-rifle | 1 |
| Marksman | 32 | 22 | 70 | 45 | 4/3/2 | Long rifle | 0 |
| Heavy | 28 | 36 | 45 | 35 | 10/7/4 | Heavy beam | 0 |
| Striker | 42 | 24 | 40 | 75 | 4/3/2 | Blade | 2 |

## 5. Skirmish

- Each side: Sergeant, Trooper, Marksman, Heavy, starting on opposite edges. The map is the same rotated 180°.
- **+2** per enemy killed. **+1** at the end of your own turn if you have a soldier in the centre zone (3×3) and the enemy has none there.
- First to **10**, or wipe out the enemy. After 12 rounds, higher score wins; a tie is a draw.
- Opening: the first player gets **65%** AP on turn one, and the second player's soldiers start with no AP saved for opportunity fire. (Without this, bots showed a strong second-seat advantage.)

## 6. Raid

- Player index 0 attacks with 5 (Sergeant, 2 Troopers, Heavy, Striker) from the bottom edge. Player index 1 defends a walled base at the top with 3 (Trooper, Marksman, Heavy), starting on guard with full AP.
- **Sabotage** an adjacent core: 50% AP. Destroying **2 of 3** cores wins for the attacker, as does wiping out the defenders.
- Defenders win by wiping out the attackers or lasting 12 rounds.

## 7. Bot simulation

`./gradlew :games:squad:jvmTest` runs 300 bot games per matchup (`SquadSimulation`). With simple greedy bots:

| Matchup | Result |
|---|---|
| Skirmish, same bot both seats | first seat 41%, second 59%, median 10 turns |
| Raid, same bot both sides | attacker 64%, defender 35%, median 9 turns |

Seat balance swings a lot with small changes (opening AP of 50% → first seat 72%, 75% → 92%), so it is driven by the bots' fixed timing as much as by the rules. Real play should decide. Raid bots rarely destroy cores; most raids end in a firefight.

## 8. Platform: per-player views

- `GameDefinition.playerStateJson(stateJson, uid)` (default null). When a game returns a view, the server writes `games/{id}/views/{uid}` = `{stateJson, round}` in the same transaction as the game doc, and sets `hasViews: true` on the game doc.
- Firestore rules: a player can read only their own view; nobody can write views.
- `FirebaseClient.game()` shows the player's view in place of the public state, and only once the view's round matches the game doc's.
- The public `stateJson` on the game doc has no soldiers and no events. At game end both views show everything.
- `GameDefinition.variantLabel()` names variants in the lobby, which now offers one entry per variant.

## 9. Trying it locally

With the emulators and server running (see README), and the app switched to the emulators:

```
node e2e/squad-opponent.mjs skirmish   # or raid
```

hosts a game as "Squad-Bot" that turns one soldier each turn. Join it from the app.
