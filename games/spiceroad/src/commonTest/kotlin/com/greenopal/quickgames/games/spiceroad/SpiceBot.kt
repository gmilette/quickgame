package com.greenopal.quickgames.games.spiceroad

class BotStyle(
    val name: String,
    val wantRoyal: Boolean = true,
    val buildFrom: Double = 0.5,
    val upgrades: Boolean = true
)

object SpiceBot {

    fun turn(state: SpiceState, uid: String, style: BotStyle): SpiceTurn {
        val trader = state.traders.getValue(uid)
        val owned = SpiceRules.ownedSources(state, uid)
        val hoursLeft = state.totalHours - state.hour + 1
        val buildPhase = state.hour >= 1 + (state.totalHours * style.buildFrom).toInt()

        fun rate(tile: Tile): Double {
            val trip = SpiceRules.tripHours(state, uid, tile.id) ?: return 0.0
            if (trip > hoursLeft) return 0.0
            return tile.spice!!.price.toDouble() * trader.capacity / trip
        }

        val target = SpiceRules.claimable(state, uid)
            .filter { rate(it) > 0.0 }
            .filter { SpiceRules.claimCost(it) == 0 || (style.wantRoyal && pay(trader, SpiceRules.claimCost(it), 0) != null) }
            .maxByOrNull { rate(it) }

        fun explore(): SpiceTurn? = target?.let {
            SpiceTurn(SpiceContent.EXPLORE, tile = it.id, payment = pay(trader, SpiceRules.claimCost(it), 0)!!)
        }

        fun monument(): SpiceTurn? =
            pay(trader, SpiceContent.monumentCost(trader.monuments), SpiceContent.monumentKinds(trader.monuments))
                ?.let { SpiceTurn(SpiceContent.MONUMENT, payment = it) }

        fun upgrade(action: String): SpiceTurn? {
            if (!style.upgrades || hoursLeft <= 4) return null
            val cost = SpiceRules.upgradeCost(trader, action) ?: return null
            return pay(trader, cost, 0)?.let { SpiceTurn(action, payment = it) }
        }

        val worked = owned.map { rate(it) }.sortedDescending()
        val worstWorked = worked.getOrNull(trader.caravans.size - 1) ?: 0.0
        val betterRoute = target != null && rate(target) > worstWorked

        val choice = (if (buildPhase) monument() else null)
            ?: (if (betterRoute) explore() else null)
            ?: (if (owned.size > trader.caravans.size) upgrade(SpiceContent.HIRE) else null)
            ?: upgrade(SpiceContent.CAMELS)
            ?: upgrade(SpiceContent.LOADS)
            ?: monument()
            ?: explore()
            ?: SpiceTurn(SpiceContent.WAIT)

        return withAssignments(state, uid, choice)
    }

    fun withAssignments(state: SpiceState, uid: String, choice: SpiceTurn): SpiceTurn {
        val trader = state.traders.getValue(uid)
        val hoursLeft = state.totalHours - state.hour + 1
        fun rate(tile: Tile): Double {
            val trip = SpiceRules.tripHours(state, uid, tile.id) ?: return 0.0
            if (trip > hoursLeft) return 0.0
            return tile.spice!!.price.toDouble() / trip
        }
        val claimed = choice.tile?.takeIf { choice.action == SpiceContent.EXPLORE }?.let { state.tiles[it] }
        val caravanCount = trader.caravans.size + if (choice.action == SpiceContent.HIRE) 1 else 0
        val workable = (SpiceRules.ownedSources(state, uid) + listOfNotNull(claimed))
            .filter { rate(it) > 0.0 }
            .sortedByDescending { rate(it) }
            .take(caravanCount)
            .map { it.id }
        val assignments = arrayOfNulls<Int>(caravanCount)
        val unplaced = workable.toMutableList()
        trader.caravans.forEachIndexed { i, caravan ->
            if (caravan.next in unplaced) {
                assignments[i] = caravan.next
                unplaced -= caravan.next!!
            }
        }
        for (i in assignments.indices) {
            if (assignments[i] == null && unplaced.isNotEmpty()) assignments[i] = unplaced.removeAt(0)
        }
        return choice.copy(assignments = assignments.toList())
    }

    fun pay(trader: Trader, cost: Int, kinds: Int): Map<Spice, Int>? = SpiceRules.suggestPayment(trader, cost, kinds)
}
