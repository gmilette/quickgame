package com.greenopal.quickgames.games.squad

import com.greenopal.quickgames.core.engine.EngineValidation
import com.greenopal.quickgames.core.engine.SeededRng
import com.greenopal.quickgames.core.model.PlayerRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SquadRulesTest {

    private val a = "a"
    private val b = "b"

    private fun unit(id: Int, owner: String, x: Int, y: Int, facing: Int, kind: String = "TROOPER", ap: Int? = null, health: Int? = null): SquadUnit {
        val k = SquadContent.kind(kind)
        return SquadUnit(id, owner, kind, "U$id", x, y, facing, health ?: k.health, ap ?: k.ap, k.grenades)
    }

    private fun state(mode: String, vararg units: SquadUnit): SquadState =
        SquadDefinition.initialState(mode, listOf(a, b)).copy(
            units = units.toList(),
            remaining = listOf(a, b).associateWith { p -> units.count { it.owner == p } }
        )

    private fun play(state: SquadState, vararg orders: Order, seed: Long = 1): SquadState =
        SquadRules.play(state, state.activePlayer(), SquadTurn(orders.toList()), SeededRng(seed))

    private fun move(unit: Int, dir: Int) = Order(unit, OrderType.MOVE, dir = dir)

    @Test
    fun movingCostsApByDirectionTerrainAndTurning() {
        val s = state(SquadContent.SKIRMISH, unit(0, a, 0, 8, 0), unit(1, a, 3, 8, 2), unit(9, b, 8, 0, 0))
        val preview = SquadRules.preview(
            s, a,
            listOf(
                move(0, 3),                       // turn 3 steps + diagonal 6 = 9
                move(0, 2),                       // (2,9) is a wall
                Order(0, OrderType.FACE, dir = 6), // turn 3 steps from SE to W
                move(1, 2)                        // onto rubble: 4 + 2
            )
        )
        val mover = preview.state.unit(0)!!
        assertEquals(1 to 9, mover.x to mover.y)
        assertEquals(36 - 9 - 3, mover.ap)
        assertEquals(6, mover.facing)
        assertEquals(mapOf(1 to "blocked"), preview.failed)
        assertEquals(36 - 6, preview.state.unit(1)!!.ap)
    }

    @Test
    fun visionArcAndWalls() {
        val grid = Grid(SquadContent.skirmishMap, emptyList())
        assertFalse(Vision.clear(grid, 0, 1, 3, 1), "walls block sight")
        assertTrue(Vision.clear(grid, 0, 0, 5, 0), "rubble does not block sight")
        assertTrue(Vision.inArc(0, 4, 8, 4, 5))
        assertTrue(Vision.inArc(0, 4, 8, 5, 7), "45 degrees is inside the arc")
        assertFalse(Vision.inArc(0, 4, 8, 6, 7))
        assertFalse(Vision.inArc(0, 4, 8, 4, 9), "nothing behind")
    }

    @Test
    fun armourSideDependsOnWhereTheAttackComesFrom() {
        val target = unit(0, a, 4, 4, 0)
        assertEquals(0, SquadRules.side(target, 4, 2))
        assertEquals(0, SquadRules.side(target, 5, 3))
        assertEquals(1, SquadRules.side(target, 6, 4))
        assertEquals(2, SquadRules.side(target, 4, 6))
    }

    @Test
    fun viewsHideEnemiesNobodyCanSee() {
        val hidden = state(SquadContent.SKIRMISH, unit(0, a, 0, 8, 0), unit(1, b, 8, 8, 0))
        val view = SquadRules.view(hidden, a)
        assertEquals(listOf(0), view.units.map { it.id })
        assertEquals(a, view.viewer)
        assertTrue(SquadRules.publicView(hidden).units.isEmpty())

        val seen = state(SquadContent.SKIRMISH, unit(0, a, 0, 8, 0), unit(1, b, 0, 4, 4))
        val enemy = SquadRules.view(seen, a).unit(1)
        assertNotNull(enemy)
        assertEquals(0, enemy.ap, "enemy AP is not shown")
    }

    @Test
    fun opportunityFireNeedsApLeft() {
        val watching = state(SquadContent.SKIRMISH, unit(0, a, 8, 6, 0), unit(1, b, 8, 0, 4))
        val after = play(watching, move(0, 0))
        val shot = after.events.single { it.kind == EventKind.SHOT }
        assertTrue(shot.reaction)
        assertEquals(1, shot.unit)
        assertEquals(after.unit(1)!!.stats.ap, after.unit(1)!!.ap, "watcher's AP is refilled for its own turn")

        val spent = state(SquadContent.SKIRMISH, unit(0, a, 8, 6, 0), unit(1, b, 8, 0, 4, ap = 0))
        assertTrue(play(spent, move(0, 0)).events.none { it.kind == EventKind.SHOT })
    }

    @Test
    fun aUnitHaltsWhenItSpotsANewEnemy() {
        val s = state(SquadContent.RAID, unit(0, a, 4, 9, 0), unit(1, b, 4, 5, 4, ap = 0))
        assertTrue(SquadRules.view(s, a).unit(1) == null)
        val after = play(s, move(0, 6), move(0, 0), move(0, 0), Order(0, OrderType.FACE, dir = 2))
        val mover = after.unit(0)!!
        assertEquals(3 to 8, mover.x to mover.y, "stopped on the square where the enemy came into view")
        assertEquals(1, after.events.count { it.kind == EventKind.HALT })
        assertEquals(0, mover.facing, "orders after the halt are dropped")
        assertTrue(SquadRules.view(after, a).unit(1) != null)
    }

    @Test
    fun ordersThatCannotRunAreSkippedAndTheRestContinue() {
        val s = state(SquadContent.SKIRMISH, unit(0, a, 0, 8, 0), unit(1, a, 3, 8, 0), unit(2, b, 8, 0, 0))
        val after = play(s, move(0, 3), move(0, 2), Order(0, OrderType.SNAP, target = 2), move(1, 0))
        val skips = after.events.filter { it.kind == EventKind.SKIP }.map { it.reason }
        assertEquals(listOf("blocked", "notInView"), skips)
        assertEquals(3 to 7, after.unit(1)!!.let { it.x to it.y })
    }

    @Test
    fun grenadesExplodeAtTheEndOfTheTurn() {
        val s = state(SquadContent.RAID, unit(0, a, 3, 9, 0), unit(1, b, 3, 6, 0, ap = 0))
        val after = play(s, Order(0, OrderType.THROW, x = 3, y = 6))
        assertEquals(0, after.unit(0)!!.grenades)
        val kinds = after.events.map { it.kind }
        assertTrue(kinds.indexOf(EventKind.BLAST) > kinds.indexOf(EventKind.THROW))
    }

    @Test
    fun eliminatingTheLastEnemyWinsTheSkirmish() {
        var kills = 0
        for (seed in 1L..20L) {
            val s = state(SquadContent.SKIRMISH, unit(0, a, 8, 6, 0), unit(1, b, 8, 3, 4, ap = 0, health = 1))
            val after = play(s, Order(0, OrderType.AIM, target = 1), seed = seed)
            if (!after.unit(1)!!.alive) {
                kills += 1
                assertTrue(after.complete)
                assertEquals(a, after.winner)
                assertEquals(SquadContent.KILL_POINTS, after.scores[a])
                assertEquals(0, after.remaining[b])
            }
        }
        assertTrue(kills > 10, "aimed shots at close range mostly hit")
    }

    @Test
    fun destroyingTwoCoresWinsTheRaid() {
        val s = state(SquadContent.RAID, unit(0, a, 3, 1, 0), unit(1, a, 5, 1, 0), unit(2, b, 0, 10, 0, ap = 0))
        val after = play(s, Order(0, OrderType.SABOTAGE, dir = 6), Order(1, OrderType.SABOTAGE, dir = 6))
        assertEquals(2, after.cores.count { it.destroyed })
        assertTrue(after.complete)
        assertEquals(a, after.winner)
        assertEquals(SquadContent.RUBBLE, Grid(after).terrain(2, 1))
    }

    @Test
    fun defendersWinTheRaidWhenTimeRunsOut() {
        val s = state(SquadContent.RAID, unit(0, a, 0, 10, 0), unit(1, b, 4, 3, 4)).copy(turn = 23)
        assertEquals(b, s.activePlayer(), "the last turn belongs to the defender")
        val after = play(s)
        assertTrue(after.complete)
        assertEquals(b, after.winner)
    }

    @Test
    fun holdingTheCentreScores() {
        val s = state(SquadContent.SKIRMISH, unit(0, a, 4, 5, 0), unit(1, b, 8, 0, 4))
        val after = play(s)
        assertEquals(SquadContent.ZONE_POINTS, after.scores[a])
        val contested = play(state(SquadContent.SKIRMISH, unit(0, a, 4, 5, 0), unit(1, b, 3, 4, 4)))
        assertEquals(0, contested.scores[a])
    }

    @Test
    fun theOpponentNeverSeesHiddenEvents() {
        val s = state(SquadContent.SKIRMISH, unit(0, a, 0, 8, 0), unit(1, b, 8, 0, 0))
        val after = play(s, move(0, 0))
        assertTrue(after.events.any { it.kind == EventKind.MOVE })
        val bView = SquadRules.view(after, b)
        assertTrue(bView.events.none { it.kind == EventKind.MOVE })
        assertTrue(bView.units.none { it.owner == a })
        assertTrue(SquadRules.view(after, a).events.any { it.kind == EventKind.MOVE })
    }

    @Test
    fun routesFindTheCheapestPath() {
        val s = state(SquadContent.SKIRMISH, unit(0, a, 1, 10, 0), unit(1, b, 8, 0, 4))
        val route = SquadRules.route(s, s.unit(0)!!, 1, 8)
        assertNotNull(route)
        assertEquals(listOf(0, 0), route.dirs)
        assertEquals(8, route.cost)
        val preview = SquadRules.preview(s, a, route.dirs.map { move(0, it) })
        assertEquals(36 - route.cost, preview.state.unit(0)!!.ap)
        assertNull(SquadRules.route(s, s.unit(0)!!, 2, 9), "walls can't be entered")
    }

    @Test
    fun engineValidatesOwnershipAndBuildsViews() {
        val players = listOf(PlayerRef(a, "A", 0), PlayerRef(b, "B", 1))
        val config = SquadDefinition.configJson(SquadContent.RAID)
        val json = SquadDefinition.initialStateJson(config, players, 7)
        val initial = SquadEngine.decode(json)
        assertEquals(SquadContent.RAID, initial.mode)
        assertEquals(5, initial.remaining[a])
        assertEquals(3, initial.remaining[b])

        val wrong = SquadJson.json.encodeToString(SquadTurn(listOf(move(5, 0))))
        assertEquals(EngineValidation.Invalid(listOf("notYourUnit")), SquadEngine.validate(json, wrong, a, config))
        assertTrue(SquadEngine.validate(json, "{", a, config) is EngineValidation.Invalid)

        val view = SquadEngine.decode(SquadDefinition.playerStateJson(json, b))
        assertTrue(view.units.all { it.owner == b || view.viewer == b })
        assertTrue(SquadEngine.decode(SquadDefinition.publicStateJson(json)).units.isEmpty())
    }
}
