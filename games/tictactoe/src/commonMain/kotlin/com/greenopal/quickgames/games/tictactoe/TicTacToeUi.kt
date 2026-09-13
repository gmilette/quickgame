package com.greenopal.quickgames.games.tictactoe

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.greenopal.quickgames.core.model.GameDoc
import com.greenopal.quickgames.core.model.GameStatus
import com.greenopal.quickgames.core.model.GameType
import com.greenopal.quickgames.core.ui.GameUi
import com.greenopal.quickgames.core.ui.PixelPalette

object TicTacToeUi : GameUi {
    override val gameType = GameType.TIC_TAC_TOE

    @Composable
    override fun GameScreen(game: GameDoc, myUid: String, onSubmit: (String) -> Unit) {
        val state = remember(game.stateJson) {
            TicTacToeJson.json.decodeFromString<TicTacToeState>(game.stateJson)
        }
        val myMark = state.marks.entries.firstOrNull { it.value == myUid }?.key ?: "?"
        val myTurn = game.isMyTurn(myUid)
        val opponent = game.opponentOf(myUid)?.displayName ?: "?"

        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("vs $opponent", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = statusText(game, state, myUid, myTurn),
                style = MaterialTheme.typography.bodyLarge,
                color = if (myTurn) PixelPalette.primary else MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(16.dp))
            Board(state, enabled = myTurn) { cell -> onSubmit("""{"cell":$cell}""") }
            Spacer(Modifier.height(16.dp))
            Text("You are $myMark", style = MaterialTheme.typography.labelLarge)
        }
    }

    private fun statusText(game: GameDoc, state: TicTacToeState, myUid: String, myTurn: Boolean): String = when {
        game.status == GameStatus.FORFEITED && game.winnerUid == myUid -> "Opponent forfeited - you win!"
        game.status == GameStatus.FORFEITED -> "Forfeited - you lose"
        state.winnerMark != null && game.winnerUid == myUid -> "You win!"
        state.winnerMark != null -> "You lose"
        state.moveCount == 9 -> "Draw"
        myTurn -> "Your move"
        else -> "Waiting for opponent..."
    }

    @Composable
    private fun Board(state: TicTacToeState, enabled: Boolean, onCell: (Int) -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(PixelPalette.surface)
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (row in 0..2) {
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (col in 0..2) {
                        val index = row * 3 + col
                        val value = state.cells[index]
                        val inWinLine = state.line?.contains(index) == true
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .background(PixelPalette.background)
                                .border(
                                    width = 2.dp,
                                    color = if (inWinLine) PixelPalette.secondary else PixelPalette.surface
                                )
                                .clickable(enabled = enabled && value.isEmpty()) { onCell(index) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = value,
                                style = MaterialTheme.typography.displayLarge,
                                color = when (value) {
                                    "X" -> PixelPalette.primary
                                    "O" -> PixelPalette.accent
                                    else -> PixelPalette.text
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
