package com.greenopal.quickgames.games.rps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.greenopal.quickgames.core.model.GameDoc
import com.greenopal.quickgames.core.model.GameStatus
import com.greenopal.quickgames.core.model.GameType
import com.greenopal.quickgames.core.ui.GameUi
import com.greenopal.quickgames.core.ui.PixelPalette

object RpsUi : GameUi {
    override val gameType = GameType.ROCK_PAPER_SCISSORS

    @Composable
    override fun GameScreen(game: GameDoc, myUid: String, onSubmit: (String) -> Unit) {
        val state = remember(game.stateJson) {
            RpsJson.json.decodeFromString<RpsState>(game.stateJson)
        }
        val opponentUid = state.players.firstOrNull { it != myUid } ?: "?"
        val opponentName = game.opponentOf(myUid)?.displayName ?: "?"
        val myTurn = game.isMyTurn(myUid)
        var myChoice by remember(game.id, state.history.size) { mutableStateOf<String?>(null) }
        val lastRound = state.history.lastOrNull()

        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("vs $opponentName", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "You ${state.scores[myUid] ?: 0} - ${state.scores[opponentUid] ?: 0} $opponentName",
                style = MaterialTheme.typography.headlineMedium
            )
            Text("First to ${state.targetWins} wins", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(16.dp))
            Text(
                text = statusText(game, state, myUid, myTurn, myChoice),
                style = MaterialTheme.typography.bodyLarge,
                color = if (myTurn) PixelPalette.primary else MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(16.dp))
            if (lastRound != null) {
                LastRound(lastRound, myUid, opponentUid, opponentName)
                Spacer(Modifier.height(16.dp))
            }
            if (game.status == GameStatus.ACTIVE) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (choice in RpsEngine.choices) {
                        ChoiceButton(
                            label = choice.uppercase(),
                            enabled = myTurn && myChoice == null,
                            selected = myChoice == choice,
                            modifier = Modifier.weight(1f)
                        ) {
                            myChoice = choice
                            onSubmit("""{"choice":"$choice"}""")
                        }
                    }
                }
            }
        }
    }

    private fun statusText(
        game: GameDoc,
        state: RpsState,
        myUid: String,
        myTurn: Boolean,
        myChoice: String?
    ): String = when {
        game.status == GameStatus.FORFEITED && game.winnerUid == myUid -> "Opponent forfeited - you win!"
        game.status == GameStatus.FORFEITED -> "Forfeited - you lose"
        state.winner == myUid -> "You win the match!"
        state.winner != null -> "You lose the match"
        myChoice != null || state.pendingBy == myUid -> "You have chosen. Waiting for opponent..."
        myTurn && state.pendingBy != null -> "Opponent has chosen. Your pick!"
        myTurn -> "Pick first - your choice stays hidden"
        else -> "Waiting for opponent to pick..."
    }

    @Composable
    private fun LastRound(round: RpsRound, myUid: String, opponentUid: String, opponentName: String) {
        val outcome = when (round.winner) {
            null -> "Tie"
            myUid -> "You won the round"
            else -> "$opponentName won the round"
        }
        Column(
            modifier = Modifier.fillMaxWidth().background(PixelPalette.surface).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("LAST ROUND", style = MaterialTheme.typography.labelMedium, color = PixelPalette.secondary)
            Text(
                "You: ${round.choices[myUid] ?: "?"}   $opponentName: ${round.choices[opponentUid] ?: "?"}",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(outcome, style = MaterialTheme.typography.bodyMedium)
        }
    }

    @Composable
    private fun ChoiceButton(
        label: String,
        enabled: Boolean,
        selected: Boolean,
        modifier: Modifier,
        onClick: () -> Unit
    ) {
        Box(
            modifier = modifier
                .height(72.dp)
                .background(PixelPalette.surface)
                .border(
                    width = 2.dp,
                    color = when {
                        selected -> PixelPalette.secondary
                        enabled -> PixelPalette.primary
                        else -> PixelPalette.surface
                    }
                )
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled || selected) PixelPalette.text else PixelPalette.text.copy(alpha = 0.4f)
            )
        }
    }
}
