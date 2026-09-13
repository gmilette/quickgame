import {
  EngineResult,
  EngineValidation,
  GameDefinition,
  GameEngine,
  GameStatus,
  PlayerRef,
} from "../engine";

interface TicTacToeState {
  cells: string[];
  marks: Record<string, string>;
  next: string;
  winnerMark: string | null;
  line: number[] | null;
  moveCount: number;
}

const LINES: number[][] = [
  [0, 1, 2],
  [3, 4, 5],
  [6, 7, 8],
  [0, 3, 6],
  [1, 4, 7],
  [2, 5, 8],
  [0, 4, 8],
  [2, 4, 6],
];

function decodeState(stateJson: string): TicTacToeState {
  return JSON.parse(stateJson) as TicTacToeState;
}

function decodeSubmission(submissionJson: string): number | null {
  try {
    const parsed = JSON.parse(submissionJson) as { cell?: unknown };
    if (typeof parsed !== "object" || parsed === null) return null;
    const cell = parsed.cell;
    if (typeof cell !== "number" || !Number.isInteger(cell)) return null;
    return cell;
  } catch {
    return null;
  }
}

function encodeState(state: TicTacToeState): string {
  return JSON.stringify({
    cells: state.cells,
    marks: state.marks,
    next: state.next,
    winnerMark: state.winnerMark,
    line: state.line,
    moveCount: state.moveCount,
  });
}

const engine: GameEngine = {
  activePlayer(stateJson: string): string {
    const state = decodeState(stateJson);
    return state.marks[state.next];
  },

  validate(stateJson: string, submissionJson: string, playerId: string): EngineValidation {
    const state = decodeState(stateJson);
    const errors: string[] = [];
    if (state.winnerMark !== null || state.moveCount === 9) {
      errors.push("gameComplete");
    } else if (state.marks[state.next] !== playerId) {
      errors.push("notActivePlayer");
    }
    const cell = decodeSubmission(submissionJson);
    if (cell === null) {
      errors.push("malformedSubmission");
    } else if (cell < 0 || cell > 8) {
      errors.push("cellOutOfRange");
    } else if (state.cells[cell] !== "") {
      errors.push("cellOccupied");
    }
    return errors.length === 0 ? { valid: true } : { valid: false, errors };
  },

  apply(stateJson: string, submissionJson: string): EngineResult {
    const state = decodeState(stateJson);
    const cell = decodeSubmission(submissionJson);
    if (cell === null) throw new Error("apply called with invalid submission");
    const mark = state.next;
    const cells = [...state.cells];
    cells[cell] = mark;
    const winLine = LINES.find((line) => line.every((i) => cells[i] === mark)) ?? null;
    const moveCount = state.moveCount + 1;
    const newState: TicTacToeState = {
      cells,
      marks: state.marks,
      next: mark === "X" ? "O" : "X",
      winnerMark: winLine !== null ? mark : null,
      line: winLine,
      moveCount,
    };
    const status: GameStatus = winLine !== null || moveCount === 9 ? "complete" : "active";
    return {
      stateJson: encodeState(newState),
      logJson: null,
      status,
      winnerUid: winLine !== null ? state.marks[mark] : null,
    };
  },
};

export const ticTacToeDefinition: GameDefinition = {
  gameType: "tictactoe",
  displayName: "Tic-Tac-Toe",
  configVariants: ["standard"],
  engineVersion: 1,
  turnDeadlineHours: 72,

  configJson(): string {
    return "{}";
  },

  engine(): GameEngine {
    return engine;
  },

  initialStateJson(_configJson: string, players: PlayerRef[]): string {
    const ordered = [...players].sort((a, b) => a.playerIndex - b.playerIndex);
    const state: TicTacToeState = {
      cells: Array(9).fill(""),
      marks: { X: ordered[0].uid, O: ordered[1].uid },
      next: "X",
      winnerMark: null,
      line: null,
      moveCount: 0,
    };
    return encodeState(state);
  },
};
