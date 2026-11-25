package com.greenopal.quickgames.games.hexstead

import com.greenopal.quickgames.core.engine.SeededRng

object BoardGen {
    /**
     * Seven tiles: one of each resource plus two extras of different resources, shuffled. The 6
     * and the 8 always land on ring hexes that do not touch each other.
     */
    fun generate(seed: Long): List<HexTile> {
        val rng = SeededRng(seed)
        val extras = rng.shuffle(Res.all).take(HexBoard.HEX_COUNT - Res.all.size)
        val resources = rng.shuffle(Res.all + extras)
        var numbers = rng.shuffle(Limits.numbers)
        while (!hotNumbersApart(numbers)) numbers = rng.shuffle(Limits.numbers)
        return resources.indices.map { HexTile(resources[it], numbers[it]) }
    }

    fun hotNumbersApart(numbers: List<Int>): Boolean {
        val six = numbers.indexOf(6)
        val eight = numbers.indexOf(8)
        return six != HexBoard.CENTER && eight != HexBoard.CENTER && eight !in HexBoard.ringNeighbors(six)
    }
}

object Rules {

    // ---- Counting ----

    fun roadCount(state: HexsteadState, uid: String): Int = state.roads.values.count { it == uid }

    fun settlementCount(state: HexsteadState, uid: String): Int =
        state.buildings.values.count { it.owner == uid && !it.city }

    fun cityCount(state: HexsteadState, uid: String): Int =
        state.buildings.values.count { it.owner == uid && it.city }

    fun victoryPoints(state: HexsteadState, uid: String): Int {
        val awards = listOf(state.longestRoadHolder, state.mostRaidersHolder).count { it == uid }
        return settlementCount(state, uid) + 2 * cityCount(state, uid) +
            state.players.getValue(uid).monuments + Limits.AWARD_VP * awards
    }

    /** The longest unbroken run of [uid]'s roads. A rival's building on a corner breaks the run. */
    fun longestRoad(state: HexsteadState, uid: String): Int {
        fun extend(vertex: Int, used: Set<Int>): Int {
            val building = state.buildings[vertex]
            if (building != null && building.owner != uid) return 0
            var best = 0
            for (edge in HexBoard.vertexEdges[vertex]) {
                if (state.roads[edge] == uid && edge !in used) {
                    best = maxOf(best, 1 + extend(HexBoard.otherEnd(edge, vertex), used + edge))
                }
            }
            return best
        }
        var best = 0
        for ((edge, owner) in state.roads) {
            if (owner != uid) continue
            val ends = HexBoard.edges[edge]
            best = maxOf(best, 1 + extend(ends.a, setOf(edge)), 1 + extend(ends.b, setOf(edge)))
        }
        return best
    }

    // ---- Where things may be built ----

    private fun roadTouchesNetwork(state: HexsteadState, uid: String, edge: Int): Boolean {
        val ends = HexBoard.edges[edge]
        return listOf(ends.a, ends.b).any { vertex ->
            val building = state.buildings[vertex]
            when {
                building != null -> building.owner == uid
                else -> HexBoard.vertexEdges[vertex].any { it != edge && state.roads[it] == uid }
            }
        }
    }

    fun legalRoadEdges(state: HexsteadState, uid: String): List<Int> =
        if (roadCount(state, uid) >= Limits.ROADS) {
            emptyList()
        } else {
            HexBoard.edges.indices.filter { it !in state.roads && roadTouchesNetwork(state, uid, it) }
        }

    /** After setup a settlement may go on any empty corner that one of your roads reaches. */
    fun legalSettlementVertices(state: HexsteadState, uid: String): List<Int> =
        if (settlementCount(state, uid) >= Limits.SETTLEMENTS) {
            emptyList()
        } else {
            HexBoard.vertices.indices.filter { vertex ->
                vertex !in state.buildings && HexBoard.vertexEdges[vertex].any { state.roads[it] == uid }
            }
        }

    fun legalCityVertices(state: HexsteadState, uid: String): List<Int> =
        if (cityCount(state, uid) >= Limits.CITIES) {
            emptyList()
        } else {
            state.buildings.filter { (_, building) -> building.owner == uid && !building.city }.keys.sorted()
        }

    fun setupVertices(state: HexsteadState): List<Int> =
        HexBoard.vertices.indices.filter { it !in state.buildings }

    fun setupEdges(state: HexsteadState, vertex: Int): List<Int> =
        HexBoard.vertexEdges[vertex].filter { it !in state.roads }

    // ---- Validation ----

    fun errors(state: HexsteadState, action: Action, uid: String): List<String> {
        if (state.phase == Phase.COMPLETE) return listOf("gameComplete")
        if (state.activePlayer() != uid) return listOf("notActivePlayer")
        if (state.phase == Phase.SETUP) return setupErrors(state, action)
        val player = state.players.getValue(uid)
        val pending = state.pending
        if (pending != null) return pendingErrors(state, pending, action, uid)
        return when (action.type) {
            ActionType.ROAD -> buildList {
                if (action.edge == null || action.edge !in legalRoadEdges(state, uid)) add("illegalRoad")
                if (!player.canAfford(Costs.road)) add("cannotAfford")
            }
            ActionType.SETTLEMENT -> buildList {
                if (action.vertex == null || action.vertex !in legalSettlementVertices(state, uid)) add("illegalSettlement")
                if (!player.canAfford(Costs.settlement)) add("cannotAfford")
            }
            ActionType.CITY -> buildList {
                if (action.vertex == null || action.vertex !in legalCityVertices(state, uid)) add("illegalCity")
                if (!player.canAfford(Costs.city)) add("cannotAfford")
            }
            ActionType.BUY_CARD -> buildList {
                if (state.deck.values.sum() == 0) add("deckEmpty")
                if (!player.canAfford(Costs.card)) add("cannotAfford")
            }
            ActionType.TRADE -> when {
                action.give !in Res.all || action.get !in Res.all -> listOf("invalidResource")
                action.give == action.get -> listOf("sameResource")
                player.count(action.give!!) < Limits.TRADE_RATE -> listOf("cannotAfford")
                else -> emptyList()
            }
            ActionType.END_TURN -> emptyList()
            ActionType.CHOOSE -> listOf("noPendingChoice")
            ActionType.SETUP -> listOf("setupOver")
            else -> listOf("unknownAction")
        }
    }

    private fun setupErrors(state: HexsteadState, action: Action): List<String> {
        if (action.type != ActionType.SETUP) return listOf("setupRequired")
        val vertex = action.vertex
        val edge = action.edge
        return buildList {
            when {
                vertex == null || vertex !in HexBoard.vertices.indices -> add("vertexOutOfRange")
                vertex in state.buildings -> add("vertexTaken")
            }
            when {
                edge == null || edge !in HexBoard.edges.indices -> add("edgeOutOfRange")
                edge in state.roads -> add("edgeTaken")
                vertex != null && HexBoard.edges[edge].let { it.a != vertex && it.b != vertex } -> add("edgeNotAdjacent")
            }
        }
    }

    private fun pendingErrors(state: HexsteadState, pending: Pending, action: Action, uid: String): List<String> =
        when (pending.kind) {
            PendingKind.FREE_ROADS -> when {
                action.type != ActionType.ROAD -> listOf("pendingChoice")
                action.edge == null || action.edge !in legalRoadEdges(state, uid) -> listOf("illegalRoad")
                else -> emptyList()
            }
            else -> when {
                action.type != ActionType.CHOOSE -> listOf("pendingChoice")
                action.resources.size != pending.count -> listOf("wrongChoiceCount")
                action.resources.any { it !in Res.all } -> listOf("invalidResource")
                else -> emptyList()
            }
        }

    // ---- Applying a valid action ----

    fun apply(state: HexsteadState, action: Action, uid: String, rng: SeededRng): HexsteadState {
        val next = when {
            state.phase == Phase.SETUP -> applySetup(state, action, uid, rng)
            state.pending != null -> applyPending(state, state.pending, action, uid)
            else -> when (action.type) {
                ActionType.ROAD -> placeRoad(pay(state, uid, Costs.road), uid, action.edge!!)
                ActionType.SETTLEMENT -> placeSettlement(pay(state, uid, Costs.settlement), uid, action.vertex!!)
                ActionType.CITY -> placeCity(pay(state, uid, Costs.city), uid, action.vertex!!)
                ActionType.BUY_CARD -> buyCard(pay(state, uid, Costs.card), uid, rng)
                ActionType.TRADE -> trade(state, uid, action.give!!, action.get!!)
                else -> startTurn(state.copy(lastTurnEvents = state.events, events = emptyList()), state.opponentOf(uid), rng)
            }
        }
        return checkWin(next)
    }

    private fun pay(state: HexsteadState, uid: String, cost: Map<String, Int>): HexsteadState =
        state.withPlayer(uid) { it.minus(cost) }

    private fun HexsteadState.withPlayer(uid: String, change: (PlayerState) -> PlayerState): HexsteadState =
        copy(players = players + (uid to change(players.getValue(uid))))

    private fun HexsteadState.withEvent(event: Event): HexsteadState = copy(events = events + event)

    private fun applySetup(state: HexsteadState, action: Action, uid: String, rng: SeededRng): HexsteadState {
        val vertex = action.vertex!!
        val edge = action.edge!!
        var next = state.copy(
            buildings = state.buildings + (vertex to Building(uid, city = false)),
            roads = state.roads + (edge to uid),
            setupIndex = state.setupIndex + 1
        )
        val gains = mutableMapOf<String, Int>()
        val secondSettlement = settlementCount(next, uid) == 2
        if (secondSettlement) {
            for (hex in HexBoard.vertexHexes[vertex]) {
                val resource = state.hexes[hex].resource
                gains[resource] = (gains[resource] ?: 0) + 1
                next = next.withPlayer(uid) { it.plus(resource, 1) }
            }
        }
        next = next.withEvent(
            Event(EventKind.SETUP, uid, spot = vertex, gains = if (gains.isEmpty()) emptyMap() else mapOf(uid to gains))
        )
        if (next.setupIndex < Limits.setupSeats.size) return next
        return startTurn(
            next.copy(phase = Phase.MAIN, lastTurnEvents = next.events, events = emptyList()),
            state.order[0],
            rng
        )
    }

    /** Hands the turn to [uid] and rolls the dice for them. */
    private fun startTurn(state: HexsteadState, uid: String, rng: SeededRng): HexsteadState {
        val dice = listOf(rng.nextInt(6) + 1, rng.nextInt(6) + 1)
        val total = dice.sum()
        var next = state.copy(active = uid, turn = state.turn + 1)
        val gains = mutableMapOf<String, MutableMap<String, Int>>()
        for (hex in state.hexes.indices) {
            val tile = state.hexes[hex]
            if (tile.number != total) continue
            for (vertex in HexBoard.hexVertices[hex]) {
                val building = state.buildings[vertex] ?: continue
                val amount = if (building.city) 2 else 1
                next = next.withPlayer(building.owner) { it.plus(tile.resource, amount) }
                val mine = gains.getOrPut(building.owner) { mutableMapOf() }
                mine[tile.resource] = (mine[tile.resource] ?: 0) + amount
            }
        }
        next = next.withEvent(Event(EventKind.ROLL, uid, dice = dice, count = total, gains = gains))
        if (total == 7) {
            for (player in state.order) {
                val held = next.players.getValue(player).total()
                if (held <= Limits.HAND_LIMIT) continue
                val lost = mutableMapOf<String, Int>()
                repeat(held / 2) {
                    val cards = next.players.getValue(player).cards()
                    val resource = cards[rng.nextInt(cards.size)]
                    next = next.withPlayer(player) { it.plus(resource, -1) }
                    lost[resource] = (lost[resource] ?: 0) + 1
                }
                next = next.withEvent(Event(EventKind.DISCARD, player, count = held / 2, gains = mapOf(player to lost)))
            }
        }
        return next
    }

    private fun placeRoad(state: HexsteadState, uid: String, edge: Int): HexsteadState =
        updateLongestRoad(state.copy(roads = state.roads + (edge to uid)).withEvent(Event(EventKind.ROAD, uid, spot = edge)))

    private fun placeSettlement(state: HexsteadState, uid: String, vertex: Int): HexsteadState =
        updateLongestRoad(
            state.copy(buildings = state.buildings + (vertex to Building(uid, city = false)))
                .withEvent(Event(EventKind.SETTLEMENT, uid, spot = vertex))
        )

    private fun placeCity(state: HexsteadState, uid: String, vertex: Int): HexsteadState =
        state.copy(buildings = state.buildings + (vertex to Building(uid, city = true)))
            .withEvent(Event(EventKind.CITY, uid, spot = vertex))

    private fun trade(state: HexsteadState, uid: String, give: String, get: String): HexsteadState =
        state.withPlayer(uid) { it.plus(give, -Limits.TRADE_RATE).plus(get, 1) }
            .withEvent(Event(EventKind.TRADE, uid, given = give, resource = get))

    private fun buyCard(state: HexsteadState, uid: String, rng: SeededRng): HexsteadState {
        val remaining = Cards.all.flatMap { card -> List(state.deck[card] ?: 0) { card } }
        val card = remaining[rng.nextInt(remaining.size)]
        val next = state.copy(deck = state.deck + (card to state.deck.getValue(card) - 1))
        return when (card) {
            Cards.RAIDER -> {
                val rival = state.opponentOf(uid)
                val cards = next.players.getValue(rival).cards()
                val stolen = if (cards.isEmpty()) null else cards[rng.nextInt(cards.size)]
                var after = next.withPlayer(uid) { it.copy(raiders = it.raiders + 1) }
                if (stolen != null) {
                    after = after.withPlayer(rival) { it.plus(stolen, -1) }.withPlayer(uid) { it.plus(stolen, 1) }
                }
                updateMostRaiders(after.withEvent(Event(EventKind.CARD, uid, card = card, resource = stolen)), uid)
            }
            Cards.MONUMENT ->
                next.withPlayer(uid) { it.copy(monuments = it.monuments + 1) }
                    .withEvent(Event(EventKind.CARD, uid, card = card))
            Cards.ROAD_CREW -> {
                val roadsLeft = Limits.ROADS - roadCount(next, uid)
                val free = if (legalRoadEdges(next, uid).isEmpty()) 0 else minOf(2, roadsLeft)
                next.copy(pending = if (free > 0) Pending(PendingKind.FREE_ROADS, free) else null)
                    .withEvent(Event(EventKind.CARD, uid, card = card, count = free))
            }
            Cards.WINDFALL ->
                next.copy(pending = Pending(PendingKind.WINDFALL, 2)).withEvent(Event(EventKind.CARD, uid, card = card))
            else ->
                next.copy(pending = Pending(PendingKind.LEVY, 1)).withEvent(Event(EventKind.CARD, uid, card = card))
        }
    }

    private fun applyPending(state: HexsteadState, pending: Pending, action: Action, uid: String): HexsteadState =
        when (pending.kind) {
            PendingKind.FREE_ROADS -> {
                val placed = placeRoad(state, uid, action.edge!!)
                val left = pending.count - 1
                val more = left > 0 && legalRoadEdges(placed, uid).isNotEmpty()
                placed.copy(pending = if (more) Pending(PendingKind.FREE_ROADS, left) else null)
            }
            PendingKind.WINDFALL -> {
                var next = state.copy(pending = null)
                val gains = mutableMapOf<String, Int>()
                for (resource in action.resources) {
                    next = next.withPlayer(uid) { it.plus(resource, 1) }
                    gains[resource] = (gains[resource] ?: 0) + 1
                }
                next.withEvent(Event(EventKind.CHOICE, uid, card = Cards.WINDFALL, gains = mapOf(uid to gains)))
            }
            else -> {
                val resource = action.resources.first()
                val rival = state.opponentOf(uid)
                val taken = state.players.getValue(rival).count(resource)
                state.copy(pending = null)
                    .withPlayer(rival) { it.plus(resource, -taken) }
                    .withPlayer(uid) { it.plus(resource, taken) }
                    .withEvent(Event(EventKind.CHOICE, uid, card = Cards.LEVY, resource = resource, count = taken))
            }
        }

    // ---- Awards and winning ----

    /**
     * The holder keeps the award while nobody is strictly longer and they still have the minimum.
     * Otherwise it goes to whoever is alone at the top, or to nobody.
     */
    private fun updateLongestRoad(state: HexsteadState): HexsteadState {
        val lengths = state.order.associateWith { longestRoad(state, it) }
        val top = lengths.values.max()
        val holder = state.longestRoadHolder
        val newHolder = when {
            top < Limits.ROAD_AWARD_MIN -> null
            holder != null && lengths.getValue(holder) == top -> holder
            else -> lengths.filterValues { it == top }.keys.singleOrNull()
        }
        if (newHolder == holder) return state
        val next = state.copy(longestRoadHolder = newHolder)
        return if (newHolder == null) next else next.withEvent(Event(EventKind.AWARD, newHolder, card = Award.LONGEST_ROAD))
    }

    private fun updateMostRaiders(state: HexsteadState, uid: String): HexsteadState {
        val mine = state.players.getValue(uid).raiders
        val holder = state.mostRaidersHolder
        val holderCount = holder?.let { state.players.getValue(it).raiders } ?: 0
        if (holder == uid || mine < Limits.RAIDER_AWARD_MIN || mine <= holderCount) return state
        return state.copy(mostRaidersHolder = uid).withEvent(Event(EventKind.AWARD, uid, card = Award.MOST_RAIDERS))
    }

    /** You can only win on your own turn. */
    private fun checkWin(state: HexsteadState): HexsteadState {
        if (state.phase != Phase.MAIN) return state
        val uid = state.active
        if (victoryPoints(state, uid) < Limits.WIN_VP) return state
        return state.copy(phase = Phase.COMPLETE, winner = uid, pending = null).withEvent(Event(EventKind.WIN, uid))
    }
}
