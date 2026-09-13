export type GameStatus = "active" | "complete" | "forfeited";

export interface PlayerRef {
  uid: string;
  displayName: string;
  playerIndex: number;
}

export type EngineValidation = { valid: true } | { valid: false; errors: string[] };

export interface EngineResult {
  stateJson: string;
  logJson: string | null;
  status: GameStatus;
  winnerUid: string | null;
}

export interface GameEngine {
  activePlayer(stateJson: string, configJson: string): string;
  validate(stateJson: string, submissionJson: string, playerId: string, configJson: string): EngineValidation;
  apply(stateJson: string, submissionJson: string, playerId: string, seed: number, configJson: string): EngineResult;
}

export interface GameDefinition {
  gameType: string;
  displayName: string;
  configVariants: string[];
  engineVersion: number;
  turnDeadlineHours: number;
  configJson(variant: string): string;
  engine(): GameEngine;
  initialStateJson(configJson: string, players: PlayerRef[], seed: number): string;
}

export function seedForRound(baseSeed: number, round: number): number {
  return (baseSeed ^ (round * 0x9e3779b9)) >>> 0;
}
