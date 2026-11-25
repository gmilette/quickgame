package com.greenopal.quickgames.games.squad

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Eight directions, 0 = north (up the screen), clockwise. */
object Dir {
    val dx = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
    val dy = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1)

    fun diagonal(dir: Int): Boolean = dir % 2 == 1

    /** Number of 45° steps between two facings, 0..4. */
    fun diff(a: Int, b: Int): Int {
        val d = abs(a - b) % 8
        return min(d, 8 - d)
    }

    /** The direction closest to the vector (dx, dy); 0 for a zero vector. Integer-only, so it is identical on every platform. */
    fun toward(dx: Int, dy: Int): Int {
        if (dx == 0 && dy == 0) return 0
        var best = -1
        var bestDot = 0L
        var bestLen = 1L
        for (d in 0 until 8) {
            val dot = (this.dx[d] * dx + this.dy[d] * dy).toLong()
            if (dot <= 0) continue
            val len = if (diagonal(d)) 2L else 1L
            if (best < 0 || dot * dot * bestLen > bestDot * bestDot * len) {
                best = d
                bestDot = dot
                bestLen = len
            }
        }
        return best
    }

    fun distance(x0: Int, y0: Int, x1: Int, y1: Int): Int = max(abs(x1 - x0), abs(y1 - y0))
}

/** The map with the raid cores' current status. */
class Grid(val rows: List<String>, private val cores: List<Core>) {
    val width: Int = rows[0].length
    val height: Int = rows.size

    constructor(state: SquadState) : this(SquadContent.map(state.mode), state.cores)

    fun inside(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height

    /** Floor, wall or rubble; a destroyed core is rubble, an intact one stays `C`. */
    fun terrain(x: Int, y: Int): Char {
        if (!inside(x, y)) return SquadContent.WALL
        val c = rows[y][x]
        if (c != SquadContent.CORE) return c
        return if (cores.any { it.x == x && it.y == y && it.destroyed }) SquadContent.RUBBLE else SquadContent.CORE
    }

    fun blocksSight(x: Int, y: Int): Boolean = terrain(x, y) == SquadContent.WALL
    fun blocksMove(x: Int, y: Int): Boolean = terrain(x, y).let { it == SquadContent.WALL || it == SquadContent.CORE }
    fun rubble(x: Int, y: Int): Boolean = terrain(x, y) == SquadContent.RUBBLE
}

object Vision {

    /** Within 45° either side of [facing]. Integer-only. */
    fun inArc(facing: Int, fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean {
        val tx = (toX - fromX).toLong()
        val ty = (toY - fromY).toLong()
        if (tx == 0L && ty == 0L) return true
        val dot = Dir.dx[facing] * tx + Dir.dy[facing] * ty
        if (dot <= 0) return false
        val len = if (Dir.diagonal(facing)) 2L else 1L
        return 2 * dot * dot >= len * (tx * tx + ty * ty)
    }

    /**
     * Line of sight between square centres, blocked by walls. The line is sampled four times per
     * square; a sample on a square edge is blocked only when every square it touches is a wall,
     * so a line passing exactly between two diagonal walls gets through.
     */
    fun clear(grid: Grid, x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
        val dx = x1 - x0
        val dy = y1 - y0
        val n = max(abs(dx), abs(dy))
        if (n <= 1) return true
        val scale = 8 * n
        for (i in 1 until 4 * n) {
            val fx = (2 * x0 + 1) * 4 * n + 2 * dx * i
            val fy = (2 * y0 + 1) * 4 * n + 2 * dy * i
            var blocked = true
            for (cx in cells(fx, scale)) for (cy in cells(fy, scale)) {
                val endpoint = (cx == x0 && cy == y0) || (cx == x1 && cy == y1)
                if (endpoint || !grid.blocksSight(cx, cy)) blocked = false
            }
            if (blocked) return false
        }
        return true
    }

    private fun cells(f: Int, scale: Int): List<Int> =
        if (f % scale == 0) listOf(f / scale - 1, f / scale) else listOf(f / scale)

    /** A unit can aim at a square in its arc with a clear line. */
    fun canTargetSquare(grid: Grid, u: SquadUnit, x: Int, y: Int): Boolean =
        inArc(u.facing, u.x, u.y, x, y) && clear(grid, u.x, u.y, x, y)

    /** A unit notices anything next to it, and anything it could aim at. */
    fun seesSquare(grid: Grid, u: SquadUnit, x: Int, y: Int): Boolean =
        Dir.distance(u.x, u.y, x, y) <= 1 || canTargetSquare(grid, u, x, y)

    fun canTarget(grid: Grid, u: SquadUnit, t: SquadUnit): Boolean =
        (u.x != t.x || u.y != t.y) && canTargetSquare(grid, u, t.x, t.y)

    fun sees(grid: Grid, u: SquadUnit, t: SquadUnit): Boolean = seesSquare(grid, u, t.x, t.y)

    /** Ids of living enemies that at least one of [uid]'s living units can see. */
    fun visibleEnemies(grid: Grid, units: List<SquadUnit>, uid: String): Set<Int> {
        val mine = units.filter { it.owner == uid && it.alive }
        return units.filter { it.owner != uid && it.alive && mine.any { m -> sees(grid, m, it) } }.map { it.id }.toSet()
    }

    fun playerSeesSquare(grid: Grid, units: List<SquadUnit>, uid: String, x: Int, y: Int): Boolean =
        units.any { it.owner == uid && it.alive && seesSquare(grid, it, x, y) }

    fun playerSeesUnit(grid: Grid, units: List<SquadUnit>, uid: String, t: SquadUnit): Boolean =
        t.owner == uid || units.any { it.owner == uid && it.alive && sees(grid, it, t) }
}
