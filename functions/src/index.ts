import * as admin from "firebase-admin";
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { onRequest } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { logger } from "firebase-functions/v2";
import { PlayerRef } from "./engine";
import { GameData, resolveSubmission } from "./pipeline";
import { registry } from "./registry";

admin.initializeApp();
const db = admin.firestore();

function deadline(hours: number): Timestamp {
  return Timestamp.fromMillis(Date.now() + hours * 3600 * 1000);
}

export const onQueueEntryCreated = onDocumentCreated(
  "queues/{queueId}/entries/{uid}",
  async (event) => {
    const queueId = event.params.queueId;
    const separator = queueId.indexOf("_");
    if (separator < 1) {
      logger.warn("malformed queue id", { queueId });
      return;
    }
    const gameTypeId = queueId.slice(0, separator);
    const variant = queueId.slice(separator + 1);
    const def = registry[gameTypeId];
    if (!def || !def.configVariants.includes(variant)) {
      logger.warn("unknown game type or variant", { queueId });
      return;
    }

    await db.runTransaction(async (tx) => {
      const entries = await tx.get(
        db.collection(`queues/${queueId}/entries`).orderBy("createdAt").limit(2)
      );
      if (entries.size < 2) return;
      const [first, second] = entries.docs;
      if (first.id === second.id) return;

      const seed = Math.floor(Math.random() * 0xffffffff);
      const ordered = Math.random() < 0.5 ? [first, second] : [second, first];
      const players: PlayerRef[] = ordered.map((doc, index) => ({
        uid: doc.id,
        displayName: (doc.data().displayName as string) ?? "Player",
        playerIndex: index,
      }));

      const configJson = def.configJson(variant);
      const stateJson = def.initialStateJson(configJson, players, seed);
      const activePlayerId = def.engine().activePlayer(stateJson, configJson);

      const gameRef = db.collection("games").doc();
      tx.delete(first.ref);
      tx.delete(second.ref);
      tx.create(gameRef, {
        gameType: gameTypeId,
        variant,
        players,
        playerUids: players.map((p) => p.uid),
        activePlayerId,
        round: 0,
        stateJson,
        status: "active",
        winner: null,
        engineVersion: def.engineVersion,
        seed,
        turnDeadline: deadline(def.turnDeadlineHours),
        createdAt: FieldValue.serverTimestamp(),
      });
      logger.info("game created", { gameId: gameRef.id, gameTypeId, variant });
    });
  }
);

export const onSubmissionCreated = onDocumentCreated(
  "games/{gameId}/submissions/{subId}",
  async (event) => {
    const snapshot = event.data;
    if (!snapshot) return;
    const { gameId, subId } = event.params;
    const payloadJson = snapshot.data().payloadJson;

    await db.runTransaction(async (tx) => {
      const gameRef = db.doc(`games/${gameId}`);
      const gameSnap = await tx.get(gameRef);
      if (!gameSnap.exists) return;
      const game = gameSnap.data() as GameData;

      const subRef = snapshot.ref;
      const subSnap = await tx.get(subRef);
      if (!subSnap.exists || subSnap.data()?.status !== undefined) return;

      const reject = (errors: string[]) => {
        tx.set(db.doc(`games/${gameId}/logs/${subId}-rejected`), {
          type: "rejection",
          errors,
          payloadJson: typeof payloadJson === "string" ? payloadJson : null,
          createdAt: FieldValue.serverTimestamp(),
        });
        tx.delete(subRef);
        logger.info("submission rejected", { gameId, subId, errors });
      };

      if (typeof payloadJson !== "string") {
        reject(["missingPayload"]);
        return;
      }

      const resolution = resolveSubmission(game, subId, payloadJson);
      if (resolution.kind === "rejected") {
        reject(resolution.errors);
        return;
      }

      tx.update(gameRef, {
        stateJson: resolution.stateJson,
        status: resolution.status,
        winner: resolution.winner,
        activePlayerId: resolution.activePlayerId,
        round: resolution.round,
        turnDeadline:
          resolution.status === "active" ? deadline(resolution.turnDeadlineHours) : null,
      });
      if (resolution.logJson !== null) {
        tx.set(db.doc(`games/${gameId}/logs/${subId}`), {
          logJson: resolution.logJson,
          seed: resolution.seedUsed,
          createdAt: FieldValue.serverTimestamp(),
        });
      }
      tx.update(subRef, { status: "applied" });
      logger.info("submission applied", { gameId, subId, status: resolution.status });
    });
  }
);

async function sweepExpiredGames(): Promise<number> {
  const now = Timestamp.now();
  const expired = await db
    .collection("games")
    .where("status", "==", "active")
    .where("turnDeadline", "<=", now)
    .limit(100)
    .get();

  let forfeited = 0;
  for (const doc of expired.docs) {
    await db.runTransaction(async (tx) => {
      const snap = await tx.get(doc.ref);
      if (!snap.exists) return;
      const game = snap.data() as GameData & { turnDeadline: Timestamp | null };
      if (game.status !== "active" || !game.turnDeadline || game.turnDeadline > now) return;
      const winner = game.playerUids.find((uid) => uid !== game.activePlayerId) ?? null;
      tx.update(doc.ref, {
        status: "forfeited",
        winner,
        activePlayerId: null,
        turnDeadline: null,
      });
      forfeited += 1;
      logger.info("game forfeited on deadline", { gameId: doc.id, winner });
    });
  }
  return forfeited;
}

export const deadlineSweep = onSchedule("every 60 minutes", async () => {
  await sweepExpiredGames();
});

export const sweepNow = onRequest({ invoker: "private" }, async (_req, res) => {
  const forfeited = await sweepExpiredGames();
  res.json({ forfeited });
});
