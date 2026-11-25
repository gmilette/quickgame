package com.greenopal.quickgames.games.spiceroad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.greenopal.quickgames.core.model.GameDoc
import com.greenopal.quickgames.core.model.GameStatus
import com.greenopal.quickgames.core.model.GameType
import com.greenopal.quickgames.core.ui.GameUi
import com.greenopal.quickgames.core.ui.PixelPalette

object SpiceRoadUi : GameUi {
    override val gameType = GameType.SPICE_ROAD

    private val abbreviations = mapOf(
        Spice.PEPPER to "PEP", Spice.CINNAMON to "CIN", Spice.GINGER to "GIN",
        Spice.CARDAMOM to "CAR", Spice.CLOVES to "CLO", Spice.NUTMEG to "NUT",
        Spice.VANILLA to "VAN", Spice.STAR_ANISE to "ANI",
        Spice.SAFFRON to "SAF", Spice.FRANKINCENSE to "FRA"
    )

    private fun name(spice: Spice): String =
        spice.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun tierColor(spice: Spice): Color = when (spice.price) {
        1 -> PixelPalette.text
        4 -> PixelPalette.accent
        8 -> PixelPalette.secondary
        else -> PixelPalette.danger
    }

    private fun describe(units: Map<Spice, Int>): String =
        if (units.isEmpty()) "nothing" else units.entries.joinToString(", ") { "${it.value} ${name(it.key)}" }

    private val errorText = mapOf(
        "paymentTooSmall" to "You can't afford this yet",
        "paymentNeedsMoreSpices" to "You need more different spices for this monument",
        "tileNotClaimable" to "You can't claim that tile",
        "upgradeMaxed" to "That upgrade is at its top level",
        "tileAssignedTwice" to "Two caravans can't work the same tile",
        "exchangeNotInStock" to "You don't have the spice for that exchange"
    )

    @Composable
    override fun GameScreen(game: GameDoc, myUid: String, onSubmit: (String) -> Unit) {
        val state = remember(game.stateJson) { SpiceJson.json.decodeFromString<SpiceState>(game.stateJson) }
        val oppUid = state.opponentOf(myUid)
        val oppName = game.opponentOf(myUid)?.displayName ?: "Opponent"
        val me = state.traders.getValue(myUid)
        val opp = state.traders.getValue(oppUid)
        val myTurn = game.isMyTurn(myUid)

        var action by remember(game.stateJson) { mutableStateOf<String?>(null) }
        var exploreTile by remember(game.stateJson) { mutableStateOf<Int?>(null) }
        var exchanges by remember(game.stateJson) { mutableStateOf(listOf<Exchange>()) }
        var overrides by remember(game.stateJson) { mutableStateOf(mapOf<Int, Int>()) }
        var selectedCaravan by remember(game.stateJson) { mutableStateOf<Int?>(null) }
        var giveSpice by remember(game.stateJson) { mutableStateOf<Spice?>(null) }
        var takeSpice by remember(game.stateJson) { mutableStateOf<Spice?>(null) }
        var submitted by remember(game.stateJson) { mutableStateOf(false) }

        val trading = SpiceRules.afterExchanges(me, exchanges)
        val claimable = remember(state) { SpiceRules.claimable(state, myUid).map { it.id }.toSet() }
        val pendingTile = exploreTile.takeIf { action == SpiceContent.EXPLORE }
        val cost = action?.let { SpiceRules.cost(state, myUid, it, pendingTile) }
        val kinds = if (action == SpiceContent.MONUMENT) SpiceContent.monumentKinds(me.monuments) else 0
        val payment = cost?.let { SpiceRules.suggestPayment(trading, it, kinds) }
        val assignments = assignments(state, myUid, me, action, pendingTile, overrides)
        val turn = action?.let { SpiceTurn(it, pendingTile, payment ?: emptyMap(), exchanges, assignments) }
        val outcome = turn?.let { SpiceRules.play(state, myUid, it) }
        val canAct = myTurn && !submitted

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (state.complete) "Final hour reached" else "Hour ${state.hour} of ${state.totalHours}",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    statusText(game, state, myUid, myTurn, submitted),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (myTurn) PixelPalette.primary else PixelPalette.text
                )
                Text(
                    "Monuments: you ${me.monuments} - ${opp.monuments} $oppName",
                    style = MaterialTheme.typography.titleMedium,
                    color = PixelPalette.secondary
                )
            }

            Board(state, myUid, me, claimable, pendingTile, assignments, canAct) { id ->
                val caravan = selectedCaravan
                val tile = state.tiles[id]
                if (caravan != null && (tile.owner == myUid && tile.kind == SpiceContent.SOURCE || id == pendingTile)) {
                    overrides = overrides.filterValues { it != id } + (caravan to id)
                    selectedCaravan = null
                } else if (id in claimable) {
                    action = SpiceContent.EXPLORE
                    exploreTile = id
                }
            }

            Panel("YOUR SPICE (worth ${trading.stockValue()})") {
                if (trading.stock.isEmpty()) Text("None yet", style = MaterialTheme.typography.bodyMedium)
                for ((spice, units) in trading.stock.entries.sortedBy { it.key.ordinal }) {
                    Text(
                        "$units ${name(spice)}  (${spice.price} each)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = tierColor(spice)
                    )
                }
                if (state.lastDeliveries.isNotEmpty()) {
                    val mine = state.lastDeliveries.filter { it.uid == myUid }
                    val theirs = state.lastDeliveries.filter { it.uid == oppUid }
                    Text(
                        "Last hour: you +" + describe(sum(mine)) + ", $oppName +" + describe(sum(theirs)),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            Panel("CARAVANS: ${level(SpiceContent.caravanNames, me.caravans.size - 1)}") {
                Text(
                    "${level(SpiceContent.loadNames, me.loadLevel)} carry ${me.capacity} | " +
                        "${level(SpiceContent.camelNames, me.camelLevel)} save ${me.camelLevel}h",
                    style = MaterialTheme.typography.labelMedium
                )
                assignments.forEachIndexed { i, tileId ->
                    val caravan = me.caravans.getOrNull(i)
                    val route = tileId?.let { id ->
                        val spice = state.tiles[id].spice?.let { name(it) } ?: "?"
                        val trip = SpiceRules.tripHours(state, myUid, id)
                        "$spice, " + (trip?.let { "${it}h trip" } ?: "route blocked")
                    } ?: "no route"
                    val onRoad = caravan?.target?.let { ", back in ${caravan.hoursLeft}h" } ?: ""
                    Text(
                        "${i + 1}: $route$onRoad",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth()
                            .border(1.dp, if (selectedCaravan == i) PixelPalette.secondary else Color.Transparent)
                            .clickable(enabled = canAct) { selectedCaravan = if (selectedCaravan == i) null else i }
                            .padding(4.dp)
                    )
                }
                Text(
                    if (selectedCaravan != null) "Now tap one of your tiles to send this caravan there"
                    else "Tap a caravan, then one of your tiles, to change its route",
                    style = MaterialTheme.typography.labelMedium
                )
            }

            if (game.status == GameStatus.ACTIVE && !state.complete) {
                Panel("ACTION (one per hour)") {
                    val monumentCost = SpiceContent.monumentCost(me.monuments)
                    val options = listOf(
                        SpiceContent.EXPLORE to "Explore\ntap a lit tile",
                        SpiceContent.MONUMENT to "${SpiceContent.monumentName(me.monuments)}\n$monumentCost, ${SpiceContent.monumentKinds(me.monuments)} spices",
                        SpiceContent.HIRE to upgradeLabel(me, SpiceContent.HIRE, SpiceContent.caravanNames, me.caravans.size),
                        SpiceContent.LOADS to upgradeLabel(me, SpiceContent.LOADS, SpiceContent.loadNames, me.loadLevel + 1),
                        SpiceContent.CAMELS to upgradeLabel(me, SpiceContent.CAMELS, SpiceContent.camelNames, me.camelLevel + 1),
                        SpiceContent.WAIT to "Wait\nno action"
                    )
                    for (row in options.chunked(2)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for ((id, label) in row) {
                                Choice(label, selected = action == id, enabled = canAct, modifier = Modifier.weight(1f)) {
                                    action = id
                                }
                            }
                        }
                    }
                }

                Panel("EXCHANGE (give double the value you take)") {
                    val revealed = state.tiles.filter { it.faceUp }.mapNotNull { it.spice }.distinct().sortedBy { it.ordinal }
                    Text("Give", style = MaterialTheme.typography.labelMedium)
                    SpiceChips(trading.stock.keys.sortedBy { it.ordinal }, giveSpice, canAct) { giveSpice = it }
                    Text("Take", style = MaterialTheme.typography.labelMedium)
                    SpiceChips(revealed, takeSpice, canAct) { takeSpice = it }
                    val give = giveSpice
                    val take = takeSpice
                    if (give != null && take != null && give != take) {
                        val units = (SpiceContent.EXCHANGE_RATE * take.price + give.price - 1) / give.price
                        val affordable = (trading.stock[give] ?: 0) >= units
                        Choice(
                            "Swap $units ${name(give)} for 1 ${name(take)}",
                            selected = false,
                            enabled = canAct && affordable,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            exchanges = exchanges + Exchange(mapOf(give to units), mapOf(take to 1))
                        }
                    }
                    if (exchanges.isNotEmpty()) {
                        Choice("Undo ${exchanges.size} swap(s)", selected = false, enabled = canAct, modifier = Modifier.fillMaxWidth()) {
                            exchanges = emptyList()
                        }
                    }
                }

                Panel("CONFIRM") {
                    val summary = when {
                        turn == null -> "Choose an action above"
                        action == SpiceContent.EXPLORE && pendingTile == null -> "Tap a lit tile on the map"
                        outcome?.state == null -> errorText[outcome?.errors?.firstOrNull()] ?: "Not allowed: ${outcome?.errors?.firstOrNull()}"
                        cost == 0 -> "No cost"
                        else -> "Cost $cost. You pay ${describe(payment ?: emptyMap())}"
                    }
                    Text(summary, style = MaterialTheme.typography.bodyMedium)
                    Choice(
                        if (submitted) "Sent" else "END TURN",
                        selected = false,
                        enabled = canAct && outcome?.state != null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        submitted = true
                        onSubmit(SpiceJson.json.encodeToString(turn!!))
                    }
                }
            }

            Panel(oppName.uppercase()) {
                Text(
                    "${opp.monuments} monuments | spice worth ${opp.stockValue()}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "${level(SpiceContent.caravanNames, opp.caravans.size - 1)} (${opp.caravans.size}), " +
                        "${level(SpiceContent.loadNames, opp.loadLevel)}, ${level(SpiceContent.camelNames, opp.camelLevel)}",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }

    private fun level(names: List<String>, index: Int): String = names[index.coerceIn(0, names.size - 1)]

    private fun upgradeLabel(trader: Trader, action: String, names: List<String>, nextIndex: Int): String {
        val cost = SpiceRules.upgradeCost(trader, action) ?: return "${names.last()}\ntop level"
        return "${names[nextIndex]}\n$cost"
    }

    private fun sum(deliveries: List<Delivery>): Map<Spice, Int> =
        deliveries.groupBy { it.spice }.mapValues { entry -> entry.value.sumOf { it.units } }

    private fun assignments(
        state: SpiceState,
        uid: String,
        me: Trader,
        action: String?,
        pendingTile: Int?,
        overrides: Map<Int, Int>
    ): List<Int?> {
        val count = me.caravans.size + if (action == SpiceContent.HIRE) 1 else 0
        val result = MutableList(count) { i -> overrides[i] ?: me.caravans.getOrNull(i)?.next }
        for ((i, id) in overrides) {
            for (j in result.indices) if (j != i && result[j] == id) result[j] = null
        }
        val workable = (SpiceRules.ownedSources(state, uid).map { it.id } + listOfNotNull(pendingTile))
            .filter { it !in result }
            .sortedByDescending { id ->
                val trip = SpiceRules.tripHours(state, uid, id) ?: return@sortedByDescending 0.0
                (state.tiles[id].spice?.price ?: 0).toDouble() / trip
            }
            .toMutableList()
        for (i in result.indices) {
            if (result[i] == null && workable.isNotEmpty()) result[i] = workable.removeAt(0)
        }
        return result
    }

    private fun statusText(game: GameDoc, state: SpiceState, myUid: String, myTurn: Boolean, submitted: Boolean): String = when {
        game.status == GameStatus.FORFEITED && game.winnerUid == myUid -> "Opponent forfeited - you win!"
        game.status == GameStatus.FORFEITED -> "Forfeited - you lose"
        state.complete && state.winner == myUid -> "You win!"
        state.complete && state.winner == null -> "A draw"
        state.complete -> "You lose"
        submitted -> "Turn sent..."
        myTurn -> "Your turn"
        else -> "Waiting for opponent..."
    }

    @Composable
    private fun Board(
        state: SpiceState,
        myUid: String,
        me: Trader,
        claimable: Set<Int>,
        pendingTile: Int?,
        assignments: List<Int?>,
        enabled: Boolean,
        onTap: (Int) -> Unit
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            for (y in 0 until SpiceContent.SIZE) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (x in 0 until SpiceContent.SIZE) {
                        val tile = state.tiles[y * SpiceContent.SIZE + x]
                        val mine = tile.owner == myUid
                        val fill = when {
                            tile.owner == null -> PixelPalette.surface
                            mine -> PixelPalette.primary.copy(alpha = 0.25f)
                            else -> PixelPalette.danger.copy(alpha = 0.25f)
                        }
                        val edge = when {
                            tile.id == pendingTile -> PixelPalette.secondary
                            enabled && tile.id in claimable -> PixelPalette.accent
                            else -> Color.Transparent
                        }
                        val top = when (tile.kind) {
                            SpiceContent.HOME -> if (mine) "YOU" else "FOE"
                            SpiceContent.OASIS_KIND -> "OASIS"
                            else -> if (tile.faceUp) abbreviations.getValue(tile.spice!!) else "?"
                        }
                        val caravans = assignments.mapIndexedNotNull { i, id ->
                            if (id != tile.id) return@mapIndexedNotNull null
                            val c = me.caravans.getOrNull(i)
                            "C${i + 1}" + if (c?.target == tile.id) ":${c.hoursLeft}h" else ""
                        }.joinToString(" ")
                        Box(
                            modifier = Modifier.weight(1f).aspectRatio(1f).background(fill).border(2.dp, edge)
                                .clickable(enabled = enabled) { onTap(tile.id) },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    top,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = tile.spice?.takeIf { tile.faceUp }?.let { tierColor(it) } ?: PixelPalette.text
                                )
                                if (tile.faceUp && tile.spice != null) {
                                    Text("${tile.spice!!.price}", style = MaterialTheme.typography.labelMedium)
                                }
                                if (caravans.isNotEmpty()) {
                                    Text(caravans, style = MaterialTheme.typography.labelMedium, color = PixelPalette.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Panel(title: String, content: @Composable () -> Unit) {
        Column(
            modifier = Modifier.fillMaxWidth().background(PixelPalette.surface).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = PixelPalette.secondary)
            content()
        }
    }

    @Composable
    private fun SpiceChips(spices: List<Spice>, selected: Spice?, enabled: Boolean, onPick: (Spice) -> Unit) {
        for (row in spices.chunked(5)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (spice in row) {
                    Choice(abbreviations.getValue(spice), selected == spice, enabled, Modifier.weight(1f)) { onPick(spice) }
                }
                repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }

    @Composable
    private fun Choice(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
        Box(
            modifier = modifier
                .background(PixelPalette.background)
                .border(
                    2.dp,
                    when {
                        selected -> PixelPalette.secondary
                        enabled -> PixelPalette.primary
                        else -> PixelPalette.surface
                    }
                )
                .clickable(enabled = enabled, onClick = onClick)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                color = if (enabled || selected) PixelPalette.text else PixelPalette.text.copy(alpha = 0.4f)
            )
        }
    }
}
