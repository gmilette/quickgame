package com.greenopal.quickgames.server

import com.greenopal.quickgames.core.engine.GameRegistry
import com.greenopal.quickgames.games.tictactoe.TicTacToeDefinition

val gameRegistry = GameRegistry(listOf(TicTacToeDefinition))
