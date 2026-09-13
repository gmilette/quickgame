package com.greenopal.quickgames.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

class ReplayState(private val stepCount: Int) {
    var index by mutableIntStateOf(0)
        internal set

    val finished: Boolean get() = index >= stepCount

    fun skipToEnd() {
        index = stepCount
    }
}

@Composable
fun rememberReplayState(stepCount: Int, stepMillis: Long): ReplayState {
    val state = remember(stepCount) { ReplayState(stepCount) }
    LaunchedEffect(stepCount) {
        while (state.index < stepCount) {
            delay(stepMillis)
            state.index += 1
        }
    }
    return state
}
