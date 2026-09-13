import { initializeApp } from "firebase/app";
import { connectAuthEmulator, getAuth, signInAnonymously } from "firebase/auth";
import {
  collection,
  connectFirestoreEmulator,
  doc,
  getDoc,
  getDocs,
  initializeFirestore,
  onSnapshot,
  query,
  serverTimestamp,
  setDoc,
  where,
} from "firebase/firestore";

const HOST = "127.0.0.1";
const config = { apiKey: "fake-emulator-api-key", projectId: "demo-quickgames", appId: "1:0:web:opp" };

const app = initializeApp(config, "opponent");
const auth = getAuth(app);
connectAuthEmulator(auth, `http://${HOST}:9099`, { disableWarnings: true });
const db = initializeFirestore(app, { experimentalForceLongPolling: true });
connectFirestoreEmulator(db, HOST, 8080);

function log(...args) {
  console.log(new Date().toISOString().slice(11, 19), ...args);
}

async function main() {
  const uid = (await signInAnonymously(auth)).user.uid;
  log("opponent signed in as", uid);

  await setDoc(doc(db, "queues/tictactoe_standard/entries", uid), {
    uid,
    displayName: "Scripted-Bot",
    createdAt: serverTimestamp(),
  });
  log("joined queue, waiting for match...");

  let gameId = null;
  const start = Date.now();
  while (!gameId && Date.now() - start < 120000) {
    const snap = await getDocs(
      query(collection(db, "games"), where("playerUids", "array-contains", uid))
    );
    const active = snap.docs.find((d) => d.data().status === "active");
    if (active) gameId = active.id;
    else await new Promise((r) => setTimeout(r, 500));
  }
  if (!gameId) {
    log("no match found, exiting");
    process.exit(1);
  }
  log("matched into game", gameId);

  let done = false;
  onSnapshot(doc(db, "games", gameId), async (snap) => {
    if (done || !snap.exists()) return;
    const game = snap.data();
    const marks = JSON.parse(game.stateJson).marks;
    const myMark = Object.keys(marks).find((m) => marks[m] === uid);
    if (game.status !== "active") {
      done = true;
      const outcome =
        game.winner === uid ? "bot won" : game.winner ? "bot lost" : "draw";
      log(`game over: ${game.status} (${outcome}); bot was ${myMark}`);
      process.exit(0);
    }
    if (game.activePlayerId !== uid) return;
    const state = JSON.parse(game.stateJson);
    const cell = state.cells.findIndex((c) => c === "");
    if (cell < 0) return;
    log(`my turn (${myMark}), round ${game.round}, playing cell ${cell}`);
    await setDoc(doc(db, `games/${gameId}/submissions`, `${game.round}-${uid}`), {
      payloadJson: JSON.stringify({ cell }),
      submittedAt: serverTimestamp(),
    });
  });
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
