package com.greenopal.quickgames.games.squad

/**
 * A simple greedy player that only uses its own view, for the balance simulation.
 * [reserve] is the share of each unit's AP it tries to keep for opportunity fire when it moves.
 */
class SquadBot(val name: String, private val reserve: Double = 0.3, private val grenades: Boolean = true) {

    fun turn(view: SquadState, uid: String): SquadTurn {
        val orders = mutableListOf<Order>()
        for (id in view.squad(uid).filter { it.alive }.map { it.id }) {
            val current = SquadRules.preview(view, uid, orders).state
            orders += unitOrders(current, uid, current.unit(id)!!)
        }
        return SquadTurn(orders)
    }

    private fun unitOrders(state: SquadState, uid: String, start: SquadUnit): List<Order> {
        val grid = Grid(state)
        val orders = mutableListOf<Order>()
        var u = start
        fun apply(order: Order): Boolean {
            val preview = SquadRules.preview(state, uid, orders + order)
            if (preview.failed.containsKey(orders.size)) return false
            orders += order
            u = preview.state.unit(u.id)!!
            return true
        }

        val enemies = state.units.filter { it.owner != uid && it.alive }

        // Close combat when an enemy is next to us.
        enemies.firstOrNull { Dir.distance(u.x, u.y, it.x, it.y) == 1 }?.let { adjacent ->
            val dir = Dir.toward(adjacent.x - u.x, adjacent.y - u.y)
            if (u.stats.weapon.snap == null || u.stats.weapon.meleeAccuracy >= 60) {
                while (apply(Order(u.id, OrderType.MELEE, dir = dir))) Unit
                return orders
            }
        }

        // Shoot whatever we can see, turning to face it first if needed.
        val target = enemies
            .filter { Vision.clear(grid, u.x, u.y, it.x, it.y) }
            .minByOrNull { Dir.distance(u.x, u.y, it.x, it.y) }
        if (target != null && u.stats.weapon.snap != null) {
            if (!Vision.canTarget(grid, u, target)) apply(Order(u.id, OrderType.FACE, dir = Dir.toward(target.x - u.x, target.y - u.y)))
            if (Vision.canTarget(grid, u, target)) {
                if (grenades && u.grenades > 0 && Dir.distance(u.x, u.y, target.x, target.y) in 2..SquadContent.THROW_RANGE) {
                    apply(Order(u.id, OrderType.THROW, x = target.x, y = target.y))
                }
                var shots = 0
                while (shots < 3) {
                    val type = when {
                        u.ap >= (SquadRules.shotCost(u, OrderType.AIM) ?: Int.MAX_VALUE) -> OrderType.AIM
                        else -> OrderType.SNAP
                    }
                    if (!apply(Order(u.id, type, target = target.id))) break
                    shots += 1
                }
                return orders
            }
        }

        // Otherwise head for the objective, keeping some AP back.
        val goal = goal(state, uid, u) ?: return orders
        if (goal.second != null) {
            apply(Order(u.id, OrderType.SABOTAGE, dir = goal.second!!))
            return orders
        }
        val (gx, gy) = goal.first
        val route = bestRoute(state, u, gx, gy)
        val budget = u.ap - (u.stats.ap * reserve).toInt()
        var spent = 0
        if (route != null) {
            for (dir in route.dirs) {
                val cost = SquadRules.moveCost(grid, u, dir)
                if (spent + cost > budget) break
                if (!apply(Order(u.id, OrderType.MOVE, dir = dir))) break
                spent += cost
            }
        }
        // Face the most likely threat.
        val threat = enemies.minByOrNull { Dir.distance(u.x, u.y, it.x, it.y) }?.let { it.x to it.y }
            ?: state.lastSeen[uid]?.maxByOrNull { it.turn }?.let { it.x to it.y }
            ?: defaultThreat(state, uid)
        val facing = Dir.toward(threat.first - u.x, threat.second - u.y)
        if (facing != u.facing && (threat.first != u.x || threat.second != u.y)) apply(Order(u.id, OrderType.FACE, dir = facing))
        return orders
    }

    private fun defaultThreat(state: SquadState, uid: String): Pair<Int, Int> =
        if (state.players[0] == uid) 4 to 0 else 4 to 10

    /** Where to go, or (here, dir) to sabotage an adjacent core. */
    private fun goal(state: SquadState, uid: String, u: SquadUnit): Pair<Pair<Int, Int>, Int?>? {
        if (state.mode == SquadContent.SKIRMISH) return (4 to 5) to null
        if (uid != state.attacker()) return null
        val cores = state.cores.filter { !it.destroyed }
        for (dir in 0 until 8) {
            if (cores.any { it.x == u.x + Dir.dx[dir] && it.y == u.y + Dir.dy[dir] }) return (u.x to u.y) to dir
        }
        val core = cores.minByOrNull { Dir.distance(u.x, u.y, it.x, it.y) } ?: return null
        return (core.x to core.y) to null
    }

    /** Cheapest route to the goal square or, if it can't be entered, to a square next to it. */
    private fun bestRoute(state: SquadState, u: SquadUnit, gx: Int, gy: Int): Route? {
        SquadRules.route(state, u, gx, gy)?.let { return it }
        return (0 until 8)
            .mapNotNull { d -> SquadRules.route(state, u, gx + Dir.dx[d], gy + Dir.dy[d]) }
            .minByOrNull { it.cost }
    }
}
