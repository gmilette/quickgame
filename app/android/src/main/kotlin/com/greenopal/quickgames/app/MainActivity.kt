package com.greenopal.quickgames.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.greenopal.quickgames.core.engine.GameRegistry
import com.greenopal.quickgames.core.ui.App
import com.greenopal.quickgames.core.ui.GameUiRegistry
import com.greenopal.quickgames.core.ui.OpenGameRequest
import com.greenopal.quickgames.games.rpg.RpgDefinition
import com.greenopal.quickgames.games.rpg.RpgUi
import com.greenopal.quickgames.games.rps.RpsDefinition
import com.greenopal.quickgames.games.rps.RpsUi
import com.greenopal.quickgames.games.tictactoe.TicTacToeDefinition
import com.greenopal.quickgames.games.tictactoe.TicTacToeUi

class MainActivity : ComponentActivity() {
    private val resumeCount = mutableIntStateOf(0)
    private val openGameRequest = mutableStateOf<OpenGameRequest?>(null)
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val registry = GameRegistry(listOf(TicTacToeDefinition, RpsDefinition, RpgDefinition))
        val uiRegistry = GameUiRegistry(listOf(TicTacToeUi, RpsUi, RpgUi))
        val client = (application as QuickGamesApplication).client
        if (savedInstanceState == null) {
            handleNotificationIntent(intent)
        }
        askForNotificationPermission()
        setContent {
            App(
                registry = registry,
                uiRegistry = uiRegistry,
                client = client,
                resumeCount = resumeCount.intValue,
                openGameRequest = openGameRequest.value,
                onSignedIn = { uid -> Push.registerCurrentToken(client, uid) }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        resumeCount.intValue += 1
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val gameId = intent?.getStringExtra(EXTRA_GAME_ID) ?: return
        val sequence = (openGameRequest.value?.sequence ?: 0) + 1
        openGameRequest.value = OpenGameRequest(sequence, gameId)
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
