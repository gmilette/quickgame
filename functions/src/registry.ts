import { GameDefinition } from "./engine";
import { ticTacToeDefinition } from "./games/tictactoe";

export const registry: Record<string, GameDefinition> = {
  [ticTacToeDefinition.gameType]: ticTacToeDefinition,
};
