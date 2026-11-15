package com.greenopal.quickgames.games.rpg

import com.greenopal.quickgames.core.engine.EngineValidation
import com.greenopal.quickgames.core.engine.SeededRng
import com.greenopal.quickgames.core.model.GameStatus
import com.greenopal.quickgames.core.model.PlayerRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RpgEngineTest {

    private val players = listOf(PlayerRef("a", "A", 0), PlayerRef("b", "B", 1))
    private val config = RpgDefinition.configJson("standard")

    private fun decode(stateJson: String): RpgState = RpgJson.json.decodeFromString(stateJson)

    private fun randomTurn(state: RpgState, uid: String, rng: SeededRng): String {
        val hero = state.heroes.getValue(uid)
        val open = state.squares.filter { it.claimedBy == null }
        val square = open[rng.nextInt(open.size)].id
        val boons = hero.pendingOffers.map { it[rng.nextInt(it.size)] }
        val loot = if (hero.pendingLoot == null) null else if (rng.nextInt(2) == 0) "EQUIP" else "CONVERT"
        return RpgJson.json.encodeToString(HuntTurn(square, boons, loot))
    }

    private fun playRandomGame(seed: Long): Pair<RpgState, List<String>> {
        val rng = SeededRng(seed * 7919 + 13)
        var stateJson = RpgDefinition.initialStateJson(config, players, seed)
        val trace = mutableListOf(stateJson)
        var status = GameStatus.ACTIVE
        var turns = 0
        while (status == GameStatus.ACTIVE) {
            val state = decode(stateJson)
            val uid = RpgEngine.activePlayer(stateJson, config)
            val turn = randomTurn(state, uid, rng)
            assertIs<EngineValidation.Valid>(RpgEngine.validate(stateJson, turn, uid, config), "seed $seed turn $turns")
            val result = RpgEngine.apply(stateJson, turn, uid, seed + turns * 31L, config)
            stateJson = result.stateJson
            status = result.status
            trace += stateJson
            turns += 1
            assertTrue(turns <= RpgContent.TOTAL_PICKS, "game ran past 20 picks")
        }
        return decode(stateJson) to trace
    }

    @Test
    fun mapIsMirroredWithTierFourCentre() {
        val squares = MapGen.generateSquares(42L)
        assertEquals(25, squares.size)
        assertEquals(4, squares[12].tier)
        for (i in 0 until 12) assertEquals(squares[i].tier, squares[24 - i].tier)
        assertEquals(mapOf(1 to 8, 2 to 8, 3 to 6, 4 to 3), squares.groupingBy { it.tier }.eachCount())
    }

    @Test
    fun draftOrderIsSnake() {
        val state = decode(RpgDefinition.initialStateJson(config, players, 1L))
        assertEquals(listOf("a", "b", "b", "a", "a", "b", "b", "a"), state.draftOrder.take(8))
        assertEquals(10, state.draftOrder.count { it == "a" })
    }

    @Test
    fun randomGamesAlwaysFinishWithAWinner() {
        var totalLevels = 0
        for (seed in 1L..200L) {
            val (state, _) = playRandomGame(seed)
            assertEquals("complete", state.phase)
            assertEquals(20, state.pickIndex)
            assertNotNull(state.winner)
            assertNotNull(state.finalBattle)
            assertEquals(20, state.squares.count { it.claimedBy != null })
            for (hero in state.heroes.values) {
                assertTrue(hero.pendingOffers.isEmpty() && hero.pendingLoot == null)
                assertTrue(hero.hp <= hero.maxHp)
                totalLevels += hero.level
            }
        }
        assertTrue(totalLevels / 400.0 > 3.0, "random play should still gain levels, got ${totalLevels / 400.0}")
    }

    @Test
    fun sameSeedsReplayIdentically() {
        for (seed in 1L..20L) {
            assertEquals(playRandomGame(seed).second, playRandomGame(seed).second)
        }
    }

    @Test
    fun rejectsBadTurns() {
        val initial = RpgDefinition.initialStateJson(config, players, 5L)
        fun errors(turn: String, uid: String) =
            (RpgEngine.validate(initial, turn, uid, config) as EngineValidation.Invalid).errors
        assertEquals(listOf("malformedSubmission"), errors("""{"nope":1}""", "a"))
        assertEquals(listOf("notActivePlayer"), errors("""{"squareId":0}""", "b"))
        assertEquals(listOf("squareOutOfRange"), errors("""{"squareId":25}""", "a"))
        assertEquals(listOf("boonChoicesNotOwed"), errors("""{"squareId":0,"boonChoices":["MIGHT"]}""", "a"))
        assertEquals(listOf("noLootPending"), errors("""{"squareId":0,"lootDecision":"EQUIP"}""", "a"))
        val afterPick = RpgEngine.apply(initial, """{"squareId":0}""", "a", 9L, config).stateJson
        assertEquals(
            listOf("squareClaimed"),
            (RpgEngine.validate(afterPick, """{"squareId":0}""", "b", config) as EngineValidation.Invalid).errors
        )
    }

    @Test
    fun losingAHuntFullyHealsAndGrantsNothing() {
        val initial = decode(RpgDefinition.initialStateJson(config, players, 3L))
        val tierFour = initial.squares.first { it.tier == 4 }.id
        val result = RpgEngine.apply(RpgJson.json.encodeToString(initial), """{"squareId":$tierFour}""", "a", 1L, config)
        val state = decode(result.stateJson)
        val encounter = assertNotNull(state.lastEncounter)
        assertEquals(false, encounter.won)
        val hero = state.heroes.getValue("a")
        assertEquals(hero.maxHp, hero.hp)
        assertEquals(0, hero.xp)
        assertEquals("a", state.squares[tierFour].claimedBy)
    }
}
