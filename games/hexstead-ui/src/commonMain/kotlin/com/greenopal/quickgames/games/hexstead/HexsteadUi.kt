package com.greenopal.quickgames.games.hexstead

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.greenopal.quickgames.core.model.GameDoc
import com.greenopal.quickgames.core.model.GameStatus
import com.greenopal.quickgames.core.model.GameType
import com.greenopal.quickgames.core.ui.GameUi
import com.greenopal.quickgames.core.ui.PixelPalette

/** How many hex sides wide the board area is: 5 for the hexes plus a margin for pieces on the rim. */
private const val BOARD_SIDES = 5.6f
private const val HEX_HALF_HEIGHT = 0.866f
private const val COLLAPSED_LOG_LINES = 3

private val myColor = Color(0xFF4FC3F7)
private val rivalColor = Color(0xFFAB47BC)
private val tokenColor = Color(0xFFF3E9D2)
private val hotNumberColor = Color(0xFFC62828)

private fun resourceColor(resource: String): Color = when (resource) {
    Res.BRICK -> Color(0xFFC0583F)
    Res.WOOD -> Color(0xFF4C9A52)
    Res.SHEEP -> Color(0xFFA5D66B)
    Res.WHEAT -> Color(0xFFE6B94A)
    else -> Color(0xFF8E97A6)
}

private enum class Mode { NONE, ROAD, SETTLEMENT, CITY, TRADE }

object HexsteadUi : GameUi {
    override val gameType = GameType.HEXSTEAD

    @Composable
    override fun GameScreen(game: GameDoc, myUid: String, onSubmit: (String) -> Unit) {
        val state = remember(game.stateJson) { HexsteadJson.json.decodeFromString<HexsteadState>(game.stateJson) }
        val me = state.players[myUid] ?: return
        val rivalUid = state.opponentOf(myUid)
        val rival = state.players.getValue(rivalUid)
        val names = remember(game.players, myUid) {
            game.players.associate { it.uid to if (it.uid == myUid) "You" else it.displayName }
        }
        val rivalName = names[rivalUid] ?: "Opponent"

        var mode by remember(game.round) { mutableStateOf(Mode.NONE) }
        var setupVertex by remember(game.round) { mutableStateOf<Int?>(null) }
        var submitted by remember(game.round) { mutableStateOf(false) }
        var picks by remember(game.round) { mutableStateOf(listOf<String>()) }
        var tradeGive by remember(game.round) { mutableStateOf<String?>(null) }
        var tradeGet by remember(game.round) { mutableStateOf<String?>(null) }
        var showRivalHand by remember { mutableStateOf(false) }
        var showLog by remember { mutableStateOf(false) }

        fun send(action: Action) {
            submitted = true
            mode = Mode.NONE
            onSubmit(HexsteadJson.json.encodeToString(action))
        }

        val canAct = game.isMyTurn(myUid) && !submitted
        val setup = state.phase == Phase.SETUP
        val pending = state.pending
        val freeRoads = pending?.kind == PendingKind.FREE_ROADS
        val chosenVertex = setupVertex

        val litVertices = when {
            !canAct -> emptyList()
            setup -> if (chosenVertex == null) Rules.setupVertices(state) else emptyList()
            pending != null -> emptyList()
            mode == Mode.SETTLEMENT -> Rules.legalSettlementVertices(state, myUid)
            mode == Mode.CITY -> Rules.legalCityVertices(state, myUid)
            else -> emptyList()
        }
        val litEdges = when {
            !canAct -> emptyList()
            setup -> if (chosenVertex != null) Rules.setupEdges(state, chosenVertex) else emptyList()
            freeRoads || (pending == null && mode == Mode.ROAD) -> Rules.legalRoadEdges(state, myUid)
            else -> emptyList()
        }

        Column(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PlayerStrip(
                    name = rivalName,
                    color = rivalColor,
                    state = state,
                    uid = rivalUid,
                    detail = if (showRivalHand) handText(rival) else "${rival.total()} cards (tap to see)",
                    onClick = { showRivalHand = !showRivalHand }
                )
                BoardView(
                    state = state,
                    myUid = myUid,
                    litVertices = litVertices,
                    litEdges = litEdges,
                    ghostVertex = if (canAct) chosenVertex else null,
                    onVertex = { vertex ->
                        when {
                            setup -> setupVertex = vertex
                            mode == Mode.SETTLEMENT -> send(Action(ActionType.SETTLEMENT, vertex = vertex))
                            else -> send(Action(ActionType.CITY, vertex = vertex))
                        }
                    },
                    onEdge = { edge ->
                        if (setup) {
                            send(Action(ActionType.SETUP, vertex = chosenVertex, edge = edge))
                        } else {
                            send(Action(ActionType.ROAD, edge = edge))
                        }
                    }
                )
                EventPanel(state, names, showLog) { showLog = !showLog }
                PlayerStrip(
                    name = "You",
                    color = myColor,
                    state = state,
                    uid = myUid,
                    detail = "Cards left in the deck: ${state.deck.values.sum()}",
                    onClick = null
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (resource in Res.all) {
                        ResourceChip(resource, me.count(resource).toString(), Modifier.weight(1f))
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth().background(PixelPalette.surface).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val finished = game.status != GameStatus.ACTIVE
                Text(
                    text = statusText(game, state, myUid, rivalName, submitted, mode, chosenVertex),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (canAct || finished) PixelPalette.primary else PixelPalette.text
                )
                when {
                    !canAct -> Unit
                    setup -> if (chosenVertex != null) {
                        BarButton("PICK A DIFFERENT CORNER", true, false, Modifier.fillMaxWidth()) { setupVertex = null }
                    }
                    freeRoads -> Unit
                    pending != null -> {
                        ResourcePicker(enabled = { true }, selected = { it in picks }) { resource ->
                            picks = (picks + resource).takeLast(pending.count)
                        }
                        val ready = picks.size == pending.count
                        BarButton(
                            label = if (ready) "TAKE ${picks.joinToString(" + ").uppercase()}" else "PICK ${pending.count}",
                            enabled = ready,
                            selected = false,
                            modifier = Modifier.fillMaxWidth()
                        ) { send(Action(ActionType.CHOOSE, resources = picks)) }
                    }
                    mode == Mode.TRADE -> {
                        Text("Give ${Limits.TRADE_RATE} of:", style = MaterialTheme.typography.labelMedium)
                        ResourcePicker(enabled = { me.count(it) >= Limits.TRADE_RATE }, selected = { it == tradeGive }) { tradeGive = it }
                        Text("Get 1 of:", style = MaterialTheme.typography.labelMedium)
                        ResourcePicker(enabled = { it != tradeGive }, selected = { it == tradeGet }) { tradeGet = it }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val give = tradeGive
                            val get = tradeGet
                            BarButton("TRADE", give != null && get != null && give != get, false, Modifier.weight(1f)) {
                                send(Action(ActionType.TRADE, give = give, get = get))
                            }
                            BarButton("CANCEL", true, false, Modifier.weight(1f)) { mode = Mode.NONE }
                        }
                    }
                    else -> {
                        fun toggle(target: Mode) {
                            mode = if (mode == target) Mode.NONE else target
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            CostButton(
                                "ROAD", Costs.road, Modifier.weight(1f), mode == Mode.ROAD,
                                me.canAfford(Costs.road) && Rules.legalRoadEdges(state, myUid).isNotEmpty()
                            ) { toggle(Mode.ROAD) }
                            CostButton(
                                "SETTLE", Costs.settlement, Modifier.weight(1f), mode == Mode.SETTLEMENT,
                                me.canAfford(Costs.settlement) && Rules.legalSettlementVertices(state, myUid).isNotEmpty()
                            ) { toggle(Mode.SETTLEMENT) }
                            CostButton(
                                "CITY", Costs.city, Modifier.weight(1f), mode == Mode.CITY,
                                me.canAfford(Costs.city) && Rules.legalCityVertices(state, myUid).isNotEmpty()
                            ) { toggle(Mode.CITY) }
                            CostButton(
                                "CARD", Costs.card, Modifier.weight(1f), false,
                                me.canAfford(Costs.card) && state.deck.values.sum() > 0
                            ) { send(Action(ActionType.BUY_CARD)) }
                            CostButton(
                                "TRADE", emptyMap(), Modifier.weight(1f), false,
                                Res.all.any { me.count(it) >= Limits.TRADE_RATE }
                            ) {
                                tradeGive = null
                                tradeGet = null
                                mode = Mode.TRADE
                            }
                        }
                        BarButton("END TURN", true, false, Modifier.fillMaxWidth()) { send(Action(ActionType.END_TURN)) }
                    }
                }
            }
        }
    }

    private fun statusText(
        game: GameDoc,
        state: HexsteadState,
        myUid: String,
        rivalName: String,
        submitted: Boolean,
        mode: Mode,
        setupVertex: Int?
    ): String {
        val pending = state.pending
        return when {
            game.status == GameStatus.FORFEITED && game.winnerUid == myUid -> "Opponent forfeited - you win!"
            game.status == GameStatus.FORFEITED -> "Forfeited - you lose"
            state.winner == myUid -> "You win with ${Rules.victoryPoints(state, myUid)} points!"
            state.winner != null -> "$rivalName wins with ${Rules.victoryPoints(state, state.opponentOf(myUid))} points"
            submitted -> "Sending..."
            !game.isMyTurn(myUid) -> "Waiting for $rivalName"
            state.phase == Phase.SETUP && setupVertex == null -> "Tap a corner to place a settlement"
            state.phase == Phase.SETUP -> "Now tap a road next to it"
            pending?.kind == PendingKind.FREE_ROADS -> "Road Crew: tap to place ${pending.count} free road(s)"
            pending?.kind == PendingKind.WINDFALL -> "Windfall: pick any 2 resources"
            pending?.kind == PendingKind.LEVY -> "Levy: pick a resource to take from $rivalName"
            mode == Mode.ROAD -> "Tap a lit spot to build a road"
            mode == Mode.SETTLEMENT -> "Tap a lit corner to build a settlement"
            mode == Mode.CITY -> "Tap a lit settlement to make it a city"
            mode == Mode.TRADE -> "Trade with the bank"
            else -> "Your turn"
        }
    }

    private fun handText(player: PlayerState): String =
        Res.all.joinToString("  ") { "${it} ${player.count(it)}" }

    // ---- Board ----

    @Composable
    private fun BoardView(
        state: HexsteadState,
        myUid: String,
        litVertices: List<Int>,
        litEdges: List<Int>,
        ghostVertex: Int?,
        onVertex: (Int) -> Unit,
        onEdge: (Int) -> Unit
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val side = maxWidth / BOARD_SIDES
            val stepX = side / 2
            val stepY = side * HEX_HALF_HEIGHT
            val boardHeight = stepY * (2 * HexBoard.HALF_HEIGHT) + side * (BOARD_SIDES - 2 * HexBoard.HALF_WIDTH / 2f)
            val centerX = maxWidth / 2
            val centerY = boardHeight / 2
            fun xOf(point: HexBoard.Point): Dp = centerX + stepX * point.x
            fun yOf(point: HexBoard.Point): Dp = centerY + stepY * point.y

            Box(Modifier.fillMaxWidth().height(boardHeight)) {
                Canvas(Modifier.matchParentSize()) {
                    val s = side.toPx()
                    fun at(point: HexBoard.Point) = Offset(
                        size.width / 2 + point.x * s / 2,
                        size.height / 2 + point.y * s * HEX_HALF_HEIGHT
                    )
                    for (hex in state.hexes.indices) {
                        val corners = HexBoard.hexVertices[hex].map { at(HexBoard.vertices[it]) }
                        val path = Path().apply {
                            moveTo(corners[0].x, corners[0].y)
                            for (corner in corners.drop(1)) lineTo(corner.x, corner.y)
                            close()
                        }
                        drawPath(path, resourceColor(state.hexes[hex].resource))
                        drawPath(path, PixelPalette.background, style = Stroke(width = s * 0.05f))
                    }
                    for ((edge, owner) in state.roads) {
                        val ends = HexBoard.edges[edge]
                        val a = at(HexBoard.vertices[ends.a])
                        val b = at(HexBoard.vertices[ends.b])
                        val from = a + (b - a) * 0.2f
                        val to = a + (b - a) * 0.8f
                        drawLine(Color.White, from, to, strokeWidth = s * 0.17f, cap = StrokeCap.Round)
                        drawLine(pieceColor(owner, myUid), from, to, strokeWidth = s * 0.11f, cap = StrokeCap.Round)
                    }
                    for ((vertex, building) in state.buildings) {
                        drawBuilding(at(HexBoard.vertices[vertex]), s, pieceColor(building.owner, myUid), building.city)
                    }
                    if (ghostVertex != null) {
                        drawBuilding(at(HexBoard.vertices[ghostVertex]), s, myColor, city = false)
                    }
                }
                for (hex in state.hexes.indices) {
                    val tile = state.hexes[hex]
                    val center = HexBoard.hexCenters[hex]
                    Column(
                        modifier = Modifier.offset(xOf(center) - 32.dp, yOf(center) - 24.dp).size(64.dp, 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(tile.resource.uppercase(), style = MaterialTheme.typography.labelMedium, color = Color.Black)
                        Box(Modifier.size(28.dp).background(tokenColor, CircleShape), contentAlignment = Alignment.Center) {
                            Text(
                                tile.number.toString(),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (tile.number == 6 || tile.number == 8) hotNumberColor else Color.Black
                            )
                        }
                    }
                }
                for (vertex in litVertices) {
                    val point = HexBoard.vertices[vertex]
                    TapSpot(xOf(point), yOf(point), 44.dp, 22.dp) { onVertex(vertex) }
                }
                for (edge in litEdges) {
                    val a = HexBoard.vertices[HexBoard.edges[edge].a]
                    val b = HexBoard.vertices[HexBoard.edges[edge].b]
                    TapSpot((xOf(a) + xOf(b)) / 2, (yOf(a) + yOf(b)) / 2, 40.dp, 18.dp) { onEdge(edge) }
                }
            }
        }
    }

    private fun pieceColor(owner: String, myUid: String): Color = if (owner == myUid) myColor else rivalColor

    private fun DrawScope.drawBuilding(center: Offset, side: Float, color: Color, city: Boolean) {
        val half = side * (if (city) 0.2f else 0.13f)
        val topLeft = Offset(center.x - half, center.y - half)
        val box = Size(half * 2, half * 2)
        drawRect(color, topLeft, box)
        drawRect(Color.White, topLeft, box, style = Stroke(width = side * 0.035f))
        if (city) drawCircle(Color.White, radius = half * 0.4f, center = center)
    }

    @Composable
    private fun TapSpot(x: Dp, y: Dp, target: Dp, dot: Dp, onClick: () -> Unit) {
        Box(
            modifier = Modifier.offset(x - target / 2, y - target / 2).size(target).clip(CircleShape).clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(dot).background(PixelPalette.secondary, CircleShape).border(2.dp, Color.Black, CircleShape))
        }
    }

    // ---- Strips and panels ----

    @Composable
    private fun PlayerStrip(name: String, color: Color, state: HexsteadState, uid: String, detail: String, onClick: (() -> Unit)?) {
        val player = state.players.getValue(uid)
        val awards = buildList {
            if (state.longestRoadHolder == uid) add("LONGEST ROAD")
            if (state.mostRaidersHolder == uid) add("MOST RAIDERS")
        }
        Column(
            Modifier.fillMaxWidth().background(PixelPalette.surface)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(12.dp).background(color).border(1.dp, Color.White))
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${Rules.victoryPoints(state, uid)}/${Limits.WIN_VP} VP",
                    style = MaterialTheme.typography.titleMedium,
                    color = PixelPalette.secondary
                )
                Text("Raiders ${player.raiders}", style = MaterialTheme.typography.bodyMedium)
            }
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            if (awards.isNotEmpty()) {
                Text(awards.joinToString("  ") { "* $it +${Limits.AWARD_VP}" }, style = MaterialTheme.typography.labelMedium, color = PixelPalette.accent)
            }
        }
    }

    @Composable
    private fun EventPanel(state: HexsteadState, names: Map<String, String>, expanded: Boolean, onToggle: () -> Unit) {
        val lines = (state.lastTurnEvents + state.events).map { describe(it, names) }
        if (lines.isEmpty()) return
        val shown = if (expanded) lines else lines.takeLast(COLLAPSED_LOG_LINES)
        Column(Modifier.fillMaxWidth().background(PixelPalette.surface).clickable(onClick = onToggle).padding(8.dp)) {
            for ((index, line) in shown.withIndex()) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (index == shown.lastIndex) PixelPalette.secondary else PixelPalette.text
                )
            }
            if (lines.size > COLLAPSED_LOG_LINES) {
                Text(
                    if (expanded) "tap to show less" else "tap for the whole turn (${lines.size} lines)",
                    style = MaterialTheme.typography.labelMedium,
                    color = PixelPalette.text.copy(alpha = 0.5f)
                )
            }
        }
    }

    private fun amounts(gains: Map<String, Int>): String =
        gains.entries.joinToString(", ") { (resource, n) -> "$n $resource" }

    private fun describe(event: Event, names: Map<String, String>): String {
        val who = names[event.uid] ?: "Opponent"
        val rivalOfWho = names.entries.firstOrNull { it.key != event.uid }?.value ?: "Opponent"
        return when (event.kind) {
            EventKind.SETUP -> {
                val got = event.gains[event.uid]
                "$who placed a settlement" + if (got.isNullOrEmpty()) "." else " and got ${amounts(got)}."
            }
            EventKind.ROLL -> {
                val produced = event.gains.entries.joinToString(" ") { (uid, got) -> "${names[uid] ?: "Opponent"} +${amounts(got)}." }
                "$who rolled ${event.count} (${event.dice.joinToString("+")}). " + produced.ifEmpty { "Nothing produced." }
            }
            EventKind.DISCARD -> "$who lost ${event.count} cards to the hand limit: ${amounts(event.gains[event.uid].orEmpty())}."
            EventKind.ROAD -> "$who built a road."
            EventKind.SETTLEMENT -> "$who built a settlement."
            EventKind.CITY -> "$who built a city."
            EventKind.TRADE -> "$who traded ${Limits.TRADE_RATE} ${event.given} for 1 ${event.resource}."
            EventKind.CARD -> "$who drew " + when (event.card) {
                Cards.RAIDER -> "a Raider and stole " + (event.resource?.let { "1 $it from $rivalOfWho." } ?: "nothing.")
                Cards.MONUMENT -> "a Monument: +1 VP."
                Cards.ROAD_CREW -> "Road Crew: " + if (event.count > 0) "${event.count} free road(s)." else "no room for roads."
                Cards.WINDFALL -> "Windfall: any 2 resources."
                else -> "Levy: all of one resource from $rivalOfWho."
            }
            EventKind.CHOICE ->
                if (event.card == Cards.WINDFALL) {
                    "$who took ${amounts(event.gains[event.uid].orEmpty())}."
                } else {
                    "$who took all ${event.count} ${event.resource} from $rivalOfWho."
                }
            EventKind.AWARD ->
                "$who took the ${if (event.card == Award.LONGEST_ROAD) "Longest Road" else "Most Raiders"} award: +${Limits.AWARD_VP} VP."
            else -> "$who won!"
        }
    }

    // ---- Buttons and chips ----

    @Composable
    private fun ResourceChip(resource: String, count: String, modifier: Modifier) {
        Column(
            modifier.background(resourceColor(resource)).padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(resource.uppercase(), style = MaterialTheme.typography.labelMedium, color = Color.Black)
            Text(count, style = MaterialTheme.typography.titleLarge, color = Color.Black)
        }
    }

    @Composable
    private fun ResourcePicker(enabled: (String) -> Boolean, selected: (String) -> Boolean, onPick: (String) -> Unit) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (resource in Res.all) {
                val on = enabled(resource)
                Box(
                    modifier = Modifier.weight(1f)
                        .background(resourceColor(resource).copy(alpha = if (on) 1f else 0.25f))
                        .border(3.dp, if (selected(resource)) Color.White else Color.Transparent)
                        .clickable(enabled = on) { onPick(resource) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(resource.uppercase(), style = MaterialTheme.typography.labelMedium, color = Color.Black)
                }
            }
        }
    }

    @Composable
    private fun CostButton(
        label: String,
        cost: Map<String, Int>,
        modifier: Modifier,
        selected: Boolean,
        enabled: Boolean,
        onClick: () -> Unit
    ) {
        Column(
            modifier = modifier
                .background(if (selected) PixelPalette.primary.copy(alpha = 0.25f) else PixelPalette.background)
                .border(2.dp, buttonBorder(enabled, selected))
                .clickable(enabled = enabled || selected, onClick = onClick)
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled || selected) PixelPalette.text else PixelPalette.text.copy(alpha = 0.4f)
            )
            Row(Modifier.height(8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (cost.isEmpty()) {
                    Text("${Limits.TRADE_RATE}:1", style = MaterialTheme.typography.labelMedium, color = PixelPalette.text.copy(alpha = 0.4f))
                }
                for ((resource, n) in cost) {
                    repeat(n) {
                        Box(Modifier.size(8.dp).background(resourceColor(resource).copy(alpha = if (enabled || selected) 1f else 0.4f)))
                    }
                }
            }
        }
    }

    @Composable
    private fun BarButton(label: String, enabled: Boolean, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
        Box(
            modifier = modifier
                .background(PixelPalette.background)
                .border(2.dp, buttonBorder(enabled, selected))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                color = if (enabled) PixelPalette.text else PixelPalette.text.copy(alpha = 0.4f)
            )
        }
    }

    private fun buttonBorder(enabled: Boolean, selected: Boolean): Color = when {
        selected -> PixelPalette.secondary
        enabled -> PixelPalette.primary
        else -> PixelPalette.surface
    }
}
