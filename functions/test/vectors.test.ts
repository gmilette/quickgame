import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { registry } from "../src/registry";

interface VectorFile {
  game: string;
  engineVersion: number;
  initialStates: Array<{
    name: string;
    configJson: string;
    players: Array<{ uid: string; displayName: string; playerIndex: number }>;
    seed: number;
    expectedStateJson: string;
    expectedActivePlayer: string;
  }>;
  vectors: Array<{
    name: string;
    stateJson: string;
    submissionJson: string;
    playerId: string;
    seed: number;
    configJson: string;
    expected:
      | { valid: true; stateJson: string; logJson: string | null; status: string; winnerUid: string | null }
      | { valid: false; errors: string[] };
  }>;
}

const vectorFile: VectorFile = JSON.parse(
  readFileSync(join(__dirname, "..", "..", "test-vectors", "tictactoe.json"), "utf8")
);

describe(`shared test vectors: ${vectorFile.game}`, () => {
  const definition = registry[vectorFile.game];
  const engine = definition.engine();

  it("is registered with a matching engine version", () => {
    expect(definition).toBeDefined();
    expect(definition.engineVersion).toBe(vectorFile.engineVersion);
  });

  for (const initial of vectorFile.initialStates) {
    it(`initial state: ${initial.name}`, () => {
      const stateJson = definition.initialStateJson(initial.configJson, initial.players, initial.seed);
      expect(JSON.parse(stateJson)).toEqual(JSON.parse(initial.expectedStateJson));
      expect(engine.activePlayer(stateJson, initial.configJson)).toBe(initial.expectedActivePlayer);
    });
  }

  for (const vector of vectorFile.vectors) {
    it(`vector: ${vector.name}`, () => {
      const validation = engine.validate(
        vector.stateJson,
        vector.submissionJson,
        vector.playerId,
        vector.configJson
      );
      if (vector.expected.valid) {
        expect(validation).toEqual({ valid: true });
        const result = engine.apply(
          vector.stateJson,
          vector.submissionJson,
          vector.playerId,
          vector.seed,
          vector.configJson
        );
        expect(JSON.parse(result.stateJson)).toEqual(JSON.parse(vector.expected.stateJson));
        expect(result.status).toBe(vector.expected.status);
        expect(result.winnerUid).toBe(vector.expected.winnerUid);
        expect(result.logJson).toBe(vector.expected.logJson);
      } else {
        expect(validation).toEqual({ valid: false, errors: vector.expected.errors });
      }
    });
  }
});
