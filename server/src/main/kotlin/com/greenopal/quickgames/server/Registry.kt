package com.greenopal.quickgames.server

import com.greenopal.quickgames.core.engine.GameRegistry
import com.greenopal.quickgames.games.hexstead.HexsteadDefinition
import com.greenopal.quickgames.games.rpg.RpgDefinition
import com.greenopal.quickgames.games.rps.RpsDefinition
import com.greenopal.quickgames.games.spiceroad.SpiceRoadDefinition
import com.greenopal.quickgames.games.squad.SquadDefinition
import com.greenopal.quickgames.games.tictactoe.TicTacToeDefinition

val gameRegistry = GameRegistry(
    listOf(TicTacToeDefinition, RpsDefinition, RpgDefinition, HexsteadDefinition, SpiceRoadDefinition, SquadDefinition)
)
