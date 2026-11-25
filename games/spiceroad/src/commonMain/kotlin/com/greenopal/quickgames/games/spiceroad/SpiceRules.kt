package com.greenopal.quickgames.games.spiceroad

class TurnOutcome(val state: SpiceState?, val errors: List<String>)

object SpiceRules {

    fun value(units: Map<Spice, Int>): Int = units.entries.sumOf { it.key.price * it.value }

    fun ownedSources(state: SpiceState, uid: String): List<Tile> =
        state.tiles.filter { it.kind == SpiceContent.SOURCE && it.owner == uid }

    fun claimable(state: SpiceState, uid: String): List<Tile> {
        val reach = mutableSetOf<Int>()
        val bases = state.tiles.filter { it.owner == uid }.map { it.id }
        for (base in bases) {
            for (n in SpiceContent.neighbours(base)) {
                reach += n
                if (n == SpiceContent.OASIS) reach += SpiceContent.neighbours(n)
            }
        }
        return state.tiles.filter {
            it.id in reach && it.kind == SpiceContent.SOURCE && it.faceUp && it.owner == null &&
                (it.reservedFor == null || it.reservedFor == uid)
        }
    }

    fun claimCost(tile: Tile): Int = if (tile.spice?.royal == true) SpiceContent.ROYAL_TRIBUTE else 0

    fun routeLength(state: SpiceState, uid: String, target: Int): Int? {
        val home = state.homeOf(uid)
        val dist = mutableMapOf(home to 0)
        val queue = ArrayDeque(listOf(home))
        while (queue.isNotEmpty()) {
            val at = queue.removeFirst()
            if (at == target) return dist.getValue(at)
            for (n in SpiceContent.neighbours(at)) {
                val owner = state.tiles[n].owner
                if (n !in dist && (owner == null || owner == uid)) {
                    dist[n] = dist.getValue(at) + 1
                    queue += n
                }
            }
        }
        return null
    }

    fun tripHours(state: SpiceState, uid: String, target: Int): Int? {
        val length = routeLength(state, uid, target) ?: return null
        return (length - state.traders.getValue(uid).camelLevel).coerceAtLeast(1)
    }

    fun upgradeCost(trader: Trader, action: String): Int? = when (action) {
        SpiceContent.HIRE -> SpiceContent.hireCosts.getOrNull(trader.caravans.size - 1)
        SpiceContent.LOADS -> SpiceContent.loadCosts.getOrNull(trader.loadLevel)
        SpiceContent.CAMELS -> SpiceContent.camelCosts.getOrNull(trader.camelLevel)
        else -> null
    }

    fun cost(state: SpiceState, uid: String, action: String, tile: Int?): Int? {
        val trader = state.traders.getValue(uid)
        return when (action) {
            SpiceContent.EXPLORE -> state.tiles.getOrNull(tile ?: -1)?.let { claimCost(it) }
            SpiceContent.MONUMENT -> SpiceContent.monumentCost(trader.monuments)
            SpiceContent.WAIT -> 0
            else -> upgradeCost(trader, action)
        }
    }

    fun suggestPayment(trader: Trader, cost: Int, kinds: Int): Map<Spice, Int>? {
        if (cost == 0) return emptyMap()
        val held = trader.stock.filterValues { it > 0 }
        if (held.size < kinds || trader.stockValue() < cost) return null
        val payment = mutableMapOf<Spice, Int>()
        held.keys.sortedBy { it.price }.take(kinds).forEach { payment[it] = 1 }
        val units = held.flatMap { (spice, n) -> List(n - (payment[spice] ?: 0)) { spice } }.sortedBy { it.price }
        for (spice in units) {
            if (value(payment) >= cost) break
            payment[spice] = (payment[spice] ?: 0) + 1
        }
        for (spice in payment.keys.sortedByDescending { it.price }) {
            while (payment.getValue(spice) > 0 &&
                value(payment) - spice.price >= cost &&
                (payment.getValue(spice) > 1 || payment.count { it.value > 0 } > kinds)
            ) {
                payment[spice] = payment.getValue(spice) - 1
            }
        }
        return payment.filterValues { it > 0 }.takeIf { value(it) >= cost && it.size >= kinds }
    }

    fun afterExchanges(trader: Trader, exchanges: List<Exchange>): Trader =
        exchanges.fold(trader) { t, e -> t.copy(stock = add(subtract(t.stock, e.give), e.take)) }

    fun play(state: SpiceState, uid: String, turn: SpiceTurn): TurnOutcome {
        if (state.complete) return fail("gameComplete")
        if (state.activePlayer() != uid) return fail("notActivePlayer")
        var trader = state.traders[uid] ?: return fail("unknownPlayer")
        var tiles = state.tiles

        for (exchange in turn.exchanges) {
            if ((exchange.give.values + exchange.take.values).any { it < 0 }) return fail("negativeUnits")
            if (exchange.give.any { (spice, units) -> units > (trader.stock[spice] ?: 0) }) return fail("exchangeNotInStock")
            if (exchange.take.keys.any { spice -> tiles.none { it.faceUp && it.spice == spice } }) {
                return fail("exchangeSpiceNotRevealed")
            }
            if (value(exchange.give) < SpiceContent.EXCHANGE_RATE * value(exchange.take)) return fail("exchangeTooCheap")
            trader = trader.copy(stock = add(subtract(trader.stock, exchange.give), exchange.take))
        }

        var cost = 0
        var kinds = 0
        when (turn.action) {
            SpiceContent.EXPLORE -> {
                val tile = tiles.getOrNull(turn.tile ?: -1) ?: return fail("tileOutOfRange")
                if (claimable(state, uid).none { it.id == tile.id }) return fail("tileNotClaimable")
                cost = claimCost(tile)
                tiles = reveal(tiles.map { if (it.id == tile.id) it.copy(owner = uid) else it }, tile.id)
            }
            SpiceContent.HIRE, SpiceContent.LOADS, SpiceContent.CAMELS -> {
                cost = upgradeCost(trader, turn.action) ?: return fail("upgradeMaxed")
                trader = when (turn.action) {
                    SpiceContent.HIRE -> trader.copy(caravans = trader.caravans + Caravan())
                    SpiceContent.LOADS -> trader.copy(loadLevel = trader.loadLevel + 1)
                    else -> trader.copy(camelLevel = trader.camelLevel + 1)
                }
            }
            SpiceContent.MONUMENT -> {
                cost = SpiceContent.monumentCost(trader.monuments)
                kinds = SpiceContent.monumentKinds(trader.monuments)
                trader = trader.copy(monuments = trader.monuments + 1)
            }
            SpiceContent.WAIT -> Unit
            else -> return fail("unknownAction")
        }

        val payment = turn.payment.filterValues { it != 0 }
        if (payment.values.any { it < 0 }) return fail("negativeUnits")
        if (payment.any { (spice, units) -> units > (trader.stock[spice] ?: 0) }) return fail("paymentNotInStock")
        if (cost == 0 && payment.isNotEmpty()) return fail("paymentNotOwed")
        if (value(payment) < cost) return fail("paymentTooSmall")
        if (payment.size < kinds) return fail("paymentNeedsMoreSpices")
        trader = trader.copy(stock = subtract(trader.stock, payment))

        if (turn.assignments.isNotEmpty()) {
            if (turn.assignments.size != trader.caravans.size) return fail("assignmentCountMismatch")
            val targets = turn.assignments.filterNotNull()
            if (targets.size != targets.toSet().size) return fail("tileAssignedTwice")
            if (targets.any { id -> tiles.getOrNull(id)?.let { it.kind == SpiceContent.SOURCE && it.owner == uid } != true }) {
                return fail("assignmentNotOwned")
            }
            trader = trader.copy(caravans = trader.caravans.mapIndexed { i, c -> c.copy(next = turn.assignments[i]) })
        }

        var next = state.copy(tiles = tiles, traders = state.traders + (uid to trader))
        next = depart(next, uid)
        next = if (state.turnInHour == 0) next.copy(turnInHour = 1) else endHour(next)
        return TurnOutcome(next, emptyList())
    }

    private fun fail(error: String) = TurnOutcome(null, listOf(error))

    private fun reveal(tiles: List<Tile>, claimed: Int): List<Tile> {
        val shown = mutableSetOf<Int>()
        for (n in SpiceContent.neighbours(claimed)) {
            shown += n
            if (n == SpiceContent.OASIS) shown += SpiceContent.neighbours(n)
        }
        return tiles.map { if (it.id in shown && !it.faceUp) it.copy(faceUp = true) else it }
    }

    private fun depart(state: SpiceState, uid: String): SpiceState {
        val trader = state.traders.getValue(uid)
        val caravans = trader.caravans.map { caravan ->
            val hours = caravan.next?.takeIf { caravan.target == null }?.let { tripHours(state, uid, it) }
            if (hours == null) caravan else caravan.copy(target = caravan.next, hoursLeft = hours)
        }
        return state.copy(traders = state.traders + (uid to trader.copy(caravans = caravans)))
    }

    private fun endHour(state: SpiceState): SpiceState {
        val deliveries = mutableListOf<Delivery>()
        var next = state
        for (uid in state.players) {
            var trader = next.traders.getValue(uid)
            val caravans = trader.caravans.map { caravan ->
                val target = caravan.target ?: return@map caravan
                if (caravan.hoursLeft > 1) return@map caravan.copy(hoursLeft = caravan.hoursLeft - 1)
                val spice = state.tiles[target].spice!!
                deliveries += Delivery(uid, target, spice, trader.capacity)
                trader = trader.copy(stock = add(trader.stock, mapOf(spice to trader.capacity)))
                caravan.copy(target = null, hoursLeft = 0)
            }
            next = next.copy(traders = next.traders + (uid to trader.copy(caravans = caravans)))
            next = depart(next, uid)
        }
        next = next.copy(hour = state.hour + 1, turnInHour = 0, lastDeliveries = deliveries)
        if (next.hour > next.totalHours) {
            next = next.copy(complete = true, winner = winner(next))
        }
        return next
    }

    private fun winner(state: SpiceState): String? {
        val (a, b) = state.players
        val ta = state.traders.getValue(a)
        val tb = state.traders.getValue(b)
        val diff = compareValuesBy(ta, tb, { it.monuments }, { it.stockValue() })
        return when {
            diff > 0 -> a
            diff < 0 -> b
            else -> null
        }
    }

    private fun add(stock: Map<Spice, Int>, units: Map<Spice, Int>): Map<Spice, Int> =
        (stock.keys + units.keys).associateWith { (stock[it] ?: 0) + (units[it] ?: 0) }.filterValues { it != 0 }

    private fun subtract(stock: Map<Spice, Int>, units: Map<Spice, Int>): Map<Spice, Int> =
        add(stock, units.mapValues { -it.value })
}
