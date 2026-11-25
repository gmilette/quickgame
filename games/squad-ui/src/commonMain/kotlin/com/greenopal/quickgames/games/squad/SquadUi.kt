package com.greenopal.quickgames.games.squad

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.greenopal.quickgames.core.model.GameDoc
import com.greenopal.quickgames.core.model.GameStatus
import com.greenopal.quickgames.core.model.GameType
import com.greenopal.quickgames.core.ui.GameUi
import com.greenopal.quickgames.core.ui.PixelPalette
import com.greenopal.quickgames.core.ui.rememberReplayState

private val myColor = Color(0xFF4FC3F7)
private val rivalColor = Color(0xFFF87858)
private val floorColor = Color(0xFF26263C)
private val wallColor = Color(0xFF6E6E86)
private val rubbleColor = Color(0xFF5C4A36)
private val coreColor = Color(0xFFB388FF)
private const val REPLAY_STEP_MILLIS = 600L
private const val COLLAPSED_LOG_LINES = 4

private enum class Mode { NONE, FACE, GRENADE }

private val reasonText = mapOf(
    "noAp" to "Not enough AP",
    "blocked" to "That square is blocked",
    "notInView" to "Not in view: face it first",
    "noTarget" to "No enemy there",
    "outOfRange" to "Too far to throw",
    "noGrenade" to "No grenades left",
    "badSquare" to "Can't throw there",
    "noCore" to "No core there",
    "noShot" to "This weapon can't do that",
    "dead" to "That soldier is down",
    "halted" to "Stopped after spotting an enemy"
)

object SquadUi : GameUi {
    override val gameType = GameType.SQUAD

    @Composable
    override fun GameScreen(game: GameDoc, myUid: String, onSubmit: (String) -> Unit) {
        val state = remember(game.stateJson) { SquadJson.json.decodeFromString<SquadState>(game.stateJson) }
        if (state.units.isEmpty()) {
            Text("Loading your view...", Modifier.padding(16.dp))
            return
        }
        val roster = remember(state.mode, state.players) {
            SquadContent.startUnits(state.mode, state.players).associateBy { it.id }
        }
        val rivalUid = state.opponentOf(myUid)
        val rivalName = game.opponentOf(myUid)?.displayName ?: "Opponent"

        var orders by remember(game.round) { mutableStateOf(listOf<Order>()) }
        var selected by remember(game.round) { mutableStateOf<Int?>(null) }
        var target by remember(game.round) { mutableStateOf<Int?>(null) }
        var mode by remember(game.round) { mutableStateOf(Mode.NONE) }
        var submitted by remember(game.round) { mutableStateOf(false) }
        var message by remember(game.round) { mutableStateOf<String?>(null) }
        var showLog by remember { mutableStateOf(false) }

        val canAct = game.isMyTurn(myUid) && !submitted && !state.complete
        val preview = remember(state, orders) { SquadRules.preview(state, myUid, orders) }
        val planned = preview.state
        val grid = remember(state) { Grid(state) }
        val selectedUnit = selected?.let { planned.unit(it) }?.takeIf { it.alive && canAct }
        val targetUnit = target?.let { planned.unit(it) }?.takeIf { it.alive && it.owner != myUid }

        // The last turn: the opponent's when it's my turn, my own result while I wait.
        val report = remember(state) { state.events.filter { it.turn == state.turn - 1 } }
        val replay = key(state.turn) { rememberReplayState(report.size, REPLAY_STEP_MILLIS) }
        val replaying = !replay.finished
        val current = if (replaying) report.getOrNull(replay.index) else null

        fun add(newOrders: List<Order>) {
            val tried = SquadRules.preview(state, myUid, orders + newOrders)
            val firstFail = tried.failed.keys.filter { it >= orders.size }.minOrNull()
            if (firstFail == null) {
                orders = orders + newOrders
                message = null
                return
            }
            val kept = newOrders.take(firstFail - orders.size)
            orders = orders + kept
            val reason = reasonText[tried.failed.getValue(firstFail)] ?: "Can't do that"
            message = if (kept.isEmpty()) reason else "$reason: stopped part way"
        }

        fun onCell(x: Int, y: Int) {
            if (!canAct) return
            message = null
            val there = planned.unitAt(x, y)
            val sel = selectedUnit
            when {
                mode == Mode.FACE && sel != null -> {
                    if (x != sel.x || y != sel.y) add(listOf(Order(sel.id, OrderType.FACE, dir = Dir.toward(x - sel.x, y - sel.y))))
                    mode = Mode.NONE
                }
                mode == Mode.GRENADE && sel != null -> {
                    add(listOf(Order(sel.id, OrderType.THROW, x = x, y = y)))
                    mode = Mode.NONE
                }
                there != null && there.owner == myUid -> {
                    selected = there.id
                    target = null
                    mode = Mode.NONE
                }
                sel == null -> message = "Tap one of your soldiers first"
                there != null -> target = there.id
                grid.terrain(x, y) == SquadContent.CORE && Dir.distance(sel.x, sel.y, x, y) == 1 ->
                    add(listOf(Order(sel.id, OrderType.SABOTAGE, dir = Dir.toward(x - sel.x, y - sel.y))))
                else -> {
                    target = null
                    val route = SquadRules.route(planned, sel, x, y)
                    if (route == null) message = "Can't get there" else add(route.dirs.map { Order(sel.id, OrderType.MOVE, dir = it) })
                }
            }
        }

        Column(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Header(state, myUid, rivalUid, rivalName)
                Board(
                    state = state,
                    planned = planned,
                    grid = grid,
                    myUid = myUid,
                    orders = orders,
                    failed = preview.failed.keys,
                    selected = selectedUnit?.id,
                    target = targetUnit?.id,
                    current = current,
                    // A lambda, not ::onCell: references to a local function compare equal across
                    // recompositions, so the board would keep calling the first one.
                    onCell = { x, y -> onCell(x, y) }
                )
                ReportPanel(report, replay.index, replaying, roster, myUid, showLog, onSkip = { replay.skipToEnd() }) { showLog = !showLog }
            }

            Column(
                modifier = Modifier.fillMaxWidth().background(PixelPalette.surface).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val finished = game.status != GameStatus.ACTIVE || state.complete
                Text(
                    text = message ?: statusText(game, state, myUid, rivalName, submitted, mode, selectedUnit),
                    style = MaterialTheme.typography.titleMedium,
                    color = when {
                        message != null -> PixelPalette.danger
                        canAct || finished -> PixelPalette.primary
                        else -> PixelPalette.text
                    }
                )
                if (canAct) {
                    val sel = selectedUnit
                    if (sel != null) {
                        Text(unitLine(sel), style = MaterialTheme.typography.bodyMedium)
                        val tgt = targetUnit
                        if (tgt != null) {
                            AttackButtons(planned, grid, sel, tgt, { add(it) }) { target = null }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                BarButton("FACE", true, mode == Mode.FACE, Modifier.weight(1f)) {
                                    mode = if (mode == Mode.FACE) Mode.NONE else Mode.FACE
                                }
                                BarButton(
                                    "GRENADE ${SquadRules.throwCost(sel)}AP", sel.grenades > 0, mode == Mode.GRENADE, Modifier.weight(1f)
                                ) { mode = if (mode == Mode.GRENADE) Mode.NONE else Mode.GRENADE }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        BarButton("UNDO", orders.isNotEmpty(), false, Modifier.weight(1f)) {
                            orders = orders.dropLast(1)
                            message = null
                        }
                        BarButton("CLEAR", orders.isNotEmpty(), false, Modifier.weight(1f)) {
                            orders = emptyList()
                            message = null
                        }
                    }
                    BarButton("END TURN (${orders.size} orders)", true, false, Modifier.fillMaxWidth()) {
                        submitted = true
                        mode = Mode.NONE
                        onSubmit(SquadJson.json.encodeToString(SquadTurn(orders)))
                    }
                }
            }
        }
    }

    private fun unitLine(u: SquadUnit): String =
        "${u.name} (${u.stats.name}, ${u.stats.weapon.name})  AP ${u.ap}/${u.stats.ap}  HP ${u.health}/${u.stats.health}" +
            if (u.grenades > 0) "  Grenades ${u.grenades}" else ""

    private fun statusText(
        game: GameDoc,
        state: SquadState,
        myUid: String,
        rivalName: String,
        submitted: Boolean,
        mode: Mode,
        selected: SquadUnit?
    ): String = when {
        game.status == GameStatus.FORFEITED && game.winnerUid == myUid -> "Opponent forfeited - you win!"
        game.status == GameStatus.FORFEITED -> "Forfeited - you lose"
        state.complete && state.winner == myUid -> "You win!"
        state.complete && state.winner == null -> "Draw"
        state.complete -> "$rivalName wins"
        submitted -> "Sending..."
        !game.isMyTurn(myUid) -> "Waiting for $rivalName"
        mode == Mode.FACE -> "Tap a square to face towards"
        mode == Mode.GRENADE -> "Tap a square to throw at (range ${SquadContent.THROW_RANGE})"
        selected == null -> "Your turn: tap a soldier"
        else -> "Tap a square to move, or an enemy to attack"
    }

    // ---- Header ----

    @Composable
    private fun Header(state: SquadState, myUid: String, rivalUid: String, rivalName: String) {
        Column(Modifier.fillMaxWidth().background(PixelPalette.surface).padding(8.dp)) {
            val raid = state.mode == SquadContent.RAID
            Text(
                "${if (raid) "Raid" else "Skirmish"}  Round ${minOf(state.round, state.maxRounds)}/${state.maxRounds}",
                style = MaterialTheme.typography.titleMedium
            )
            if (raid) {
                val attacking = state.attacker() == myUid
                Text(
                    (if (attacking) "You attack. " else "You defend. ") +
                        "Cores destroyed ${state.cores.count { it.destroyed }}/${SquadContent.CORES_TO_WIN}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PixelPalette.secondary
                )
            } else {
                Text(
                    "You ${state.scores[myUid] ?: 0} - ${state.scores[rivalUid] ?: 0} $rivalName  (first to ${SquadContent.WIN_POINTS})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PixelPalette.secondary
                )
            }
            Text(
                "Soldiers left: you ${state.remaining[myUid] ?: 0}, $rivalName ${state.remaining[rivalUid] ?: 0}",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    // ---- Board ----

    @Composable
    private fun Board(
        state: SquadState,
        planned: SquadState,
        grid: Grid,
        myUid: String,
        orders: List<Order>,
        failed: Set<Int>,
        selected: Int?,
        target: Int?,
        current: Event?,
        onCell: (Int, Int) -> Unit
    ) {
        val seen = remember(state) {
            (0 until grid.height).flatMap { y ->
                (0 until grid.width).filter { x -> Vision.playerSeesSquare(grid, state.units, myUid, x, y) }.map { x -> x to y }
            }.toSet()
        }
        val paths = remember(state, orders) { plannedPaths(state, orders, failed) }
        val lastSeen = state.lastSeen[myUid].orEmpty()
        // The tap detector lives across recompositions, so it must always call the latest handler.
        val currentOnCell by rememberUpdatedState(onCell)

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cell = maxWidth / grid.width
            Box(
                Modifier.fillMaxWidth().height(cell * grid.height)
                    .pointerInput(grid) {
                        detectTapGestures { offset ->
                            val side = size.width.toFloat() / grid.width
                            val x = (offset.x / side).toInt()
                            val y = (offset.y / side).toInt()
                            if (grid.inside(x, y)) currentOnCell(x, y)
                        }
                    }
            ) {
                Canvas(Modifier.matchParentSize()) {
                    val side = size.width / grid.width
                    fun centre(x: Int, y: Int) = Offset((x + 0.5f) * side, (y + 0.5f) * side)

                    for (y in 0 until grid.height) for (x in 0 until grid.width) {
                        val topLeft = Offset(x * side, y * side)
                        val tile = Size(side - 1f, side - 1f)
                        val colour = when (grid.terrain(x, y)) {
                            SquadContent.WALL -> wallColor
                            SquadContent.RUBBLE -> rubbleColor
                            else -> floorColor
                        }
                        drawRect(colour, topLeft, tile)
                        if (grid.terrain(x, y) == SquadContent.CORE) {
                            drawCircle(coreColor, side * 0.3f, centre(x, y))
                        }
                        if ((x to y) !in seen) drawRect(Color.Black.copy(alpha = 0.45f), topLeft, tile)
                    }
                    if (state.mode == SquadContent.SKIRMISH) {
                        drawRect(
                            PixelPalette.secondary, Offset(3 * side, 4 * side), Size(3 * side, 3 * side),
                            style = Stroke(width = 3f)
                        )
                    }
                    for (points in paths.values) {
                        for (i in 1 until points.size) {
                            drawLine(
                                PixelPalette.primary.copy(alpha = 0.6f),
                                centre(points[i - 1].first, points[i - 1].second),
                                centre(points[i].first, points[i].second),
                                strokeWidth = 4f
                            )
                        }
                    }
                    for (s in lastSeen) {
                        drawCircle(rivalColor.copy(alpha = 0.5f), side * 0.3f, centre(s.x, s.y), style = Stroke(width = 3f))
                    }
                    for (u in planned.units.filter { it.alive }) {
                        val c = centre(u.x, u.y)
                        val colour = if (u.owner == myUid) myColor else rivalColor
                        drawCircle(colour, side * 0.36f, c)
                        drawLine(
                            Color.White,
                            c,
                            Offset(c.x + Dir.dx[u.facing] * side * 0.48f, c.y + Dir.dy[u.facing] * side * 0.48f),
                            strokeWidth = 5f
                        )
                        drawHealth(u, c, side)
                        if (u.id == selected) drawCircle(Color.White, side * 0.46f, c, style = Stroke(width = 4f))
                        if (u.id == target) drawCircle(PixelPalette.danger, side * 0.46f, c, style = Stroke(width = 4f))
                    }
                    current?.let { drawEvent(it, side, ::centre) }
                }
                for (u in planned.units.filter { it.alive }) {
                    Box(
                        Modifier.offset(cell * u.x, cell * u.y).size(cell),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(u.stats.name.take(1), style = MaterialTheme.typography.labelLarge, color = Color.Black)
                    }
                }
            }
        }
    }

    private fun DrawScope.drawHealth(u: SquadUnit, c: Offset, side: Float) {
        val width = side * 0.7f
        val left = c.x - width / 2
        val top = c.y + side * 0.36f
        drawRect(Color.Black, Offset(left, top), Size(width, 5f))
        drawRect(PixelPalette.primary, Offset(left, top), Size(width * u.health / u.stats.health, 5f))
    }

    private fun DrawScope.drawEvent(event: Event, side: Float, centre: (Int, Int) -> Offset) {
        val highlight = PixelPalette.secondary
        when (event.kind) {
            EventKind.SHOT, EventKind.MELEE, EventKind.THROW ->
                drawLine(
                    if (event.hit) highlight else Color.White.copy(alpha = 0.6f),
                    centre(event.x, event.y),
                    centre(event.tx, event.ty),
                    strokeWidth = 6f
                )
            EventKind.MOVE -> drawLine(highlight, centre(event.tx, event.ty), centre(event.x, event.y), strokeWidth = 6f)
            EventKind.BLAST -> drawCircle(highlight.copy(alpha = 0.5f), side * 1.5f, centre(event.x, event.y))
            EventKind.KILL, EventKind.HURT, EventKind.HALT, EventKind.TURN ->
                drawCircle(highlight, side * 0.5f, centre(event.x, event.y), style = Stroke(width = 6f))
            EventKind.SABOTAGE -> drawCircle(highlight, side * 0.5f, centre(event.tx, event.ty), style = Stroke(width = 6f))
            else -> Unit
        }
    }

    /** The squares each of my units passes through under the planned orders. */
    private fun plannedPaths(state: SquadState, orders: List<Order>, failed: Set<Int>): Map<Int, List<Pair<Int, Int>>> {
        val paths = mutableMapOf<Int, MutableList<Pair<Int, Int>>>()
        orders.forEachIndexed { i, order ->
            if (order.type != OrderType.MOVE || i in failed) return@forEachIndexed
            val start = state.unit(order.unit) ?: return@forEachIndexed
            val points = paths.getOrPut(order.unit) { mutableListOf(start.x to start.y) }
            val (x, y) = points.last()
            points += (x + Dir.dx[order.dir]) to (y + Dir.dy[order.dir])
        }
        return paths
    }

    // ---- Attacks ----

    @Composable
    private fun AttackButtons(
        planned: SquadState,
        grid: Grid,
        sel: SquadUnit,
        tgt: SquadUnit,
        add: (List<Order>) -> Unit,
        onCancel: () -> Unit
    ) {
        val weapon = sel.stats.weapon
        val inView = Vision.canTarget(grid, sel, tgt)
        val adjacent = Dir.distance(sel.x, sel.y, tgt.x, tgt.y) == 1
        Text(
            "Target: ${tgt.name} (${tgt.stats.name})  HP ${tgt.health}/${tgt.stats.health}" + if (!inView) "  - not in view" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = PixelPalette.danger
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((type, label) in listOf(OrderType.SNAP to "SNAP", OrderType.AIM to "AIM", OrderType.AUTO to "AUTO")) {
                val cost = SquadRules.shotCost(sel, type) ?: continue
                val chance = SquadRules.hitChance(grid, sel, tgt, type)
                val shots = if (type == OrderType.AUTO) "${SquadContent.AUTO_BURST}x" else ""
                BarButton("$label\n$shots$chance% ${cost}AP", inView && sel.ap >= cost, false, Modifier.weight(1f)) {
                    add(listOf(Order(sel.id, type, target = tgt.id)))
                }
            }
            if (adjacent) {
                val dir = Dir.toward(tgt.x - sel.x, tgt.y - sel.y)
                val cost = SquadRules.turnCost(sel.facing, dir) + SquadRules.meleeCost(sel)
                val chance = SquadRules.meleeChance(sel.copy(facing = dir), tgt)
                BarButton("MELEE\n$chance% ${cost}AP", sel.ap >= cost, false, Modifier.weight(1f)) {
                    add(listOf(Order(sel.id, OrderType.MELEE, dir = dir)))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!inView && weapon.snap != null) {
                val dir = Dir.toward(tgt.x - sel.x, tgt.y - sel.y)
                BarButton("FACE IT ${SquadRules.turnCost(sel.facing, dir)}AP", true, false, Modifier.weight(1f)) {
                    add(listOf(Order(sel.id, OrderType.FACE, dir = dir)))
                }
            }
            BarButton("CANCEL", true, false, Modifier.weight(1f), onClick = onCancel)
        }
    }

    // ---- Turn report ----

    @Composable
    private fun ReportPanel(
        report: List<Event>,
        index: Int,
        replaying: Boolean,
        roster: Map<Int, SquadUnit>,
        myUid: String,
        expanded: Boolean,
        onSkip: () -> Unit,
        onToggle: () -> Unit
    ) {
        val lines = describeAll(if (replaying) report.take(index + 1) else report, roster, myUid)
        if (lines.isEmpty()) return
        val shown = if (expanded) lines else lines.takeLast(COLLAPSED_LOG_LINES)
        Column(
            Modifier.fillMaxWidth().background(PixelPalette.surface)
                .clickable(onClick = if (replaying) onSkip else onToggle)
                .padding(8.dp)
        ) {
            Text("Last turn", style = MaterialTheme.typography.labelLarge)
            for ((i, line) in shown.withIndex()) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (i == shown.lastIndex) PixelPalette.secondary else PixelPalette.text
                )
            }
            Text(
                when {
                    replaying -> "tap to skip"
                    expanded -> "tap to show less"
                    lines.size > COLLAPSED_LOG_LINES -> "tap for the whole turn (${lines.size} lines)"
                    else -> ""
                },
                style = MaterialTheme.typography.labelMedium,
                color = PixelPalette.text.copy(alpha = 0.5f)
            )
        }
    }

    /** One line per event, with a unit's consecutive steps merged into one line. */
    private fun describeAll(events: List<Event>, roster: Map<Int, SquadUnit>, myUid: String): List<String> {
        val lines = mutableListOf<String>()
        var i = 0
        while (i < events.size) {
            val e = events[i]
            if (e.kind == EventKind.MOVE) {
                var steps = 1
                while (i + steps < events.size && events[i + steps].kind == EventKind.MOVE && events[i + steps].unit == e.unit) steps += 1
                lines += "${name(e.unit, roster, myUid)} moved $steps square${if (steps > 1) "s" else ""}."
                i += steps
                continue
            }
            describe(e, roster, myUid)?.let { lines += it }
            i += 1
        }
        return lines
    }

    private fun name(id: Int, roster: Map<Int, SquadUnit>, myUid: String): String {
        val u = roster[id] ?: return "Someone"
        return if (u.owner == myUid) u.name else "Enemy ${u.stats.name.lowercase()} ${u.name}"
    }

    private fun describe(e: Event, roster: Map<Int, SquadUnit>, myUid: String): String? {
        val who = name(e.unit, roster, myUid)
        val whom = name(e.target, roster, myUid)
        fun result() = if (e.hit) "hit for ${e.damage} (${e.chance}%)" else "missed (${e.chance}%)"
        return when (e.kind) {
            EventKind.TURN -> null
            EventKind.SHOT -> "$who ${if (e.reaction) "fired on" else "shot at"} $whom: ${result()}."
            EventKind.MELEE -> "$who struck $whom: ${result()}."
            EventKind.THROW -> "$who threw a grenade${if (e.hit) "" else ", it bounced"}."
            EventKind.BLAST -> "A grenade exploded."
            EventKind.HURT -> "$who caught the blast: ${e.damage} damage."
            EventKind.KILL -> "$who is down."
            EventKind.SABOTAGE -> "$who destroyed a core!"
            EventKind.ZONE -> "The centre was held: +${SquadContent.ZONE_POINTS}."
            EventKind.HALT -> "$who stopped: enemy spotted."
            EventKind.SKIP -> "$who: ${reasonText[e.reason] ?: "order skipped"}."
            else -> null
        }
    }

    // ---- Buttons ----

    @Composable
    private fun BarButton(label: String, enabled: Boolean, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
        Box(
            modifier = modifier
                .background(if (selected) PixelPalette.primary.copy(alpha = 0.25f) else PixelPalette.background)
                .border(2.dp, buttonBorder(enabled, selected))
                .clickable(enabled = enabled || selected, onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                color = if (enabled || selected) PixelPalette.text else PixelPalette.text.copy(alpha = 0.4f)
            )
        }
    }

    private fun buttonBorder(enabled: Boolean, selected: Boolean): Color = when {
        selected -> PixelPalette.secondary
        enabled -> PixelPalette.primary
        else -> PixelPalette.surface
    }
}
