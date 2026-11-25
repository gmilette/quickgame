# Spice Road — Design (draft 3)

A 2-player competitive idle game for the quickgames platform (`gameType: "spiceRoad"`). A game lasts a fixed number of hours, chosen when it is created: **12 or 24**. Players explore a shared desert grid to open trade routes, improve their caravans, and spend spice on monuments. Most monuments wins. **Every number here is untested and is a starting point to tune with a bot simulation.**

## 1. Decisions already made

| Topic | Decision |
|---|---|
| Clock | One hour = one round. Both players act once, then caravans move |
| Game length | 12 or 24 hours, chosen when the game is created |
| Map | Tiles start face-down and are revealed to both players as the grid is explored. Each side is shuffled separately |
| Currency | Separate spices. No pooled currency and no money |
| Prices | Fixed for the whole game. A spice's price never drops |
| Exchange | Allowed at the home market, by value, so dearer spices buy more |
| Premium spices | Two, one on each player's side |
| Travel time | Farther tiles take longer round trips |
| Blocking | Caravans cannot cross the opponent's tiles, so a claim can lengthen or cut the other player's routes |
| Royal tiles | Each is reserved for the player on its side. The opponent can never claim it |
| Spice Road idle rules (storage caps, offline trading) | Not used |

## 2. The core tension

A player gets one action per hour, and building a monument uses that action as well as spice. Every hour spent exploring or upgrading is an hour not spent on a monument. On top of that, the richest spices are the farthest away, so their caravans come home less often and their first load arrives late.

## 3. Board

A 5×5 grid. Home markets are in opposite corners and produce nothing. The centre tile is the **Oasis**: nobody owns it, it produces nothing, and both players' routes may pass through it. That leaves 22 source tiles.

Rarer spices sit farther from the homes. "Distance" below means grid steps (no diagonals) to the nearer home.

| Distance | Tiles | What is there |
|---|---|---|
| 1–2 | 5 per side | Essential: 2 Pepper, 2 Cinnamon, 1 Ginger |
| 3 | 4 per side | Valuable: 1 Cardamom, 1 Cloves, 1 Nutmeg. Plus that side's **Royal** tile |
| 4 (the middle line, equally far from both homes) | 4 | Precious: 2 Vanilla, 2 Star Anise |

- **Royal tiles:** Saffron on one side and Frankincense on the other, both face-up from the start. Each is 3 steps from its own player's home and 5 from the other's, and only that player may claim it. Claiming a Royal tile costs a **tribute** of spice worth 6; every other tile is free.
- Within each band, which spice is on which tile is shuffled by the match seed and is face-down. Players know a tile's tier from its position, but not its spice.
- Each side is shuffled separately, so the two players' halves hold the same spices in different places. Revealing a tile says nothing about the other side. The four Precious tiles on the middle line are shuffled together.
- At the start, the two tiles next to each home are face-up.

### Prices (fixed)

| Tier | Price per unit | Spices |
|---|---|---|
| Essential | 1 | Pepper, Cinnamon, Ginger |
| Valuable | 4 | Cardamom, Cloves, Nutmeg |
| Precious | 8 | Vanilla, Star Anise |
| Royal | 10 | Saffron, Frankincense |

This replaces Spice Road's "price = distance × tier" formula. The idea survives in the layout: the valuable spices are the distant ones. Prices that depend on distance to the nearest source can't be kept without price drops.

## 4. Caravans and travel time

- A player starts with **1 caravan** that carries **3 units** per trip.
- Each caravan is assigned to one owned tile. A tile takes only one caravan.
- **A round trip takes as many hours as the length of the route from the player's home to the tile.** A tile 1 step away delivers every hour; a Royal tile 3 steps away delivers every 3 hours.
- **Blocking:** the route is the shortest path that does not cross a tile the opponent owns. It may cross unowned tiles, the player's own tiles and the Oasis. If the opponent claims a tile on the route, the caravan must go around and the trip gets longer. The new length applies from the caravan's next departure. If no path is left, the tile delivers nothing until one opens (see Q7).
- A caravan delivers its load at the end of the hour in which it gets home. A load that would arrive after the final hour is lost, and one that arrives at the end of the final hour only counts for the tie-break.
- Reassigning a caravan is free but takes effect when it is next at home.

Value per hour for one starting caravan, before upgrades:

| Route | Load value | Trip | Per hour |
|---|---|---|---|
| Essential, 1 step | 3 | 1 h | 3 |
| Essential, 2 steps | 3 | 2 h | 1.5 |
| Valuable, 3 steps | 12 | 3 h | 4 |
| Precious, 4 steps | 24 | 4 h | 6 |
| Royal, 3 steps | 30 | 3 h | 10 |

## 5. An hour

Order within an hour follows the snake: A-B, B-A, A-B, … (see Q1).

On their turn a player does **one** action. They may also reassign caravans and use the exchange for free.

| Action | Cost (spice value) | Effect |
|---|---|---|
| **Explore** | Free | Claim a face-up, unowned tile next to your home, the Oasis (if your route reaches it), or a tile you own. Its face-down neighbours are turned face-up for both players; if one of them is the Oasis, the Oasis's neighbours are turned face-up too |
| **Wait** | Free | Do nothing this hour |
| **Claim your Royal tile** | Tribute of 6 | As Explore, but for a Royal tile (see §3) |
| **Hire a caravan** | 6, then 12, then 18 | +1 caravan |
| **Bigger loads** | 6, then 12, then 18 | Every caravan carries +1 unit per trip |
| **Faster camels** | 8, then 16 | Every round trip is 1 hour shorter, to a minimum of 1 hour |
| **Build a monument** | See §6 | +1 monument |

Each upgrade level has a name:

| Level | Caravans | Loads (units per trip) | Camels (trip time) |
|---|---|---|---|
| Start | Lone Trader (1) | Woven Saddlebags (3) | Pack Camels (full time) |
| 1 | Bedouin Drovers (2) | Goat-Hair Panniers (4) | Mehari Riders (−1 hour) |
| 2 | Nabataean Traders (3) | Brass-Bound Chests (5) | Simoom Racers (−2 hours) |
| 3 | Sultan's Convoy (4) | Great Hawdaj (6) | — |

Mehari are a swift riding breed of camel, the simoom is a hot desert wind, the Nabataeans were the incense traders of Petra, and a hawdaj is a camel litter.

A tile has one owner for the whole game and can never be taken from them. Claiming a tile denies it to the opponent and blocks their caravans from crossing it (§4).

The three upgrades are Spice Road's caravan levers. Hiring helps players with many tiles. Saddlebags help everyone evenly. Faster camels only help distant routes, which is where the rich spices are.

### Paying

A cost is a value, such as 12. The player chooses which units to pay with, and their prices must add up to at least the cost. No change is given.

### The exchange

At any point in their turn a player may swap spices at their home market. They give up spices worth at least **twice** the value of what they take. One Saffron (10) buys one Valuable spice (4) with 2 left over and wasted, or five Essential units. A player may only take a spice that is face-up somewhere on the map.

The exchange lets a player fill a gap in a monument's variety requirement, at half the efficiency of producing the spice themselves.

## 6. Monuments

Each monument has a value to pay and a minimum number of **different spices** in the payment. Each player builds them in this order.

| # | Monument | Value | Different spices required |
|---|---|---|---|
| 1 | Stone Well | 6 | 1 |
| 2 | Caravanserai | 9 | 2 |
| 3 | Grand Souk | 14 | 2 |
| 4 | Watchtower | 21 | 3 |
| 5 | Hammam | 30 | 3 |
| 6 | Date Palm Garden | 41 | 4 |
| 7 | Sultan's Palace | 54 | 4 |

The cost of the n-th monument is `n² + 5`, so each one costs more than the last by a growing step. A player who finishes all seven may build more Sultan's Palace wings on the same formula (69, 86, 105, …).

## 7. End of the game

After the final hour, the player with the most monuments wins. A tie goes to the higher total value of spice in stock. If that is also tied, the game is a draw.

### Game lengths

| Variant | Hours | Actions each | Submissions |
|---|---|---|---|
| Short | 12 | 12 | 24 |
| Day | 24 | 24 | 48 |

The rules, board and costs are the same in every variant; only the final hour changes. The numbers in this document are aimed at the 12-hour game.

### What the bot simulation shows

The engine is in `games/spiceroad`, and `SpiceSimulation` (a JVM test) writes its reports to `games/spiceroad/build/`. The bots are simple rule-followers and do not use the exchange, so treat these as rough.

| Measure | 12 hours | 24 hours |
|---|---|---|
| Monuments per player, rule-following bots | 3–4 | 7–11 |
| Best of 60,000 random solo lines | 4 | 10 |
| Same, never claiming the Royal tile | 4 | 9 |
| Spice left unspent at the end (value) | 35–50 | 140–350 |

The first numbers in this document (loads of 2, monuments at 8, 12, 16, …) gave 2–3 monuments in 12 hours with most games drawn, so loads went up to 3 and monument costs were changed to the formula in §6.

## 8. Fit with the platform

- **Turn submission:** one atomic submission by the active player.

```kotlin
data class SpiceTurn(
    val exchanges: List<Exchange>,    // applied first
    val action: SpiceAction,          // Explore(tileId) | HireCaravan | BiggerLoads | FasterCamels | BuildMonument
    val payment: Map<Spice, Int>,     // empty for Explore, except the Royal tribute
    val assignments: List<TileId?>    // one entry per caravan; applies when that caravan is next at home
)
```

- **Game length:** two `configVariants` (`hours12`, `hours24`); the config JSON carries the final hour.
- **Randomness:** only the initial layout, from the match seed. Nothing is rolled during play.
- **Hidden tiles:** the full layout is in the server state. `publicStateJson` must strip face-down tiles. They are hidden from both players equally, the same way the dice seed is hidden. This needs checking against how clients currently read state.
- **Alternating turns:** `activePlayer` returns the snake order. Caravan movement and deliveries are applied inside `apply` on the second submission of each hour.

## 9. Open questions

- **Q1. Turn order.** The four Precious tiles on the middle line are contested, and whoever acts first in an hour gets there first. The snake order shares that out. Check in the simulation that neither seat wins more often.
- **Q2. The 24-hour game ends with spice nobody can spend.** In the simulation players finish a 24-hour game holding 140–350 value, because income outgrows the one-monument-per-hour limit. Options: steeper costs in the long game, or letting a player build more than one monument in an hour late on. Also, the lobby currently starts every game on the first variant, so the 24-hour game can't be picked in the app yet.
- **Q3. The Royal tile looks fair.** In the simulation, lines that never claim the Royal tile reach the same monument count in 12 hours and one fewer in 24, and a bot that skips it wins about half its games against one that takes it. No change needed for now; recheck once humans have played.
- **Q8. Twelve hours may be too few monuments.** Scores of 3–4 mean many games are decided by the tie-break. Faster early income or cheaper early monuments would spread scores out.
- **Q4. Named recipes.** The variety rule is a simple form of "specific sets". The heavier form is a fixed recipe per monument (for example the Hammam needs 2 Valuable + 1 Precious).
- **Q5. Lost loads.** A caravan still on the road when the final hour ends delivers nothing. Check whether that reads as a fair deadline or as a punishment.
- **Q7. How hard can blocking bite?** Claims must touch a player's own network, so walling someone in takes several explores deep in their territory. Check in the simulation that blocking is a useful nuisance (one or two extra hours on a route) and that fully cutting off a tile is rare. If it is too strong, cap the detour at +2 hours.
- **Q6. Exchange rate.** 2:1 by value is a guess. At 1:1 the variety rule stops mattering; at 3:1 nobody would use the exchange.
