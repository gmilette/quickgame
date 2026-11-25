package com.greenopal.quickgames.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.greenopal.quickgames.core.engine.GameDefinition
import com.greenopal.quickgames.core.engine.GameRegistry
import com.greenopal.quickgames.core.model.GameDoc
import com.greenopal.quickgames.core.model.GameStatus
import com.greenopal.quickgames.core.model.GameType
import com.greenopal.quickgames.core.model.OpenGame
import com.greenopal.quickgames.core.model.PlatformClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Menu : Screen
    data object EditName : Screen
    data object PickGame : Screen
    data object JoinList : Screen
    data class Waiting(val gameType: GameType, val variant: String, val alreadyHosted: Boolean) : Screen
    data class InGame(val gameId: String) : Screen
}

private const val MAX_NAME_LENGTH = 24
private const val JOIN_TIMEOUT_MILLIS = 30_000L
private const val JOIN_GONE_TIMEOUT_MILLIS = 8_000L

data class OpenGameRequest(val sequence: Int, val gameId: String)

@Composable
fun App(
    registry: GameRegistry,
    uiRegistry: GameUiRegistry,
    client: PlatformClient,
    resumeCount: Int = 0,
    openGameRequest: OpenGameRequest? = null,
    onSignedIn: (String) -> Unit = {}
) {
    QuickGamesTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding()
        ) {
            var uid by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                val signedInUid = client.signIn()
                uid = signedInUid
                onSignedIn(signedInUid)
            }
            val currentUid = uid
            if (currentUid == null) {
                CenteredMessage("Signing in...")
            } else {
                SignedInApp(registry, uiRegistry, client, currentUid, resumeCount, openGameRequest)
            }
        }
    }
}

@Composable
private fun SignedInApp(
    registry: GameRegistry,
    uiRegistry: GameUiRegistry,
    client: PlatformClient,
    uid: String,
    resumeCount: Int,
    openGameRequest: OpenGameRequest?
) {
    val scope = rememberCoroutineScope()
    var nameLoaded by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(uid) {
        name = runCatching { client.loadDisplayName(uid) }.getOrNull()
        nameLoaded = true
    }
    val saveName: (String) -> Unit = { newName ->
        name = newName
        scope.launch { runCatching { client.saveDisplayName(uid, newName) } }
    }

    val currentName = name
    if (!nameLoaded) {
        CenteredMessage("Loading...")
        return
    }
    if (currentName == null) {
        NameScreen(initial = "", onSave = saveName, onCancel = null)
        return
    }

    val loadedGames by remember(uid) { client.games(uid) }.collectAsState(initial = null)
    val loadedOpenGames by remember { client.openGames() }.collectAsState(initial = null)
    val games = loadedGames
    val openGames = loadedOpenGames
    if (games == null || openGames == null) {
        CenteredMessage("Loading games...")
        return
    }

    var screen by remember { mutableStateOf<Screen>(Screen.Menu) }
    var routedFor by remember { mutableStateOf(-1) }
    LaunchedEffect(resumeCount) {
        if (routedFor != resumeCount) {
            routedFor = resumeCount
            if (screen !is Screen.InGame && screen !is Screen.EditName) {
                screen = startScreen(games, openGames, uid)
            }
        }
    }
    LaunchedEffect(openGameRequest) {
        if (openGameRequest != null) {
            screen = Screen.InGame(openGameRequest.gameId)
        }
    }

    when (val current = screen) {
        is Screen.Menu -> MenuScreen(
            registry = registry,
            name = currentName,
            games = games,
            openGames = openGames,
            uid = uid,
            onStart = { screen = Screen.PickGame },
            onJoin = { screen = Screen.JoinList },
            onEditName = { screen = Screen.EditName },
            onOpenGame = { screen = Screen.InGame(it.id) },
            onOpenWaiting = { screen = Screen.Waiting(it.gameType, it.variant, alreadyHosted = true) }
        )
        is Screen.EditName -> NameScreen(
            initial = currentName,
            onSave = {
                saveName(it)
                screen = Screen.Menu
            },
            onCancel = { screen = Screen.Menu }
        )
        is Screen.PickGame -> PickGameScreen(
            registry = registry,
            onPick = { definition, variant ->
                val hosting = openGames.any { it.hostUid == uid && it.gameType == definition.gameType }
                screen = Screen.Waiting(definition.gameType, variant, alreadyHosted = hosting)
            },
            onBack = { screen = Screen.Menu }
        )
        is Screen.JoinList -> JoinListScreen(
            registry = registry,
            client = client,
            uid = uid,
            name = currentName,
            games = games,
            openGames = openGames,
            onMatched = { screen = Screen.InGame(it) },
            onStart = { screen = Screen.PickGame },
            onBack = { screen = Screen.Menu }
        )
        is Screen.Waiting -> WaitingScreen(
            registry = registry,
            client = client,
            uid = uid,
            name = currentName,
            waiting = current,
            games = games,
            onMatched = { screen = Screen.InGame(it) },
            onLeave = { screen = Screen.Menu }
        )
        is Screen.InGame -> GameHostScreen(
            registry = registry,
            client = client,
            uiRegistry = uiRegistry,
            gameId = current.gameId,
            uid = uid,
            onPlayAgain = { gameType, variant ->
                val hosting = openGames.any { it.hostUid == uid && it.gameType == gameType }
                screen = Screen.Waiting(gameType, variant, alreadyHosted = hosting)
            },
            onBack = { screen = Screen.Menu }
        )
    }
}

private fun startScreen(games: List<GameDoc>, openGames: List<OpenGame>, uid: String): Screen {
    val active = games.filter { it.status == GameStatus.ACTIVE }
    if (active.size == 1) return Screen.InGame(active.single().id)
    if (active.isEmpty()) {
        val hosted = openGames.firstOrNull { it.hostUid == uid && it.guestUid == null }
        if (hosted != null) return Screen.Waiting(hosted.gameType, hosted.variant, alreadyHosted = true)
    }
    return Screen.Menu
}

private fun gameName(registry: GameRegistry, gameType: GameType): String =
    registry.all[gameType]?.displayName ?: gameType.id

/** The game's name, plus the variant's for games that have more than one. */
private fun gameName(registry: GameRegistry, gameType: GameType, variant: String): String {
    val definition = registry.all[gameType] ?: return gameType.id
    return if (definition.configVariants.size > 1) {
        "${definition.displayName}: ${definition.variantLabel(variant)}"
    } else {
        definition.displayName
    }
}

@Composable
private fun NameScreen(initial: String, onSave: (String) -> Unit, onCancel: (() -> Unit)?) {
    var text by remember { mutableStateOf(initial) }
    val trimmed = text.trim()
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("WHAT SHOULD WE CALL YOU?", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("Other players see this name.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(MAX_NAME_LENGTH) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))
        Row {
            if (onCancel != null) {
                TextButton(onClick = onCancel) { Text("CANCEL") }
                Spacer(Modifier.width(16.dp))
            }
            TextButton(onClick = { onSave(trimmed) }, enabled = trimmed.isNotEmpty()) { Text("SAVE") }
        }
    }
}

@Composable
private fun MenuScreen(
    registry: GameRegistry,
    name: String,
    games: List<GameDoc>,
    openGames: List<OpenGame>,
    uid: String,
    onStart: () -> Unit,
    onJoin: () -> Unit,
    onEditName: () -> Unit,
    onOpenGame: (GameDoc) -> Unit,
    onOpenWaiting: (OpenGame) -> Unit
) {
    val yourTurn = games
        .filter { it.isMyTurn(uid) }
        .sortedBy { it.turnDeadlineMillis ?: Long.MAX_VALUE }
    val waiting = games.filter { it.status == GameStatus.ACTIVE && !it.isMyTurn(uid) }
    val finished = games.filter { it.status != GameStatus.ACTIVE }.take(10)
    val hosted = openGames.filter { it.hostUid == uid && it.guestUid == null }
    val joinable = joinableGames(registry, openGames, uid)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Text("QUICKGAMES", style = MaterialTheme.typography.headlineMedium) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Playing as $name", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onEditName) { Text("CHANGE NAME") }
            }
        }
        item { MenuCard("START A GAME", "Pick a game and wait for someone to join", onStart) }
        item {
            val detail = when (joinable.size) {
                0 -> "No games waiting right now"
                1 -> "1 game waiting for a player"
                else -> "${joinable.size} games waiting for a player"
            }
            MenuCard("JOIN A GAME", detail, onJoin)
        }
        if (hosted.isNotEmpty()) {
            item { SectionHeader("WAITING FOR A PLAYER") }
            items(hosted) { open ->
                ListRow(gameName(registry, open.gameType), "open") { onOpenWaiting(open) }
            }
        }
        if (yourTurn.isNotEmpty()) {
            item { SectionHeader("YOUR TURN") }
            items(yourTurn) { game -> GameRow(registry, game, uid, onOpenGame) }
        }
        if (waiting.isNotEmpty()) {
            item { SectionHeader("WAITING ON OPPONENT") }
            items(waiting) { game -> GameRow(registry, game, uid, onOpenGame) }
        }
        if (finished.isNotEmpty()) {
            item { SectionHeader("FINISHED") }
            items(finished) { game -> GameRow(registry, game, uid, onOpenGame) }
        }
    }
}

private fun joinableGames(registry: GameRegistry, openGames: List<OpenGame>, uid: String): List<OpenGame> =
    openGames.filter { it.hostUid != uid && it.guestUid == null && it.gameType in registry.all }

@Composable
private fun MenuCard(title: String, detail: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun ListRow(label: String, status: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable
private fun GameRow(registry: GameRegistry, game: GameDoc, uid: String, onOpenGame: (GameDoc) -> Unit) {
    val opponent = game.opponentOf(uid)?.displayName ?: "?"
    val status = when {
        game.status == GameStatus.ACTIVE && game.isMyTurn(uid) -> "your move"
        game.status == GameStatus.ACTIVE -> "their move"
        game.winnerUid == uid -> "you won"
        game.winnerUid == null -> "draw"
        else -> "you lost"
    }
    ListRow("${gameName(registry, game.gameType)} vs $opponent", status) { onOpenGame(game) }
}

@Composable
private fun BackBar(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onBack) { Text("< MENU") }
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
        trailing()
    }
}

@Composable
private fun PickGameScreen(registry: GameRegistry, onPick: (GameDefinition, String) -> Unit, onBack: () -> Unit) {
    val choices = registry.all.values.flatMap { definition -> definition.configVariants.map { definition to it } }
    Column(Modifier.fillMaxSize()) {
        BackBar("START A GAME", onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(choices) { (definition, variant) ->
                MenuCard(gameName(registry, definition.gameType, variant), "Tap to start and wait for a player") {
                    onPick(definition, variant)
                }
            }
        }
    }
}

@Composable
private fun JoinListScreen(
    registry: GameRegistry,
    client: PlatformClient,
    uid: String,
    name: String,
    games: List<GameDoc>,
    openGames: List<OpenGame>,
    onMatched: (String) -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var joining by remember { mutableStateOf<OpenGame?>(null) }
    var knownIds by remember { mutableStateOf(emptySet<String>()) }
    var message by remember { mutableStateOf<String?>(null) }
    val joinable = joinableGames(registry, openGames, uid)

    LaunchedEffect(games, joining) {
        val target = joining ?: return@LaunchedEffect
        games.firstOrNull { game ->
            game.id !in knownIds &&
                game.gameType == target.gameType &&
                game.players.any { it.uid == target.hostUid }
        }?.let { onMatched(it.id) }
    }
    val joiningGone = joining?.let { target -> openGames.none { it.id == target.id } } ?: false
    LaunchedEffect(joining, joiningGone) {
        if (joining != null) {
            delay(if (joiningGone) JOIN_GONE_TIMEOUT_MILLIS else JOIN_TIMEOUT_MILLIS)
            joining = null
            message = "That game could not be started. Try another, or start your own."
        }
    }

    Column(Modifier.fillMaxSize()) {
        BackBar("JOIN A GAME", onBack)
        val target = joining
        if (target != null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(24.dp))
                Text("Joining ${target.hostName}...", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                message?.let { text ->
                    item { Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error) }
                }
                if (joinable.isEmpty()) {
                    item { Text("No games waiting right now.", style = MaterialTheme.typography.bodyLarge) }
                    item { MenuCard("START A GAME", "Start one and wait for a player", onStart) }
                }
                items(joinable) { open ->
                    ListRow("${gameName(registry, open.gameType, open.variant)} with ${open.hostName}", "join") {
                        knownIds = games.map { it.id }.toSet()
                        message = null
                        joining = open
                        scope.launch {
                            runCatching { client.joinOpenGame(open.id, uid, name) }.onFailure {
                                joining = null
                                message = "Someone else got there first. Pick another game."
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WaitingScreen(
    registry: GameRegistry,
    client: PlatformClient,
    uid: String,
    name: String,
    waiting: Screen.Waiting,
    games: List<GameDoc>,
    onMatched: (String) -> Unit,
    onLeave: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val knownIds = remember(waiting) { games.map { it.id }.toSet() }
    var failed by remember(waiting) { mutableStateOf(false) }

    LaunchedEffect(waiting) {
        if (!waiting.alreadyHosted) {
            runCatching { client.hostGame(waiting.gameType, waiting.variant, uid, name) }
                .onFailure { failed = true }
        }
    }
    LaunchedEffect(games) {
        games.firstOrNull {
            it.id !in knownIds && it.gameType == waiting.gameType && it.status == GameStatus.ACTIVE
        }?.let { onMatched(it.id) }
    }

    Column(Modifier.fillMaxSize()) {
        BackBar(gameName(registry, waiting.gameType), onLeave)
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (failed) {
                Text("Could not start the game. Check your connection.", style = MaterialTheme.typography.bodyLarge)
            } else {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(24.dp))
                Text("Waiting for someone to join...", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Your game stays open if you leave this screen.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(24.dp))
                TextButton(onClick = {
                    scope.launch {
                        runCatching { client.cancelHostedGame(waiting.gameType, uid) }
                        onLeave()
                    }
                }) {
                    Text("CANCEL GAME")
                }
            }
        }
    }
}

@Composable
private fun GameHostScreen(
    registry: GameRegistry,
    client: PlatformClient,
    uiRegistry: GameUiRegistry,
    gameId: String,
    uid: String,
    onPlayAgain: (GameType, String) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val game by remember(gameId) { client.game(gameId) }.collectAsState(initial = null)
    var confirmResign by remember(gameId) { mutableStateOf(false) }
    val current = game
    Column(Modifier.fillMaxSize()) {
        BackBar(current?.let { gameName(registry, it.gameType) } ?: "", onBack) {
            if (current != null && current.status == GameStatus.ACTIVE) {
                TextButton(onClick = {
                    if (confirmResign) {
                        scope.launch { runCatching { client.resign(current.id, uid) } }
                    } else {
                        confirmResign = true
                    }
                }) {
                    Text(if (confirmResign) "TAP AGAIN TO RESIGN" else "RESIGN")
                }
            }
        }
        if (current == null) {
            CenteredMessage("Loading game...")
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                uiRegistry[current.gameType].GameScreen(
                    game = current,
                    myUid = uid,
                    onSubmit = { payloadJson ->
                        scope.launch {
                            runCatching { client.submit(current.id, current.round, payloadJson) }
                        }
                    }
                )
            }
            if (current.status != GameStatus.ACTIVE) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    TextButton(onClick = { onPlayAgain(current.gameType, current.variant) }) { Text("PLAY AGAIN") }
                    Spacer(Modifier.width(24.dp))
                    TextButton(onClick = onBack) { Text("MENU") }
                }
            }
        }
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
