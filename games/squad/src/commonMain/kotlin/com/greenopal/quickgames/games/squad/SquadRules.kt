package com.greenopal.quickgames.games.squad

import com.greenopal.quickgames.core.engine.SeededRng
import kotlin.math.max

/** A path for one unit: the directions to step in, and the AP it costs including turns. */
data class Route(val dirs: List<Int>, val cost: Int)

/** The result of running a player's planned orders without the opponent or dice. */
data class Preview(val state: SquadState, val failed: Map<Int, String>)

object SquadRules {

    // ---- costs and chances (shared by the engine, the screen and the bot) ----

    fun apCost(u: SquadUnit, pct: Int): Int = max(1, u.stats.ap * pct / 100)

    fun turnCost(from: Int, to: Int): Int = Dir.diff(from, to) * SquadContent.TURN_COST

    fun stepCost(grid: Grid, dir: Int, toX: Int, toY: Int): Int =
        (if (Dir.diagonal(dir)) SquadContent.DIAGONAL_COST else SquadContent.MOVE_COST) +
            (if (grid.rubble(toX, toY)) SquadContent.RUBBLE_EXTRA else 0)

    /** A step is allowed onto an open, empty square; a diagonal step can't cut a wall or core corner. */
    fun canStep(grid: Grid, units: List<SquadUnit>, x: Int, y: Int, dir: Int): Boolean {
        val nx = x + Dir.dx[dir]
        val ny = y + Dir.dy[dir]
        if (!grid.inside(nx, ny) || grid.blocksMove(nx, ny)) return false
        if (units.any { it.alive && it.x == nx && it.y == ny }) return false
        if (Dir.diagonal(dir) && (grid.blocksMove(nx, y) || grid.blocksMove(x, ny))) return false
        return true
    }

    fun moveCost(grid: Grid, u: SquadUnit, dir: Int): Int =
        turnCost(u.facing, dir) + stepCost(grid, dir, u.x + Dir.dx[dir], u.y + Dir.dy[dir])

    /** Opportunity fire needs at least half the AP allowance left, and enough for a snap shot. */
    fun canReact(u: SquadUnit): Boolean {
        val snap = shotCost(u, OrderType.SNAP) ?: return false
        return u.alive && u.ap >= snap && u.ap * 100 >= u.stats.ap * SquadContent.REACTION_MIN_PCT
    }

    fun shotCost(u: SquadUnit, type: String): Int? = u.stats.weapon.shot(type)?.let { apCost(u, it.apPct) }
    fun meleeCost(u: SquadUnit): Int = apCost(u, u.stats.weapon.meleePct)
    fun throwCost(u: SquadUnit): Int = apCost(u, SquadContent.THROW_PCT)
    fun sabotageCost(u: SquadUnit): Int = apCost(u, SquadContent.SABOTAGE_PCT)

    /** 0 = front, 1 = side, 2 = rear, seen from the square ([fromX], [fromY]). */
    fun side(target: SquadUnit, fromX: Int, fromY: Int): Int =
        when (Dir.diff(target.facing, Dir.toward(fromX - target.x, fromY - target.y))) {
            0, 1 -> 0
            2 -> 1
            else -> 2
        }

    private fun clampChance(value: Int): Int = value.coerceIn(SquadContent.MIN_CHANCE, SquadContent.MAX_CHANCE)

    fun hitChance(grid: Grid, shooter: SquadUnit, target: SquadUnit, type: String): Int {
        val weapon = shooter.stats.weapon
        val shot = weapon.shot(type) ?: return 0
        val bonus = shooter.stats.skill * weapon.skillFactor / 100
        var chance = shot.accuracy + when (type) {
            OrderType.AIM -> bonus * 2
            OrderType.AUTO -> bonus / 2
            else -> bonus
        }
        val range = Dir.distance(shooter.x, shooter.y, target.x, target.y)
        chance -= SquadContent.RANGE_PENALTY * max(0, range - SquadContent.RANGE_FREE)
        if (grid.rubble(target.x, target.y)) chance -= SquadContent.COVER_ACCURACY
        return clampChance(chance)
    }

    /** The defender's agility counts in full from the front, half from the side, not at all from behind. */
    fun meleeChance(attacker: SquadUnit, defender: SquadUnit, fromX: Int = attacker.x, fromY: Int = attacker.y): Int {
        val agility = defender.stats.agility
        val defence = when (side(defender, fromX, fromY)) {
            0 -> agility / 2
            1 -> agility / 4
            else -> 0
        }
        return clampChance(attacker.stats.weapon.meleeAccuracy + attacker.stats.agility / 2 - defence)
    }

    fun throwChance(u: SquadUnit): Int = clampChance(u.stats.agility)

    // ---- validation ----

    /** Structure and ownership only; anything that depends on how the turn unfolds is checked while it runs. */
    fun errors(state: SquadState, uid: String, turn: SquadTurn): List<String> {
        if (state.complete) return listOf("gameOver")
        if (state.activePlayer() != uid) return listOf("notYourTurn")
        if (turn.orders.size > SquadContent.MAX_ORDERS) return listOf("tooManyOrders")
        val grid = Grid(state)
        val errors = mutableListOf<String>()
        for (order in turn.orders) {
            val unit = state.unit(order.unit)
            when {
                unit == null || unit.owner != uid -> errors += "notYourUnit"
                !unit.alive -> errors += "unitDead"
                order.type !in OrderType.all -> errors += "unknownOrder"
                order.type in OrderType.withDir && order.dir !in 0..7 -> errors += "badDirection"
                order.type == OrderType.THROW && !grid.inside(order.x, order.y) -> errors += "badSquare"
                order.type in OrderType.shots && order.target < 0 -> errors += "badTarget"
            }
        }
        return errors.distinct()
    }

    // ---- running a turn ----

    fun play(state: SquadState, uid: String, turn: SquadTurn, rng: SeededRng): SquadState {
        val sim = Sim(state, uid, rng)
        turn.orders.forEach { sim.run(it) }
        return sim.finishTurn()
    }

    /**
     * Runs [orders] for [uid] on what they can see, with no dice and no enemy reactions: where each
     * unit ends up, its AP left, and which orders can't be carried out as planned.
     */
    fun preview(state: SquadState, uid: String, orders: List<Order>): Preview {
        val sim = Sim(state, uid, null)
        val failed = mutableMapOf<Int, String>()
        orders.forEachIndexed { i, order -> sim.run(order)?.let { failed[i] = it } }
        return Preview(sim.snapshot(), failed)
    }

    // ---- per-player views ----

    fun view(state: SquadState, uid: String): SquadState {
        if (state.complete) {
            return state.copy(viewer = uid, events = state.events.map { it.copy(seenBy = emptyList()) })
        }
        val grid = Grid(state)
        val visible = Vision.visibleEnemies(grid, state.units, uid)
        return state.copy(
            units = state.units
                .filter { it.owner == uid || it.id in visible }
                .map { if (it.owner == uid) it else it.copy(ap = 0) },
            lastSeen = mapOf(uid to (state.lastSeen[uid] ?: emptyList()).filter { it.unit !in visible }),
            events = state.events.filter { uid in it.seenBy }.map { it.copy(seenBy = emptyList()) },
            viewer = uid
        )
    }

    fun publicView(state: SquadState): SquadState =
        state.copy(units = emptyList(), lastSeen = emptyMap(), events = emptyList(), viewer = null)

    // ---- paths ----

    /**
     * The cheapest route (in AP, counting turns) for [u] to reach ([toX], [toY]), treating every unit
     * in [state] as an obstacle. Null if there is no way there.
     */
    fun route(state: SquadState, u: SquadUnit, toX: Int, toY: Int): Route? {
        val grid = Grid(state)
        if (!grid.inside(toX, toY)) return null
        if (u.x == toX && u.y == toY) return Route(emptyList(), 0)
        val others = state.units.filter { it.id != u.id }
        val size = grid.width * grid.height * 8
        fun node(x: Int, y: Int, f: Int) = (y * grid.width + x) * 8 + f
        val dist = IntArray(size) { Int.MAX_VALUE }
        val prev = IntArray(size) { -1 }
        val buckets = mutableMapOf<Int, MutableList<Int>>()
        val startNode = node(u.x, u.y, u.facing)
        dist[startNode] = 0
        buckets.getOrPut(0) { mutableListOf() } += startNode
        var cost = 0
        var pending = 1
        var best = -1
        while (pending > 0 && best < 0) {
            val bucket = buckets.remove(cost)
            if (bucket != null) {
                for (n in bucket) {
                    pending -= 1
                    if (dist[n] != cost) continue
                    val f = n % 8
                    val cell = n / 8
                    val x = cell % grid.width
                    val y = cell / grid.width
                    if (x == toX && y == toY) {
                        best = n
                        break
                    }
                    for (d in 0 until 8) {
                        if (!canStep(grid, others, x, y, d)) continue
                        val nx = x + Dir.dx[d]
                        val ny = y + Dir.dy[d]
                        val next = node(nx, ny, d)
                        val c = cost + turnCost(f, d) + stepCost(grid, d, nx, ny)
                        if (c < dist[next]) {
                            dist[next] = c
                            prev[next] = n
                            buckets.getOrPut(c) { mutableListOf() } += next
                            pending += 1
                        }
                    }
                }
            }
            cost += 1
        }
        if (best < 0) return null
        val dirs = mutableListOf<Int>()
        var n = best
        while (n != startNode) {
            dirs += n % 8
            n = prev[n]
        }
        dirs.reverse()
        return Route(dirs, dist[best])
    }
}

/**
 * Runs one player's orders. With [rng] null it is a preview: AP and positions only, no dice, no
 * events, and no reactions from the opponent.
 */
private class Sim(private val start: SquadState, private val mover: String, private val rng: SeededRng?) {
    private val live = rng != null
    private val turn = start.turn
    private val raid = start.mode == SquadContent.RAID
    private val units = start.units.toMutableList()
    private val cores = start.cores.toMutableList()
    private var grid = Grid(SquadContent.map(start.mode), cores.toList())
    private val scores = start.players.associateWith { start.scores[it] ?: 0 }.toMutableMap()
    private val sightings: Map<String, MutableMap<Int, Sighting>> = start.players.associateWith { p ->
        (start.lastSeen[p] ?: emptyList()).associateBy { it.unit }.toMutableMap()
    }
    private val events = mutableListOf<Event>()
    private val halted = mutableSetOf<Int>()
    private val grenades = mutableListOf<Pair<String, Pair<Int, Int>>>()

    private fun get(id: Int): SquadUnit? = units.firstOrNull { it.id == id }

    private fun put(u: SquadUnit) {
        units[units.indexOfFirst { it.id == u.id }] = u
    }

    private fun seenBy(always: Collection<String>, squares: List<Pair<Int, Int>>): List<String> =
        start.players.filter { p ->
            p in always || squares.any { (x, y) -> Vision.playerSeesSquare(grid, units, p, x, y) }
        }

    private fun record(event: Event) {
        if (live) events += event
    }

    private fun observe() {
        if (!live) return
        for (p in start.players) {
            for (id in Vision.visibleEnemies(grid, units, p)) {
                val u = get(id)!!
                sightings.getValue(p)[id] = Sighting(id, u.x, u.y, turn)
            }
        }
    }

    private fun roll(base: Int): Int = base * (50 + rng!!.nextInt(101)) / 100

    private fun fail(order: Order, reason: String): String {
        if (reason != "halted") {
            record(Event(turn, EventKind.SKIP, unit = order.unit, reason = reason, seenBy = listOf(mover)))
        }
        return reason
    }

    /** Returns why the order could not run, or null if it ran. */
    fun run(order: Order): String? {
        val u = get(order.unit) ?: return "noUnit"
        if (u.owner != mover) return "notYourUnit"
        if (!u.alive) return fail(order, "dead")
        if (u.id in halted) return fail(order, "halted")
        return when (order.type) {
            OrderType.MOVE -> move(u, order)
            OrderType.FACE -> face(u, order)
            in OrderType.shots -> shoot(u, order)
            OrderType.MELEE -> melee(u, order)
            OrderType.THROW -> throwGrenade(u, order)
            OrderType.SABOTAGE -> sabotage(u, order)
            else -> fail(order, "unknownOrder")
        }
    }

    private fun move(u: SquadUnit, order: Order): String? {
        val dir = order.dir
        if (!SquadRules.canStep(grid, units, u.x, u.y, dir)) return fail(order, "blocked")
        val cost = SquadRules.moveCost(grid, u, dir)
        if (u.ap < cost) return fail(order, "noAp")
        val before = if (live) Vision.visibleEnemies(grid, units, mover) else emptySet()
        val moved = u.copy(x = u.x + Dir.dx[dir], y = u.y + Dir.dy[dir], facing = dir, ap = u.ap - cost)
        put(moved)
        record(
            Event(
                turn, EventKind.MOVE, unit = u.id, x = moved.x, y = moved.y, facing = dir, tx = u.x, ty = u.y,
                seenBy = seenBy(listOf(mover), listOf(u.x to u.y, moved.x to moved.y))
            )
        )
        if (live) {
            observe()
            val spotted = Vision.visibleEnemies(grid, units, mover) - before
            if (spotted.isNotEmpty()) {
                halted += u.id
                record(Event(turn, EventKind.HALT, unit = u.id, x = moved.x, y = moved.y, seenBy = listOf(mover)))
            }
            reactionFire(u.id)
            observe()
        }
        return null
    }

    private fun face(u: SquadUnit, order: Order): String? {
        val cost = SquadRules.turnCost(u.facing, order.dir)
        if (u.ap < cost) return fail(order, "noAp")
        val turned = u.copy(facing = order.dir, ap = u.ap - cost)
        put(turned)
        record(
            Event(
                turn, EventKind.TURN, unit = u.id, x = u.x, y = u.y, facing = order.dir,
                seenBy = seenBy(listOf(mover), listOf(u.x to u.y))
            )
        )
        observe()
        return null
    }

    private fun shoot(u: SquadUnit, order: Order): String? {
        val target = get(order.target)?.takeIf { it.alive && it.owner != mover } ?: return fail(order, "noTarget")
        val shot = u.stats.weapon.shot(order.type) ?: return fail(order, "noShot")
        if (!Vision.canTarget(grid, u, target)) return fail(order, "notInView")
        val cost = SquadRules.apCost(u, shot.apPct)
        if (u.ap < cost) return fail(order, "noAp")
        put(u.copy(ap = u.ap - cost))
        if (live) {
            val shots = if (order.type == OrderType.AUTO) SquadContent.AUTO_BURST else 1
            repeat(shots) {
                if (get(target.id)!!.alive) fire(u.id, target.id, order.type, reaction = false)
            }
            observe()
        }
        return null
    }

    private fun fire(shooterId: Int, targetId: Int, type: String, reaction: Boolean) {
        val s = get(shooterId)!!
        val t = get(targetId)!!
        val chance = SquadRules.hitChance(grid, s, t, type)
        val hit = rng!!.nextInt(100) < chance
        val damage = if (hit) {
            val cover = if (grid.rubble(t.x, t.y)) SquadContent.COVER_ARMOUR else 0
            max(0, roll(s.stats.weapon.damage) - t.stats.armour[SquadRules.side(t, s.x, s.y)] - cover)
        } else {
            0
        }
        record(
            Event(
                turn, EventKind.SHOT, unit = s.id, x = s.x, y = s.y, target = t.id, tx = t.x, ty = t.y,
                chance = chance, hit = hit, damage = damage, reaction = reaction,
                seenBy = seenBy(listOf(s.owner, t.owner), listOf(s.x to s.y, t.x to t.y))
            )
        )
        // The target's side sees where the shot came from.
        sightings.getValue(t.owner)[s.id] = Sighting(s.id, s.x, s.y, turn)
        if (damage > 0) hurt(t.id, damage)
    }

    private fun hurt(id: Int, damage: Int) {
        val t = get(id)!!
        val left = max(0, t.health - damage)
        put(t.copy(health = left))
        if (left == 0) {
            record(Event(turn, EventKind.KILL, unit = t.id, x = t.x, y = t.y, seenBy = start.players))
            sightings.values.forEach { it.remove(t.id) }
            if (!raid) {
                val scorer = start.opponentOf(t.owner)
                scores[scorer] = scores.getValue(scorer) + SquadContent.KILL_POINTS
            }
        }
    }

    private fun melee(u: SquadUnit, order: Order): String? {
        val tx = u.x + Dir.dx[order.dir]
        val ty = u.y + Dir.dy[order.dir]
        val target = units.firstOrNull { it.alive && it.x == tx && it.y == ty && it.owner != mover }
            ?: return fail(order, "noTarget")
        val cost = SquadRules.turnCost(u.facing, order.dir) + SquadRules.meleeCost(u)
        if (u.ap < cost) return fail(order, "noAp")
        val attacker = u.copy(facing = order.dir, ap = u.ap - cost)
        put(attacker)
        if (live) {
            val chance = SquadRules.meleeChance(attacker, target)
            val hit = rng!!.nextInt(100) < chance
            val damage = if (hit) {
                max(0, roll(attacker.stats.weapon.meleeDamage) - target.stats.armour[SquadRules.side(target, u.x, u.y)])
            } else {
                0
            }
            record(
                Event(
                    turn, EventKind.MELEE, unit = u.id, x = u.x, y = u.y, facing = order.dir, target = target.id,
                    tx = tx, ty = ty, chance = chance, hit = hit, damage = damage,
                    seenBy = seenBy(listOf(mover, target.owner), listOf(u.x to u.y))
                )
            )
            if (damage > 0) hurt(target.id, damage)
            observe()
        }
        return null
    }

    private fun throwGrenade(u: SquadUnit, order: Order): String? {
        if (u.grenades <= 0) return fail(order, "noGrenade")
        val x = order.x
        val y = order.y
        if (!grid.inside(x, y) || grid.blocksSight(x, y)) return fail(order, "badSquare")
        val distance = Dir.distance(u.x, u.y, x, y)
        if (distance == 0 || distance > SquadContent.THROW_RANGE) return fail(order, "outOfRange")
        if (!Vision.canTargetSquare(grid, u, x, y)) return fail(order, "notInView")
        val cost = SquadRules.throwCost(u)
        if (u.ap < cost) return fail(order, "noAp")
        put(u.copy(ap = u.ap - cost, grenades = u.grenades - 1))
        if (live) {
            val chance = SquadRules.throwChance(u)
            val hit = rng!!.nextInt(100) < chance
            val landing = if (hit) {
                x to y
            } else {
                val options = (0 until 8)
                    .map { (x + Dir.dx[it]) to (y + Dir.dy[it]) }
                    .filter { (nx, ny) -> grid.inside(nx, ny) && !grid.blocksSight(nx, ny) }
                if (options.isEmpty()) x to y else options[rng.nextInt(options.size)]
            }
            record(
                Event(
                    turn, EventKind.THROW, unit = u.id, x = u.x, y = u.y, tx = landing.first, ty = landing.second,
                    chance = chance, hit = hit, seenBy = seenBy(listOf(mover), listOf(u.x to u.y, landing))
                )
            )
            grenades += mover to landing
        }
        return null
    }

    private fun sabotage(u: SquadUnit, order: Order): String? {
        if (!raid || mover != start.attacker()) return fail(order, "noCore")
        val cx = u.x + Dir.dx[order.dir]
        val cy = u.y + Dir.dy[order.dir]
        val index = cores.indexOfFirst { it.x == cx && it.y == cy && !it.destroyed }
        if (index < 0) return fail(order, "noCore")
        val cost = SquadRules.turnCost(u.facing, order.dir) + SquadRules.sabotageCost(u)
        if (u.ap < cost) return fail(order, "noAp")
        put(u.copy(facing = order.dir, ap = u.ap - cost))
        if (live) {
            cores[index] = cores[index].copy(destroyed = true)
            grid = Grid(SquadContent.map(start.mode), cores.toList())
            scores[mover] = scores.getValue(mover) + 1
            record(
                Event(
                    turn, EventKind.SABOTAGE, unit = u.id, x = u.x, y = u.y, facing = order.dir, tx = cx, ty = cy,
                    seenBy = start.players
                )
            )
            observe()
        }
        return null
    }

    /**
     * Every enemy that can see the unit fires one snap shot, if it still has at least half its AP
     * allowance and enough for the shot.
     */
    private fun reactionFire(targetId: Int) {
        val watchers = units.filter { it.alive && it.owner != mover }.map { it.id }.sorted()
        for (id in watchers) {
            if (!get(targetId)!!.alive) return
            val w = get(id)!!
            if (!w.alive) continue
            val snap = w.stats.weapon.snap ?: continue
            val cost = SquadRules.apCost(w, snap.apPct)
            if (!SquadRules.canReact(w) || !Vision.canTarget(grid, w, get(targetId)!!)) continue
            put(w.copy(ap = w.ap - cost))
            fire(id, targetId, OrderType.SNAP, reaction = true)
        }
    }

    private fun explode(owner: String, at: Pair<Int, Int>) {
        val (x, y) = at
        record(Event(turn, EventKind.BLAST, x = x, y = y, seenBy = seenBy(listOf(owner), listOf(at))))
        for (dy in -1..1) for (dx in -1..1) {
            val t = units.firstOrNull { it.alive && it.x == x + dx && it.y == y + dy } ?: continue
            val damage = max(0, roll(SquadContent.GRENADE_DAMAGE) - t.stats.armour[1])
            record(
                Event(
                    turn, EventKind.HURT, unit = t.id, x = t.x, y = t.y, damage = damage,
                    seenBy = seenBy(listOf(owner, t.owner), listOf(t.x to t.y))
                )
            )
            if (damage > 0) hurt(t.id, damage)
        }
    }

    fun snapshot(): SquadState = start.copy(units = units.toList(), cores = cores.toList())

    fun finishTurn(): SquadState {
        val opponent = start.opponentOf(mover)
        for ((owner, at) in grenades) explode(owner, at)

        if (!raid) {
            val mine = units.any { it.alive && it.owner == mover && SquadContent.inZone(it.x, it.y) }
            val theirs = units.any { it.alive && it.owner == opponent && SquadContent.inZone(it.x, it.y) }
            if (mine && !theirs) {
                scores[mover] = scores.getValue(mover) + SquadContent.ZONE_POINTS
                record(Event(turn, EventKind.ZONE, seenBy = start.players))
            }
        }
        observe()

        val nextTurn = turn + 1
        val remaining = start.players.associateWith { p -> units.count { it.alive && it.owner == p } }
        val outcome = outcome(remaining, nextTurn)

        val refreshed = units.map { if (it.alive && it.owner == opponent) it.copy(ap = it.stats.ap) else it }
        return start.copy(
            turn = nextTurn,
            units = refreshed,
            cores = cores.toList(),
            scores = scores.toMap(),
            remaining = remaining,
            lastSeen = sightings.mapValues { (_, seen) -> seen.values.sortedBy { it.unit } },
            events = start.events.filter { it.turn >= turn - 1 } + events,
            complete = outcome != null,
            winner = outcome?.winner
        )
    }

    private class Outcome(val winner: String?)

    private fun outcome(remaining: Map<String, Int>, nextTurn: Int): Outcome? {
        val (a, b) = start.players
        val aLeft = remaining.getValue(a)
        val bLeft = remaining.getValue(b)
        fun higherScore(): String? = when {
            scores.getValue(a) > scores.getValue(b) -> a
            scores.getValue(b) > scores.getValue(a) -> b
            else -> null
        }
        return when {
            aLeft == 0 && bLeft == 0 -> Outcome(null)
            aLeft == 0 -> Outcome(b)
            bLeft == 0 -> Outcome(a)
            raid && scores.getValue(start.attacker()) >= SquadContent.CORES_TO_WIN -> Outcome(start.attacker())
            !raid && scores.values.any { it >= SquadContent.WIN_POINTS } -> Outcome(higherScore())
            nextTurn >= start.maxTurns -> Outcome(if (raid) start.opponentOf(start.attacker()) else higherScore())
            else -> null
        }
    }
}
