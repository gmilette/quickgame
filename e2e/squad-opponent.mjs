// A scripted Squad opponent for trying the game in the app against the local emulators.
// It hosts a game (skirmish by default, or pass "raid") and, on each of its turns, turns one
// soldier a quarter turn and ends the turn.
//
//   node e2e/squad-opponent.mjs [skirmish|raid]
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
const variant = process.argv[2] ?? "skirmish";
const config = { apiKey: "fake-emulator-api-key", projectId: "demo-quickgames", appId: "1:0:web:squadbot" };

const app = initializeApp(config, "squad-opponent");
const auth = getAuth(app);
connectAuthEmulator(auth, `http://${HOST}:9099`, { disableWarnings: true });
const db = initializeFirestore(app, { experimentalForceLongPolling: true });
connectFirestoreEmulator(db, HOST, 8080);

function log(...args) {
  console.log(new Date().toISOString().slice(11, 19), ...args);
}

async function main() {
  const uid = (await signInAnonymously(auth)).user.uid;
  await setDoc(doc(db, "users", uid), { displayName: "Squad-Bot" });
  await setDoc(doc(db, "openGames", `${uid}_squad`), {
    gameType: "squad",
    variant,
    hostUid: uid,
    hostName: "Squad-Bot",
    guestUid: null,
    guestName: null,
    createdAt: serverTimestamp(),
  });
  log(`hosting a squad ${variant} game as ${uid}; join it from the app`);

  let gameId = null;
  while (!gameId) {
    const snap = await getDocs(query(collection(db, "games"), where("playerUids", "array-contains", uid)));
    const active = snap.docs.find((d) => d.data().status === "active" && d.data().gameType === "squad");
    if (active) gameId = active.id;
    else await new Promise((r) => setTimeout(r, 1000));
  }
  log("game started", gameId);

  let lastRound = -1;
  onSnapshot(doc(db, "games", gameId), async (snap) => {
    const game = snap.data();
    if (game.status !== "active") {
      log("game over, winner", game.winner);
      process.exit(0);
    }
    if (game.activePlayerId !== uid || game.round === lastRound) return;
    lastRound = game.round;
    const view = JSON.parse((await getDoc(doc(db, `games/${gameId}/views`, uid))).data().stateJson);
    const soldier = view.units.find((u) => u.owner === uid && u.health > 0);
    const orders = soldier ? [{ unit: soldier.id, type: "FACE", dir: (soldier.facing + 2) % 8 }] : [];
    await setDoc(doc(db, `games/${gameId}/submissions`, `${game.round}-${uid}`), {
      payloadJson: JSON.stringify({ orders }),
      submittedAt: serverTimestamp(),
    });
    log(`round ${game.round}: turned ${soldier?.name ?? "nobody"}`);
  });
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
