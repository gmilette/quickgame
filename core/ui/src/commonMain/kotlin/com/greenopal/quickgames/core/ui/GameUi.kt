package com.greenopal.quickgames.core.ui

import androidx.compose.runtime.Composable
import com.greenopal.quickgames.core.model.GameDoc
import com.greenopal.quickgames.core.model.GameType

interface GameUi {
    val gameType: GameType

    @Composable
    fun GameScreen(game: GameDoc, myUid: String, onSubmit: (String) -> Unit)
}

class GameUiRegistry(uis: List<GameUi>) {
    val all: Map<GameType, GameUi> = uis.associateBy { it.gameType }

    operator fun get(gameType: GameType): GameUi = all.getValue(gameType)
}
