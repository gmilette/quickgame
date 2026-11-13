import { initializeApp } from "firebase/app";
import { getAuth, signInAnonymously } from "firebase/auth";
import { collection, doc, getDoc, getDocs, getFirestore, query, serverTimestamp, setDoc, where } from "firebase/firestore";

const config = { apiKey: "AIzaSyDKvame0OtvpTAzn7xwCsCYLci2IuEaNjQ", projectId: "quickgame-336e4", appId: "1:1047416669336:android:f80c551e899bd84804fced" };

async function waitFor(label, fn, timeoutMs = 90000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    const value = await fn();
    if (value) return { value, ms: Date.now() - start };
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`timeout waiting for: ${label}`);
}

function client(name) {
  const app = initializeApp(config, name);
  return { auth: getAuth(app), db: getFirestore(app) };
}

const a = client("a");
const b = client("b");
const uidA = (await signInAnonymously(a.auth)).user.uid;
const uidB = (await signInAnonymously(b.auth)).user.uid;
const clients = { [uidA]: a, [uidB]: b };
for (const [uid, c] of Object.entries(clients)) {
  await setDoc(doc(c.db, "queues/tictactoe_standard/entries", uid), { uid, displayName: "Smoke", createdAt: serverTimestamp() });
}
const found = await waitFor("game created", async () => {
  const snap = await getDocs(query(collection(a.db, "games"), where("playerUids", "array-contains", uidA)));
  return snap.docs[0] ?? null;
});
const gameId = found.value.id;
console.log(`matched in ${found.ms} ms, game ${gameId}`);
let game = found.value.data();
for (const cell of [0, 3, 1, 4, 2]) {
  const uid = game.activePlayerId;
  const round = game.round;
  await setDoc(doc(clients[uid].db, `games/${gameId}/submissions`, `${round}-${uid}`), { payloadJson: JSON.stringify({ cell }), submittedAt: serverTimestamp() });
  const next = await waitFor(`round ${round}`, async () => {
    const g = (await getDoc(doc(a.db, "games", gameId))).data();
    return g.round > round ? g : null;
  });
  game = next.value;
  console.log(`move ${round} applied in ${next.ms} ms`);
}
const marks = JSON.parse(game.stateJson).marks;
console.log(game.status === "complete" && game.winner === marks.X ? "PASS: X won through the cloud server" : `FAIL: ${game.status} ${game.winner}`);
process.exit(0);
