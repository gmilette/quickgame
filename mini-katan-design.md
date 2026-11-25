# Mini Katan — Design (draft)

A 2-player, short version of a Catan-style resource game for the quickgames platform (`gameType: "miniKatan"`). The board has 7 hexes: one center hex and one ring of six. The original game's rules apply except for the changes listed below. **Every number here is a starting point to tune by playtesting.**

## 0. As built (2026-11-16)

The game is in `games/hexstead` (rules) and `games/hexstead-ui` (screen), game type `hexstead`, shown in the app as **Hexstead**. The rules below are what was built, with these names:

| In this doc | In the game |
|---|---|
| Mini Katan | Hexstead |
| Brick, Lumber, Wool, Grain, Ore | Brick, Wood, Sheep, Wheat, Stone |
| Knight / Largest Army | Raider / Most Raiders |
| Victory Point card | Monument |
| Road Building | Road Crew |
| Year of Plenty | Windfall |
| Monopoly | Levy |

Differences from §9 (screen design) in the first build:
- Trade and the resource pickers appear in the pinned bottom bar, not in a bottom sheet.
- A bought card is reported in the event line, not in a dialog.
- The event panel shows the last 3 lines of the previous turn and the current roll, and expands on tap. It does not step through the opponent's turn.
- Setup is one submission: tap a corner, then tap a road next to it.

Bot simulation (300 games, simple greedy bots): median 20 turns each, first seat wins 48%.

## 1. What changes from the original

| Original rule | Mini Katan |
|---|---|
| 19 hexes, 3–4 players | **7 hexes** (center + 1 ring), **2 players** (the platform is 2-player) |
| Distance rule: no settlement next to another settlement | **None.** A settlement can go on any empty intersection, even next to another settlement. After setup it must still touch one of your own roads |
| Development cards are held, at most one played per turn, never on the turn bought | **Take effect immediately when bought.** No hand of cards |
| Victory point cards stay hidden | Score immediately and are public |
| 10 VP to win | **7 VP** |
| Longest Road: 5+ roads | **4+** |
| Largest Army: 3+ knights | **2+** |
| Deck of 25 development cards | **12 cards** (about half) |
| Trades with other players, 4:1 with the bank, 3:1/2:1 harbors | **v1: bank only, 3:1 for any resource**, no harbors, no player trades |
| Robber: a 7 or a Knight moves it, blocks a hex, steals a card | **No robber.** A 7 only triggers the hand limit. A Knight steals one random card from the opponent |
| Hand limit discard on a 7 chosen by each player | The server discards at random |
| Desert hex | None |

Unchanged: costs, the 2d6 roll, city = double production, 2 VP for each award, snake setup, and supply limits (scaled, §6).

## 2. Board

```
        ___
    ___/ R1\___
   / R6\___/ R2\
   \___/ C \___/
   / R5\___/ R3\
   \___/ R4\___/
       \___/
```

- **24 intersections**: 6 touch the center hex plus two ring hexes (**3 hexes**); 6 on the outer edge touch **2** ring hexes; 12 on the outer edge touch **1** hex.
- **30 edges** for roads.
- Hex coordinates: axial, center `(0,0)`, ring in fixed clockwise order. Intersections and edges are derived from hex corners once, as constants, the same way Quick WW2 keeps its map in Kotlin.

### Terrain (random, no desert)

Each match deals the 7 hexes from the match seed:

1. Take one of each resource: Brick, Lumber, Wool, Grain, Ore. That is 5 hexes, so every resource is always on the board.
2. Add 2 extra hexes of two **different** random resources. No resource appears more than twice.
3. Shuffle the 7 hexes over the 7 positions.

### Number tokens (random)

`3, 4, 5, 6, 8, 9, 10`, shuffled over the hexes with one rule: the **6 and the 8 go on ring hexes that are not neighbors**. Every ring hex touches the center, so the center never gets a 6 or 8. Its corners already touch three hexes, so it shouldn't also be the hottest.

On each roll there is a 26/36 chance that some hex produces. The other 10/36 (2, 7, 11, 12) produce nothing. If playtests show too many dead turns, the fix is for a 7 to give the roller one resource of their choice.

## 3. Costs (unchanged)

| Build | Cost | VP |
|---|---|---|
| Road | 1 Brick + 1 Lumber | — |
| Settlement | 1 Brick + 1 Lumber + 1 Wool + 1 Grain | 1 |
| City (upgrade a settlement) | 3 Ore + 2 Grain | 2 (replaces 1) |
| Development card | 1 Ore + 1 Wool + 1 Grain | — |

## 4. Setup

The order is A, B, B, A, the snake from the original. Each placement is one settlement plus one road touching it. A player's **second** settlement collects one of each resource from the hexes it touches.

With no distance rule, seat A takes the best 3-hex center corner. Seat B answers with two picks in a row. That is the reason for the snake order. The bot simulation (§8) checks whether seat A still wins too often on random boards.

## 5. A turn

1. **Roll** (automatic). The server rolls when the turn starts, using that submission's hidden seed. Every settlement on a hex with the rolled number gets 1 card, and every city gets 2.
2. **On a 7**: any player with more than 7 cards loses half, rounded down, picked at random by the server. Nothing else happens.
3. **Build, buy and trade** (bank, 3:1) in any order, as many times as resources allow.
4. **End turn.** A player with **7+ VP during their own turn** wins at once, including when a VP card is bought.

### Development cards (12-card deck, shuffled by seed, effect on purchase)

| Card | Count | Immediate effect |
|---|---|---|
| Knight | 6 | Steal 1 random card from the opponent (nothing if they have none). Counts toward Largest Army |
| Victory Point | 2 | +1 VP, public |
| Road Building | 2 | Place 2 free roads now (lost if no legal spot) |
| Year of Plenty | 1 | Take any 2 resources from the bank |
| Monopoly | 1 | Name a resource; the opponent gives you all of theirs |

When the deck is empty, nothing more can be bought. Knight and Victory Point need no choice. For the other three, the choice (road spots or resources) is made right after the card is revealed (§8).

### Awards (2 VP each)
- **Longest Road**: the longest unbroken road of **4+** segments. A road is cut where an opponent's settlement or city sits at an intersection. The player who got there first keeps it on ties.
- **Largest Army**: **2+** Knights. The player who got there first keeps it on ties.

## 6. Supply limits per player

8 roads, 5 settlements, 4 cities. With 24 intersections and no distance rule, the settlement limit is what keeps one player from covering the board.

## 7. Win target sanity check

You start with 2 VP. One path to 7: two cities (+2), one more settlement (+1), Longest Road (+2). Another: three settlements become cities plus a VP card. Target game length: **about 12–20 turns each**. If a simulation shows games running long, lower the target to 6. If it shows first-player wins above about 55%, add a small catch-up for seat B (B starts with 1 extra resource of its choice).

## 8. How it fits the platform

- Modules `games/katan` (rules) and `games/katan-ui` (screen), registered like `rpg`.
- **One action per submission**: `roll` is implicit, then `build`, `buyDev`, `trade`, `chooseResources`, `placeFreeRoad`, `endTurn`. The active player stays the same until `endTurn`. Each submission has its own hidden seed, so dice, steals and card draws can't be predicted from the client.
- A **pending** field in the state (`pending: yearOfPlenty | monopoly | freeRoads(n)`) blocks every other action until it is resolved.
- All state is public: hands, VP and the board. The only hidden thing is the deck order, which comes from the server seed.
- The state keeps a **list of the latest turn's events** (roll, gains, builds, cards, steals), the same way the RPG keeps its latest fight. The screen uses it to show what the opponent did.
- The rules are pure Kotlin, and a jvmTest bot simulation (greedy builder against greedy builder) checks the target VP, seat balance and game length before the UI is built.

## 9. Screen design (phone, portrait)

One screen, no tabs. The board is always visible, and everything else is a thin strip above or below it.

```
┌────────────────────────────────┐
│ Sam   4/7 VP   5 cards         │  opponent strip (1 line)
│ Knights 1   Longest Road ★     │
├────────────────────────────────┤
│                                │
│          7-hex board           │
│    (fills the screen width)    │
│                                │
├────────────────────────────────┤
│ Rolled 8 · you +2 Grain        │  event line (tap: turn log)
├────────────────────────────────┤
│ You  3/7 VP                    │
│ Brick 2  Lumber 1  Wool 0      │  your hand: 5 counters
│ Grain 3  Ore 1                 │
├────────────────────────────────┤
│ Road  Settle  City  Card  Trade│  action bar (pinned to the
│          [ End turn ]          │  bottom, always visible)
└────────────────────────────────┘
```

- **Action bar pinned.** The action bar and End turn button are fixed to the bottom of the screen and always visible. Everything above them (opponent strip, board, event line, hand) is one column that may scroll up and down on a short screen.
- **Board is fixed.** No panning or zooming inside the board. It always shows all 7 hexes.
- **Board.** Three hexes across is 5 hex sides wide, so on a 360dp phone the corners are about 68dp apart. That is big enough to tap without zooming. Each hex shows its resource color and its number, with the 6 and 8 in red. Roads, settlements and cities are drawn in the two player colors.
- **Building is two taps.** Tap **Road**, **Settle** or **City** and the board lights up only the legal spots. Tap a spot to build. Tap the button again to cancel. Nothing on the board is tappable outside a build mode, so stray taps do nothing.
- **Action buttons show their cost** as small resource dots and are greyed out when you can't afford them or have no legal spot. That replaces a separate cost card.
- **Card.** One tap buys, then a small dialog shows the card and what it did ("Knight: you stole 1 Wool"). Road Building goes straight into road mode for 2 roads. Year of Plenty and Monopoly show a 5-resource picker.
- **Trade.** A bottom sheet with two rows of the 5 resources: give 3 (only resources you hold 3 of are enabled), get 1.
- **Hand.** Five counters, always visible. A counter pulses when the roll adds to it.
- **Opponent strip.** VP, card total, Knights and award badges. Tap it to see their exact hand, since all state is public.
- **Event line.** Shows the latest roll and what it produced. When you open the game after the opponent's turn, it steps through their turn log first: roll, builds, cards. Tap it to see the full log.
- **Not your turn.** The action bar is replaced by "Waiting for Sam". The board stays browsable.
- **Setup.** The same screen. The action bar is replaced by "Place a settlement", then "Place a road", with legal spots lit.

## 10. Decisions (2026-11-16)

- **Robber.** Removed. A 7 only triggers the hand limit, and a Knight steals one random card.
- **Board.** Random each match, with every resource on the board at least once (§2).

- **Roads.** After setup, a new settlement must touch one of your own roads. Only the distance rule is dropped, so a settlement may sit right next to any other settlement, including an opponent's.
- **Trading.** v1 has bank 3:1 only. No harbors, no player-to-player trades.
- **Discards on a 7.** The server discards at random, half rounded down, for any player holding more than 7 cards. Nobody waits.
- **Win target.** 7 VP. The 12-card deck stands until the bot simulation says otherwise.
