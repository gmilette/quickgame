package com.greenopal.quickgames.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.greenopal.quickgames.core.engine.GameRegistry
import com.greenopal.quickgames.core.ui.App
import com.greenopal.quickgames.core.ui.GameUiRegistry
import com.greenopal.quickgames.games.tictactoe.TicTacToeDefinition
import com.greenopal.quickgames.games.tictactoe.TicTacToeUi

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val registry = GameRegistry(listOf(TicTacToeDefinition))
        val uiRegistry = GameUiRegistry(listOf(TicTacToeUi))
        val client = (application as QuickGamesApplication).client
        setContent {
            App(registry, uiRegistry, client)
        }
    }
}
