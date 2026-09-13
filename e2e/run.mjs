import { initializeApp } from "firebase/app";
import { connectAuthEmulator, getAuth, signInAnonymously } from "firebase/auth";
import {
  collection,
  connectFirestoreEmulator,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  getFirestore,
  initializeFirestore,
  query,
  serverTimestamp,
  setDoc,
  updateDoc,
  where,
} from "firebase/firestore";

const HOST = "127.0.0.1";
const config = { apiKey: "fake-emulator-api-key", projectId: "demo-quickgames", appId: "1:0:web:e2e" };

let failures = 0;
function check(condition, label) {
  if (condition) {
    console.log(`  ok: ${label}`);
  } else {
    failures += 1;
    console.error(`  FAIL: ${label}`);
  }
}

async function waitFor(label, fn, timeoutMs = 30000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    const value = await fn();
    if (value !== undefined && value !== null && value !== false) return value;
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error(`timeout waiting for: ${label}`);
}

function makeClient(name) {
  const app = initializeApp(config, name);
  const auth = getAuth(app);
  connectAuthEmulator(auth, `http://${HOST}:9099`, { disableWarnings: true });
  const db = initializeFirestore(app, { experimentalForceLongPolling: true });
  connectFirestoreEmulator(db, HOST, 8080);
  return { auth, db };
}

async function expectDenied(label, promise) {
  try {
    await promise;
    check(false, `${label} (expected permission-denied, but write succeeded)`);
  } catch (error) {
    check(error.code === "permission-denied", `${label} (got ${error.code})`);
  }
}

async function joinQueue(client, uid) {
  await setDoc(doc(client.db, "queues/tictactoe_standard/entries", uid), {
    uid,
    displayName: `E2E-${uid.slice(0, 4)}`,
    createdAt: serverTimestamp(),
  });
}

async function findGame(client, uid, excludeIds) {
  const snapshot = await getDocs(
    query(collection(client.db, "games"), where("playerUids", "array-contains", uid))
  );
  const found = snapshot.docs.find((d) => !excludeIds.has(d.id));
  return found ? { id: found.id, ...found.data() } : null;
}

async function readGame(client, gameId) {
  const snapshot = await getDoc(doc(client.db, "games", gameId));
  return { id: gameId, ...snapshot.data() };
}

async function submitMove(client, gameId, round, uid, cell) {
  await setDoc(doc(client.db, `games/${gameId}/submissions`, `${round}-${uid}`), {
    payloadJson: JSON.stringify({ cell }),
    submittedAt: serverTimestamp(),
  });
}

async function playGame(clients, uids, gameId, cellSequence) {
  let game = await readGame(clients[0], gameId);
  for (const cell of cellSequence) {
    const activeUid = game.activePlayerId;
    const client = clients[uids.indexOf(activeUid)];
    const round = game.round;
    await submitMove(client, gameId, round, activeUid, cell);
    game = await waitFor(`round ${round} applied`, async () => {
      const updated = await readGame(clients[0], gameId);
      return updated.round > round || updated.status !== "active" ? updated : null;
    });
  }
  return game;
}

async function main() {
  console.log("== signing in two anonymous users");
  const a = makeClient("a");
  const b = makeClient("b");
  const uidA = (await signInAnonymously(a.auth)).user.uid;
  const uidB = (await signInAnonymously(b.auth)).user.uid;
  check(uidA && uidB && uidA !== uidB, "two distinct anonymous users");

  console.log("== matchmaking game 1");
  await joinQueue(a, uidA);
  await joinQueue(b, uidB);
  const game1 = await waitFor("game 1 created", () => findGame(a, uidA, new Set()));
  check(game1.status === "active", "game created active");
  check(game1.playerUids.includes(uidA) && game1.playerUids.includes(uidB), "both players in game");
  check(game1.round === 0, "game starts at round 0");
  check(game1.turnDeadline != null, "turn deadline set");
  const state1 = JSON.parse(game1.stateJson);
  check(state1.marks.X === game1.activePlayerId, "X moves first");

  const queueSnapshot = await getDoc(doc(a.db, "queues/tictactoe_standard/entries", uidA)).catch(() => null);
  check(!queueSnapshot || !queueSnapshot.exists(), "queue entry consumed");

  console.log("== security rules");
  const inactiveUid = game1.activePlayerId === uidA ? uidB : uidA;
  const inactiveClient = game1.activePlayerId === uidA ? b : a;
  const activeClient = game1.activePlayerId === uidA ? a : b;
  const activeUid = game1.activePlayerId;

  await expectDenied(
    "inactive player cannot submit",
    submitMove(inactiveClient, game1.id, game1.round, inactiveUid, 0)
  );
  await expectDenied(
    "cannot submit under wrong round key",
    submitMove(activeClient, game1.id, 5, activeUid, 0)
  );
  await expectDenied(
    "client cannot write game doc",
    updateDoc(doc(activeClient.db, "games", game1.id), { winner: activeUid })
  );
  await expectDenied(
    "client cannot delete opponent queue entry",
    deleteDoc(doc(activeClient.db, "queues/tictactoe_standard/entries", inactiveUid))
  );
  const outsider = makeClient("outsider");
  await signInAnonymously(outsider.auth);
  await expectDenied(
    "non-player cannot read game",
    getDoc(doc(outsider.db, "games", game1.id))
  );

  console.log("== illegal move is rejected by the engine");
  await submitMove(activeClient, game1.id, 0, activeUid, 4);
  await waitFor("legal move applied", async () => (await readGame(a, game1.id)).round === 1);
  const nextGame = await readGame(a, game1.id);
  const nextActive = nextGame.activePlayerId;
  const nextClient = nextActive === uidA ? a : b;
  await submitMove(nextClient, game1.id, 1, nextActive, 4);
  const rejection = await waitFor("rejection recorded", async () => {
    const snap = await getDoc(doc(nextClient.db, `games/${game1.id}/logs`, `1-${nextActive}-rejected`));
    return snap.exists() ? snap.data() : null;
  });
  check(rejection.errors.includes("cellOccupied"), "occupied cell rejected with cellOccupied");
  const rejectedSubSnap = await getDoc(
    doc(nextClient.db, `games/${game1.id}/submissions`, `1-${nextActive}`)
  );
  check(!rejectedSubSnap.exists(), "rejected submission doc deleted so the round key is reusable");
  const unchanged = await readGame(a, game1.id);
  check(unchanged.round === 1, "rejected submission does not advance round");

  console.log("== play game 1 to an X win");
  const clients = [a, b];
  const uids = [uidA, uidB];
  const marks1 = JSON.parse(unchanged.stateJson).marks;
  const finished1 = await playGame(clients, uids, game1.id, [1, 0, 2, 8]);
  check(finished1.status === "complete", "game 1 complete");
  check(finished1.winner === marks1.X, "X wins game 1");
  check(finished1.activePlayerId === null, "no active player after completion");
  check(finished1.turnDeadline === null, "no deadline after completion");

  await expectDenied(
    "cannot submit to finished game",
    submitMove(clients[uids.indexOf(marks1.O)], game1.id, finished1.round, marks1.O, 5)
  );

  console.log("== matchmaking game 2 (draw)");
  const known = new Set([game1.id]);
  await joinQueue(a, uidA);
  await joinQueue(b, uidB);
  const game2 = await waitFor("game 2 created", () => findGame(a, uidA, known));
  const finished2 = await playGame(clients, uids, game2.id, [0, 4, 8, 1, 7, 6, 2, 5, 3]);
  check(finished2.status === "complete", "game 2 complete");
  check(finished2.winner === null, "game 2 is a draw");
  check(finished2.round === 9, "draw game took 9 rounds");

  console.log("== forfeit by deadline sweep");
  const { initializeApp: initializeAdminApp } = await import("firebase-admin/app");
  const { getFirestore: getAdminFirestore, Timestamp: AdminTimestamp } = await import(
    "firebase-admin/firestore"
  );
  const adminApp = initializeAdminApp({ projectId: "demo-quickgames" }, "e2e-admin");
  const adminDb = getAdminFirestore(adminApp);
  const known3 = new Set([game1.id, game2.id]);
  await joinQueue(a, uidA);
  await joinQueue(b, uidB);
  const game3 = await waitFor("game 3 created", () => findGame(a, uidA, known3));
  await adminDb.doc(`games/${game3.id}`).update({
    turnDeadline: AdminTimestamp.fromMillis(Date.now() - 3600 * 1000),
  });
  const sweepResponse = await fetch("http://127.0.0.1:5001/demo-quickgames/us-central1/sweepNow");
  check(sweepResponse.ok, `deadline sweep triggered (http ${sweepResponse.status})`);
  const forfeited = await waitFor("game 3 forfeited", async () => {
    const updated = await readGame(a, game3.id);
    return updated.status === "forfeited" ? updated : null;
  });
  const expectedWinner = game3.playerUids.find((uid) => uid !== game3.activePlayerId);
  check(forfeited.winner === expectedWinner, "idle player forfeits, opponent wins");
  check(forfeited.activePlayerId === null, "no active player after forfeit");

  if (failures > 0) {
    console.error(`\n${failures} check(s) failed`);
    process.exit(1);
  }
  console.log("\nAll end-to-end checks passed");
  process.exit(0);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
