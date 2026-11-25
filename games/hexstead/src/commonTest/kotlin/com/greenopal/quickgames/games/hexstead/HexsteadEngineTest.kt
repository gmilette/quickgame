package com.greenopal.quickgames.games.hexstead

import com.greenopal.quickgames.core.engine.EngineValidation
import com.greenopal.quickgames.core.engine.SeededRng
import com.greenopal.quickgames.core.model.GameStatus
import com.greenopal.quickgames.core.model.PlayerRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HexsteadEngineTest {

    private val players = listOf(PlayerRef("a", "A", 0), PlayerRef("b", "B", 1))
    private val config = HexsteadDefinition.configJson("standard")

    private fun decode(stateJson: String): HexsteadState = HexsteadJson.json.decodeFromString(stateJson)
    private fun encode(action: Action): String = HexsteadJson.json.encodeToString(action)
    private fun newGame(seed: Long): HexsteadState = decode(HexsteadDefinition.initialStateJson(config, players, seed))

    private fun errors(state: HexsteadState, action: Action, uid: String): List<String> =
        when (val v = HexsteadEngine.validate(HexsteadJson.json.encodeToString(state), encode(action), uid, config)) {
            is EngineValidation.Valid -> emptyList()
            is EngineValidation.Invalid -> v.errors
        }

    private fun hand(vararg counts: Pair<String, Int>): Map<String, Int> = Res.all.associateWith { 0 } + counts

    /** A game just past setup: a at corner 0, b at corner 23, with one road each. */
    private fun afterSetup(): HexsteadState {
        val edgeA = HexBoard.vertexEdges[0].first()
        val edgeB = HexBoard.vertexEdges[23].first()
        return newGame(1L).copy(
            phase = Phase.MAIN,
            setupIndex = 4,
            active = "a",
            buildings = mapOf(0 to Building("a", false), 23 to Building("b", false)),
            roads = mapOf(edgeA to "a", edgeB to "b")
        )
    }

    private fun HexsteadState.withHand(uid: String, vararg counts: Pair<String, Int>): HexsteadState =
        copy(players = players + (uid to players.getValue(uid).copy(hand = hand(*counts))))

    // ---- Board ----

    @Test
    fun boardHasTheExpectedShape() {
        assertEquals(24, HexBoard.vertices.size)
        assertEquals(30, HexBoard.edges.size)
        assertEquals(mapOf(3 to 6, 2 to 6, 1 to 12), HexBoard.vertexHexes.groupingBy { it.size }.eachCount())
        assertTrue(HexBoard.vertexHexes.filter { it.size == 3 }.all { HexBoard.CENTER in it })
        assertEquals(setOf(2, 3), HexBoard.vertexEdges.map { it.size }.toSet())
    }

    @Test
    fun everyBoardHasEachResourceAndSeparatesSixAndEight() {
        for (seed in 0L until 500L) {
            val hexes = BoardGen.generate(seed)
            assertEquals(7, hexes.size)
            val counts = hexes.groupingBy { it.resource }.eachCount()
            assertEquals(Res.all.toSet(), counts.keys, "seed $seed")
            assertTrue(counts.values.all { it <= 2 }, "seed $seed")
            assertEquals(Limits.numbers.sorted(), hexes.map { it.number }.sorted())
            val six = hexes.indexOfFirst { it.number == 6 }
            val eight = hexes.indexOfFirst { it.number == 8 }
            assertTrue(six != 0 && eight != 0 && eight !in HexBoard.ringNeighbors(six), "seed $seed")
        }
        assertTrue((0L until 20L).map { BoardGen.generate(it) }.distinct().size > 10, "boards should vary")
    }

    // ---- Setup ----

    @Test
    fun setupRunsFirstSecondSecondFirstAndSecondSettlementPays() {
        var state = newGame(7L)
        val seats = mutableListOf<String>()
        val spots = listOf(0, 5, 10, 15)
        for ((i, vertex) in spots.withIndex()) {
            val uid = state.activePlayer()
            seats += uid
            val action = Action(ActionType.SETUP, vertex = vertex, edge = HexBoard.vertexEdges[vertex].first())
            assertEquals(emptyList(), errors(state, action, uid))
            state = Rules.apply(state, action, uid, SeededRng(i.toLong()))
        }
        assertEquals(listOf("a", "b", "b", "a"), seats)
        assertEquals(Phase.MAIN, state.phase)
        assertEquals("a", state.active)
        assertEquals(EventKind.ROLL, state.events.single { it.kind == EventKind.ROLL }.kind)
        val setupGains = state.lastTurnEvents.filter { it.kind == EventKind.SETUP }.map { it.gains.values.sumOf { g -> g.values.sum() } }
        assertEquals(listOf(0, 0, HexBoard.vertexHexes[10].size, HexBoard.vertexHexes[15].size), setupGains)
    }

    @Test
    fun setupRejectsTakenSpotsAndDetachedRoads() {
        var state = newGame(7L)
        val edge = HexBoard.vertexEdges[0].first()
        state = Rules.apply(state, Action(ActionType.SETUP, vertex = 0, edge = edge), "a", SeededRng(1))
        assertEquals(listOf("vertexTaken", "edgeTaken"), errors(state, Action(ActionType.SETUP, vertex = 0, edge = edge), "b"))
        val far = HexBoard.edges.indices.first { HexBoard.edges[it].a != 23 && HexBoard.edges[it].b != 23 && it != edge }
        assertEquals(listOf("edgeNotAdjacent"), errors(state, Action(ActionType.SETUP, vertex = 23, edge = far), "b"))
        assertEquals(listOf("notActivePlayer"), errors(state, Action(ActionType.SETUP, vertex = 23, edge = far), "a"))
        assertEquals(listOf("setupRequired"), errors(state, Action(ActionType.END_TURN), "b"))
    }

    // ---- Building ----

    @Test
    fun settlementMayGoNextToAnotherButMustTouchOwnRoad() {
        val state = afterSetup().withHand("a", Res.BRICK to 1, Res.WOOD to 1, Res.SHEEP to 1, Res.WHEAT to 1)
        val roadEnd = HexBoard.otherEnd(HexBoard.vertexEdges[0].first(), 0)
        assertEquals(listOf(roadEnd), Rules.legalSettlementVertices(state, "a"))
        assertEquals(emptyList(), errors(state, Action(ActionType.SETTLEMENT, vertex = roadEnd), "a"))
        val unreached = HexBoard.vertices.indices.first { it != 0 && it != roadEnd && it != 23 }
        assertEquals(listOf("illegalSettlement"), errors(state, Action(ActionType.SETTLEMENT, vertex = unreached), "a"))
        val built = Rules.apply(state, Action(ActionType.SETTLEMENT, vertex = roadEnd), "a", SeededRng(1))
        assertEquals(0, built.players.getValue("a").total())
        assertEquals(2, Rules.victoryPoints(built, "a"))
    }

    @Test
    fun buildingNeedsTheResources() {
        val state = afterSetup()
        val edge = Rules.legalRoadEdges(state, "a").first()
        assertEquals(listOf("cannotAfford"), errors(state, Action(ActionType.ROAD, edge = edge), "a"))
        assertEquals(listOf("cannotAfford"), errors(state, Action(ActionType.CITY, vertex = 0), "a"))
        assertEquals(listOf("cannotAfford"), errors(state, Action(ActionType.BUY_CARD), "a"))
        assertEquals(listOf("cannotAfford"), errors(state, Action(ActionType.TRADE, give = Res.BRICK, get = Res.WOOD), "a"))
    }

    @Test
    fun cityReplacesSettlementAndProducesDouble() {
        var state = afterSetup().withHand("a", Res.STONE to 3, Res.WHEAT to 2)
        state = Rules.apply(state, Action(ActionType.CITY, vertex = 0), "a", SeededRng(1))
        assertEquals(Building("a", true), state.buildings[0])
        assertEquals(2, Rules.victoryPoints(state, "a"))
        val tile = state.hexes[HexBoard.vertexHexes[0].first()]
        val seed = (0L until 500L).first { s ->
            val rng = SeededRng(s)
            rng.nextInt(6) + 1 + rng.nextInt(6) + 1 == tile.number
        }
        state = Rules.apply(state, Action(ActionType.END_TURN), "a", SeededRng(seed))
        assertEquals("b", state.active)
        assertEquals(2, state.players.getValue("a").count(tile.resource))
    }

    @Test
    fun bankTradeIsThreeForOne() {
        var state = afterSetup().withHand("a", Res.BRICK to 3)
        state = Rules.apply(state, Action(ActionType.TRADE, give = Res.BRICK, get = Res.STONE), "a", SeededRng(1))
        assertEquals(hand(Res.STONE to 1), state.players.getValue("a").hand)
        assertEquals(listOf("sameResource"), errors(state, Action(ActionType.TRADE, give = Res.STONE, get = Res.STONE), "a"))
    }

    // ---- Dice ----

    @Test
    fun sevenHalvesBigHandsAndNothingElse() {
        val state = afterSetup().withHand("b", Res.BRICK to 5, Res.WOOD to 4).withHand("a", Res.WHEAT to 7)
        val seed = (0L until 500L).first { s ->
            val rng = SeededRng(s)
            rng.nextInt(6) + 1 + rng.nextInt(6) + 1 == 7
        }
        val next = Rules.apply(state, Action(ActionType.END_TURN), "a", SeededRng(seed))
        assertEquals(5, next.players.getValue("b").total())
        assertEquals(7, next.players.getValue("a").total())
        assertEquals(listOf(EventKind.ROLL, EventKind.DISCARD), next.events.map { it.kind })
    }

    // ---- Cards ----

    private fun withOnly(state: HexsteadState, card: String): HexsteadState =
        state.copy(deck = Cards.all.associateWith { if (it == card) 1 else 0 })
            .withHand("a", Res.STONE to 1, Res.SHEEP to 1, Res.WHEAT to 1)

    @Test
    fun raiderStealsOneCardAndTwoRaidersTakeTheAward() {
        var state = withOnly(afterSetup(), Cards.RAIDER).withHand("b", Res.WOOD to 2)
        state = Rules.apply(state, Action(ActionType.BUY_CARD), "a", SeededRng(3))
        assertEquals(1, state.players.getValue("a").count(Res.WOOD))
        assertEquals(1, state.players.getValue("b").count(Res.WOOD))
        assertNull(state.mostRaidersHolder)
        assertEquals(listOf("deckEmpty", "cannotAfford"), errors(state, Action(ActionType.BUY_CARD), "a"))
        state = withOnly(state, Cards.RAIDER).withHand("b")
        state = Rules.apply(state, Action(ActionType.BUY_CARD), "a", SeededRng(4))
        assertEquals("a", state.mostRaidersHolder)
        assertEquals(1 + Limits.AWARD_VP, Rules.victoryPoints(state, "a"))
    }

    @Test
    fun monumentScoresAtOnce() {
        val state = Rules.apply(withOnly(afterSetup(), Cards.MONUMENT), Action(ActionType.BUY_CARD), "a", SeededRng(1))
        assertEquals(2, Rules.victoryPoints(state, "a"))
        assertNull(state.pending)
    }

    @Test
    fun roadCrewGivesTwoFreeRoadsBeforeAnythingElse() {
        var state = Rules.apply(withOnly(afterSetup(), Cards.ROAD_CREW), Action(ActionType.BUY_CARD), "a", SeededRng(1))
        assertEquals(Pending(PendingKind.FREE_ROADS, 2), state.pending)
        assertEquals(listOf("pendingChoice"), errors(state, Action(ActionType.END_TURN), "a"))
        repeat(2) {
            val edge = Rules.legalRoadEdges(state, "a").first()
            assertEquals(emptyList(), errors(state, Action(ActionType.ROAD, edge = edge), "a"))
            state = Rules.apply(state, Action(ActionType.ROAD, edge = edge), "a", SeededRng(1))
        }
        assertNull(state.pending)
        assertEquals(3, Rules.roadCount(state, "a"))
        assertEquals(0, state.players.getValue("a").total())
    }

    @Test
    fun windfallAndLevyWaitForAChoice() {
        var state = Rules.apply(withOnly(afterSetup(), Cards.WINDFALL), Action(ActionType.BUY_CARD), "a", SeededRng(1))
        assertEquals(listOf("wrongChoiceCount"), errors(state, Action(ActionType.CHOOSE, resources = listOf(Res.STONE)), "a"))
        state = Rules.apply(state, Action(ActionType.CHOOSE, resources = listOf(Res.STONE, Res.STONE)), "a", SeededRng(1))
        assertEquals(2, state.players.getValue("a").count(Res.STONE))

        state = Rules.apply(
            withOnly(state, Cards.LEVY).withHand("b", Res.WOOD to 3, Res.BRICK to 1),
            Action(ActionType.BUY_CARD), "a", SeededRng(1)
        )
        state = Rules.apply(state, Action(ActionType.CHOOSE, resources = listOf(Res.WOOD)), "a", SeededRng(1))
        assertEquals(3, state.players.getValue("a").count(Res.WOOD))
        assertEquals(hand(Res.BRICK to 1), state.players.getValue("b").hand)
        assertEquals(listOf("noPendingChoice"), errors(state, Action(ActionType.CHOOSE, resources = listOf(Res.WOOD)), "a"))
    }

    // ---- Longest road ----

    /** Edge ids of a walk along the outer rim starting at corner [from]. */
    private fun chain(from: Int, length: Int): List<Int> {
        val picked = mutableListOf<Int>()
        var at = from
        repeat(length) {
            val edge = HexBoard.vertexEdges[at].first { it !in picked }
            picked += edge
            at = HexBoard.otherEnd(edge, at)
        }
        return picked
    }

    @Test
    fun longestRoadNeedsFourAndARivalSettlementCutsIt() {
        val edges = chain(0, 4)
        var state = afterSetup().copy(roads = edges.take(3).associateWith { "a" })
            .withHand("a", Res.BRICK to 1, Res.WOOD to 1)
        assertEquals(3, Rules.longestRoad(state, "a"))
        state = Rules.apply(state, Action(ActionType.ROAD, edge = edges[3]), "a", SeededRng(1))
        assertEquals("a", state.longestRoadHolder)
        assertEquals(1 + Limits.AWARD_VP, Rules.victoryPoints(state, "a"))

        val middle = HexBoard.edges[edges[1]].let { e -> listOf(e.a, e.b).first { it in HexBoard.edges[edges[2]].let { n -> listOf(n.a, n.b) } } }
        val rivalRoad = HexBoard.vertexEdges[middle].firstOrNull { it !in edges }
        if (rivalRoad != null) {
            state = state.copy(active = "b", roads = state.roads + (rivalRoad to "b"))
                .withHand("b", Res.BRICK to 1, Res.WOOD to 1, Res.SHEEP to 1, Res.WHEAT to 1)
            state = Rules.apply(state, Action(ActionType.SETTLEMENT, vertex = middle), "b", SeededRng(1))
            assertEquals(2, Rules.longestRoad(state, "a"))
            assertNull(state.longestRoadHolder)
        }
    }

    // ---- Winning ----

    @Test
    fun reachingSevenPointsOnYourTurnWins() {
        val buildings = mapOf(
            0 to Building("a", true), 1 to Building("a", true), 2 to Building("a", true), 23 to Building("b", false)
        )
        val state = withOnly(afterSetup().copy(buildings = buildings), Cards.MONUMENT)
        assertEquals(6, Rules.victoryPoints(state, "a"))
        val result = HexsteadEngine.apply(HexsteadJson.json.encodeToString(state), encode(Action(ActionType.BUY_CARD)), "a", 1L, config)
        assertEquals(GameStatus.COMPLETE, result.status)
        assertEquals("a", result.winnerUid)
        assertEquals(listOf("gameComplete"), errors(decode(result.stateJson), Action(ActionType.END_TURN), "a"))
    }

    @Test
    fun malformedAndUnknownSubmissionsAreRejected() {
        val stateJson = HexsteadJson.json.encodeToString(afterSetup())
        assertIs<EngineValidation.Invalid>(HexsteadEngine.validate(stateJson, "not json", "a", config))
        assertEquals(listOf("unknownAction"), errors(afterSetup(), Action("dance"), "a"))
    }

    // ---- Whole games ----

    private data class Played(val state: HexsteadState, val trace: List<String>)

    private fun playBotGame(seed: Long): Played {
        val bot = SeededRng(seed * 7919 + 13)
        var stateJson = HexsteadDefinition.initialStateJson(config, players, seed)
        val trace = mutableListOf(stateJson)
        var status = GameStatus.ACTIVE
        var round = 0
        while (status == GameStatus.ACTIVE) {
            val state = decode(stateJson)
            val uid = HexsteadEngine.activePlayer(stateJson, config)
            val action = encode(Bot.choose(state, uid, bot))
            assertIs<EngineValidation.Valid>(HexsteadEngine.validate(stateJson, action, uid, config), "seed $seed round $round $action")
            val result = HexsteadEngine.apply(stateJson, action, uid, seed * 31 + round, config)
            stateJson = result.stateJson
            status = result.status
            trace += stateJson
            round += 1
            assertTrue(decode(stateJson).turn <= 400, "seed $seed never finished")
        }
        return Played(decode(stateJson), trace)
    }

    @Test
    fun botGamesFinishWithAWinnerAndLegalState() {
        val games = (1L..300L).map { playBotGame(it).state }
        for (state in games) {
            val winner = state.winner!!
            assertTrue(Rules.victoryPoints(state, winner) >= Limits.WIN_VP)
            assertEquals(winner, state.active)
            for (uid in state.order) {
                assertTrue(state.players.getValue(uid).hand.values.all { it >= 0 })
                assertTrue(Rules.roadCount(state, uid) <= Limits.ROADS)
                assertTrue(Rules.settlementCount(state, uid) <= Limits.SETTLEMENTS)
                assertTrue(Rules.cityCount(state, uid) <= Limits.CITIES)
            }
            assertTrue(state.deck.values.all { it >= 0 })
        }
        val turnsEach = games.map { (it.turn + 1) / 2 }.sorted()
        val firstSeatWins = games.count { it.winner == it.order[0] }
        println(
            "hexstead bots: ${games.size} games, turns each median ${turnsEach[turnsEach.size / 2]}, " +
                "10th-90th pct ${turnsEach[turnsEach.size / 10]}-${turnsEach[turnsEach.size * 9 / 10]}, " +
                "first seat wins ${firstSeatWins * 100 / games.size}%"
        )
    }

    @Test
    fun sameSeedsGiveTheSameGame() {
        assertEquals(playBotGame(42L).trace, playBotGame(42L).trace)
    }
}

/** A simple greedy player used to check that whole games play out. */
private object Bot {
    private fun pips(number: Int): Int = 6 - kotlin.math.abs(7 - number)

    private fun value(state: HexsteadState, vertex: Int): Int =
        HexBoard.vertexHexes[vertex].sumOf { pips(state.hexes[it].number) }

    fun choose(state: HexsteadState, uid: String, rng: SeededRng): Action {
        val me = state.players.getValue(uid)
        if (state.phase == Phase.SETUP) {
            val vertex = Rules.setupVertices(state).maxBy { value(state, it) * 10 + rng.nextInt(10) }
            val edges = Rules.setupEdges(state, vertex)
            return Action(ActionType.SETUP, vertex = vertex, edge = edges[rng.nextInt(edges.size)])
        }
        state.pending?.let { pending ->
            return when (pending.kind) {
                PendingKind.FREE_ROADS -> Action(ActionType.ROAD, edge = bestRoad(state, uid, rng))
                PendingKind.WINDFALL -> Action(ActionType.CHOOSE, resources = listOf(Res.STONE, Res.WHEAT))
                else -> Action(ActionType.CHOOSE, resources = listOf(Res.all.maxBy { state.players.getValue(state.opponentOf(uid)).count(it) }))
            }
        }
        val cities = Rules.legalCityVertices(state, uid)
        val settlements = Rules.legalSettlementVertices(state, uid)
        val roads = Rules.legalRoadEdges(state, uid)
        if (cities.isNotEmpty() && me.canAfford(Costs.city)) {
            return Action(ActionType.CITY, vertex = cities.maxBy { value(state, it) })
        }
        if (settlements.isNotEmpty() && me.canAfford(Costs.settlement)) {
            return Action(ActionType.SETTLEMENT, vertex = settlements.maxBy { value(state, it) })
        }
        if (settlements.isEmpty() && roads.isNotEmpty() && me.canAfford(Costs.road)) {
            return Action(ActionType.ROAD, edge = bestRoad(state, uid, rng))
        }
        val goal = when {
            cities.isNotEmpty() -> Costs.city
            settlements.isNotEmpty() -> Costs.settlement
            roads.isNotEmpty() -> Costs.road
            else -> Costs.card
        }
        val missing = Res.all.firstOrNull { me.count(it) < (goal[it] ?: 0) }
        val spare = Res.all.firstOrNull { me.count(it) - (goal[it] ?: 0) >= Limits.TRADE_RATE }
        if (missing != null && spare != null) return Action(ActionType.TRADE, give = spare, get = missing)
        if (missing == null && goal === Costs.card && state.deck.values.sum() > 0) return Action(ActionType.BUY_CARD)
        if (me.total() > Limits.HAND_LIMIT && me.canAfford(Costs.card) && state.deck.values.sum() > 0) {
            return Action(ActionType.BUY_CARD)
        }
        return Action(ActionType.END_TURN)
    }

    private fun bestRoad(state: HexsteadState, uid: String, rng: SeededRng): Int =
        Rules.legalRoadEdges(state, uid).maxBy { edge ->
            val ends = HexBoard.edges[edge]
            listOf(ends.a, ends.b).filter { it !in state.buildings }.maxOfOrNull { value(state, it) }?.let { it * 10 + rng.nextInt(10) } ?: 0
        }
}
