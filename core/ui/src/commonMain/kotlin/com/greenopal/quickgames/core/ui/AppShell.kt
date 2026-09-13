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
import com.greenopal.quickgames.core.engine.GameRegistry
import com.greenopal.quickgames.core.model.GameDoc
import com.greenopal.quickgames.core.model.GameStatus
import com.greenopal.quickgames.core.model.GameType
import com.greenopal.quickgames.core.model.PlatformClient
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data class Queueing(val gameType: GameType, val variant: String) : Screen
    data class InGame(val gameId: String) : Screen
}

@Composable
fun App(registry: GameRegistry, uiRegistry: GameUiRegistry, client: PlatformClient) {
    QuickGamesTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding()
        ) {
            var uid by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) { uid = client.signIn() }
            val currentUid = uid
            if (currentUid == null) {
                CenteredMessage("Signing in...")
            } else {
                SignedInApp(registry, uiRegistry, client, currentUid)
            }
        }
    }
}

@Composable
private fun SignedInApp(
    registry: GameRegistry,
    uiRegistry: GameUiRegistry,
    client: PlatformClient,
    uid: String
) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val games by remember(uid) { client.games(uid) }.collectAsState(initial = emptyList())

    when (val current = screen) {
        is Screen.Home -> HomeScreen(
            registry = registry,
            games = games,
            uid = uid,
            onPlay = { gameType, variant -> screen = Screen.Queueing(gameType, variant) },
            onOpenGame = { screen = Screen.InGame(it.id) }
        )
        is Screen.Queueing -> QueueingScreen(
            client = client,
            uid = uid,
            gameType = current.gameType,
            variant = current.variant,
            games = games,
            onMatched = { screen = Screen.InGame(it) },
            onCancel = { screen = Screen.Home }
        )
        is Screen.InGame -> GameHostScreen(
            client = client,
            uiRegistry = uiRegistry,
            gameId = current.gameId,
            uid = uid,
            onBack = { screen = Screen.Home }
        )
    }
}

@Composable
private fun HomeScreen(
    registry: GameRegistry,
    games: List<GameDoc>,
    uid: String,
    onPlay: (GameType, String) -> Unit,
    onOpenGame: (GameDoc) -> Unit
) {
    val yourTurn = games
        .filter { it.isMyTurn(uid) }
        .sortedBy { it.turnDeadlineMillis ?: Long.MAX_VALUE }
    val waiting = games.filter { it.status == GameStatus.ACTIVE && !it.isMyTurn(uid) }
    val finished = games.filter { it.status != GameStatus.ACTIVE }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Text("QUICKGAMES", style = MaterialTheme.typography.headlineMedium) }
        item { Spacer(Modifier.height(4.dp)) }
        items(registry.all.values.toList()) { definition ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable {
                    onPlay(definition.gameType, definition.configVariants.first())
                },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(definition.displayName, style = MaterialTheme.typography.titleLarge)
                    Text("Tap to find an opponent", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (yourTurn.isNotEmpty()) {
            item { SectionHeader("YOUR TURN") }
            items(yourTurn) { game -> GameRow(game, uid, onOpenGame) }
        }
        if (waiting.isNotEmpty()) {
            item { SectionHeader("WAITING ON OPPONENT") }
            items(waiting) { game -> GameRow(game, uid, onOpenGame) }
        }
        if (finished.isNotEmpty()) {
            item { SectionHeader("FINISHED") }
            items(finished) { game -> GameRow(game, uid, onOpenGame) }
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
private fun GameRow(game: GameDoc, uid: String, onOpenGame: (GameDoc) -> Unit) {
    val opponent = game.opponentOf(uid)?.displayName ?: "?"
    val status = when {
        game.status == GameStatus.ACTIVE && game.isMyTurn(uid) -> "your move"
        game.status == GameStatus.ACTIVE -> "their move"
        game.winnerUid == uid -> "you won"
        game.winnerUid == null -> "draw"
        else -> "you lost"
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpenGame(game) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("vs $opponent", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable
private fun QueueingScreen(
    client: PlatformClient,
    uid: String,
    gameType: GameType,
    variant: String,
    games: List<GameDoc>,
    onMatched: (String) -> Unit,
    onCancel: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val knownIds = remember { games.map { it.id }.toSet() }
    var joined by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        client.joinQueue(gameType, variant, uid, "Player-" + uid.takeLast(4))
        joined = true
    }
    LaunchedEffect(games, joined) {
        if (joined) {
            games.firstOrNull { it.id !in knownIds && it.gameType == gameType && it.status == GameStatus.ACTIVE }
                ?.let { onMatched(it.id) }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))
        Text("Searching for an opponent...", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = {
            scope.launch {
                runCatching { client.leaveQueue(gameType, variant, uid) }
                onCancel()
            }
        }) {
            Text("CANCEL")
        }
    }
}

@Composable
private fun GameHostScreen(
    client: PlatformClient,
    uiRegistry: GameUiRegistry,
    gameId: String,
    uid: String,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val game by remember(gameId) { client.game(gameId) }.collectAsState(initial = null)
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("< BACK") }
            Spacer(Modifier.width(8.dp))
        }
        val current = game
        if (current == null) {
            CenteredMessage("Loading game...")
        } else {
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
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
