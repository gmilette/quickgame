import { GameStatus, PlayerRef, seedForRound } from "./engine";
import { registry } from "./registry";

export interface GameData {
  gameType: string;
  variant: string;
  players: PlayerRef[];
  playerUids: string[];
  activePlayerId: string | null;
  round: number;
  stateJson: string;
  status: GameStatus;
  winner: string | null;
  engineVersion: number;
  seed: number;
}

export type SubmissionResolution =
  | { kind: "rejected"; errors: string[] }
  | {
      kind: "applied";
      stateJson: string;
      logJson: string | null;
      status: GameStatus;
      winner: string | null;
      activePlayerId: string | null;
      round: number;
      seedUsed: number;
      turnDeadlineHours: number;
    };

export function resolveSubmission(
  game: GameData,
  submissionId: string,
  payloadJson: string
): SubmissionResolution {
  const def = registry[game.gameType];
  if (!def) return { kind: "rejected", errors: ["unknownGameType"] };
  if (game.status !== "active") return { kind: "rejected", errors: ["gameNotActive"] };

  const separator = submissionId.indexOf("-");
  if (separator < 1) return { kind: "rejected", errors: ["malformedSubmissionId"] };
  const round = Number(submissionId.slice(0, separator));
  const uid = submissionId.slice(separator + 1);

  if (!Number.isInteger(round) || round !== game.round) {
    return { kind: "rejected", errors: ["staleRound"] };
  }
  if (uid !== game.activePlayerId) {
    return { kind: "rejected", errors: ["notActivePlayer"] };
  }

  const configJson = def.configJson(game.variant);
  const engine = def.engine();
  const validation = engine.validate(game.stateJson, payloadJson, uid, configJson);
  if (!validation.valid) {
    return { kind: "rejected", errors: validation.errors };
  }

  const seedUsed = seedForRound(game.seed, game.round);
  const result = engine.apply(game.stateJson, payloadJson, uid, seedUsed, configJson);
  const activePlayerId =
    result.status === "active" ? engine.activePlayer(result.stateJson, configJson) : null;

  return {
    kind: "applied",
    stateJson: result.stateJson,
    logJson: result.logJson,
    status: result.status,
    winner: result.winnerUid,
    activePlayerId,
    round: game.round + 1,
    seedUsed,
    turnDeadlineHours: def.turnDeadlineHours,
  };
}
