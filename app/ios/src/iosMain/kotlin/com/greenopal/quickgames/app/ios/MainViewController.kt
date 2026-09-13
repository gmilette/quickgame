package com.greenopal.quickgames.app.ios

import androidx.compose.ui.window.ComposeUIViewController
import com.greenopal.quickgames.core.engine.GameRegistry
import com.greenopal.quickgames.core.firebase.FirebaseClient
import com.greenopal.quickgames.core.ui.App
import com.greenopal.quickgames.core.ui.GameUiRegistry
import com.greenopal.quickgames.games.tictactoe.TicTacToeDefinition
import com.greenopal.quickgames.games.tictactoe.TicTacToeUi
import platform.UIKit.UIViewController

fun mainViewController(): UIViewController {
    val registry = GameRegistry(listOf(TicTacToeDefinition))
    val uiRegistry = GameUiRegistry(listOf(TicTacToeUi))
    val client = FirebaseClient.create(useEmulators = true, emulatorHost = "127.0.0.1")
    return ComposeUIViewController {
        App(registry, uiRegistry, client)
    }
}
