import { spawn, spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
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
const SERVER_PORT = 8081;
const config = { apiKey: "fake-emulator-api-key", projectId: "demo-quickgames", appId: "1:0:web:e2e" };

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, "..");
const serverBin = path.join(repoRoot, "server", "build", "install", "server", "bin", "server");
let serverProcess = null;

function buildServerIfNeeded() {
  if (existsSync(serverBin)) return;
  console.log("== building quickgames server (first run only)");
  const result = spawnSync("./gradlew", [":server:installDist", "--console=plain"], {
    cwd: repoRoot,
    stdio: "inherit",
  });
  if (result.status !== 0) {
    throw new Error("gradle build of :server failed");
  }
}

function startServer() {
  buildServerIfNeeded();
  serverProcess = spawn(serverBin, [], {
    cwd: repoRoot,
    env: {
      ...process.env,
      FIRESTORE_EMULATOR_HOST: `${HOST}:8080`,
      GOOGLE_CLOUD_PROJECT: "demo-quickgames",
      QUICKGAMES_POLL: "true",
      QUICKGAMES_NOTIFY: "log",
      SERVER_PORT: String(SERVER_PORT),
    },
    stdio: ["ignore", "inherit", "inherit"],
  });
  process.on("exit", () => {
    serverProcess?.kill();
  });
}

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

async function hostGame(client, uid, gameType) {
  await setDoc(doc(client.db, "openGames", `${uid}_${gameType}`), {
    gameType,
    variant: "standard",
    hostUid: uid,
    hostName: `Host-${uid.slice(0, 4)}`,
    guestUid: null,
    guestName: null,
    createdAt: serverTimestamp(),
  });
}

async function joinGame(client, uid, hostUid, gameType) {
  await updateDoc(doc(client.db, "openGames", `${hostUid}_${gameType}`), {
    guestUid: uid,
    guestName: `Guest-${uid.slice(0, 4)}`,
  });
}

async function startGame(host, hostUid, guest, guestUid, gameType, known) {
  await hostGame(host, hostUid, gameType);
  await joinGame(guest, guestUid, hostUid, gameType);
  return (await waitFor(`${gameType} game created`, () => findGame(host, hostUid, known)));
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
  await submitPayload(client, gameId, round, uid, { cell });
}

async function submitPayload(client, gameId, round, uid, payload) {
  await setDoc(doc(client.db, `games/${gameId}/submissions`, `${round}-${uid}`), {
    payloadJson: JSON.stringify(payload),
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
  console.log("== starting quickgames server");
  startServer();
  await waitFor("server healthz", async () => {
    try {
      const res = await fetch(`http://${HOST}:${SERVER_PORT}/healthz`);
      return res.ok;
    } catch {
      return false;
    }
  });

  console.log("== signing in two anonymous users");
  const a = makeClient("a");
  const b = makeClient("b");
  const uidA = (await signInAnonymously(a.auth)).user.uid;
  const uidB = (await signInAnonymously(b.auth)).user.uid;
  check(uidA && uidB && uidA !== uidB, "two distinct anonymous users");

  console.log("== open games: host, list, join");
  const outsider = makeClient("outsider");
  const uidC = (await signInAnonymously(outsider.auth)).user.uid;
  await hostGame(a, uidA, "tictactoe");
  const listed = await getDocs(collection(b.db, "openGames"));
  check(listed.docs.some((d) => d.data().hostUid === uidA), "other players can see the open game");
  await expectDenied("host cannot join own game", joinGame(a, uidA, uidA, "tictactoe"));
  await expectDenied(
    "cannot host under another player's id",
    setDoc(doc(b.db, "openGames", `${uidA}_rps`), {
      gameType: "rps", variant: "standard", hostUid: uidA, hostName: "x", guestUid: null, guestName: null, createdAt: serverTimestamp(),
    })
  );
  await expectDenied(
    "guest cannot delete the host's open game",
    deleteDoc(doc(b.db, "openGames", `${uidA}_tictactoe`))
  );
  await joinGame(b, uidB, uidA, "tictactoe");
  try {
    await joinGame(outsider, uidC, uidA, "tictactoe");
    check(false, "second joiner is refused (write succeeded)");
  } catch (error) {
    check(["permission-denied", "not-found"].includes(error.code), `second joiner is refused (got ${error.code})`);
  }
  const game1 = await waitFor("game 1 created", () => findGame(a, uidA, new Set()));
  check(game1.status === "active", "game created active");
  check(game1.playerUids.includes(uidA) && game1.playerUids.includes(uidB), "both players in game");
  check(game1.round === 0, "game starts at round 0");
  check(game1.turnDeadline != null, "turn deadline set");
  const state1 = JSON.parse(game1.stateJson);
  check(state1.marks.X === game1.activePlayerId, "X moves first");

  const openSnapshot = await getDoc(doc(a.db, "openGames", `${uidA}_tictactoe`));
  check(!openSnapshot.exists(), "open game removed once the game starts");

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
  const game2 = await startGame(a, uidA, b, uidB, "tictactoe", known);
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
  const game3 = await startGame(b, uidB, a, uidA, "tictactoe", known3);
  await adminDb.doc(`games/${game3.id}`).update({
    turnDeadline: AdminTimestamp.fromMillis(Date.now() - 3600 * 1000),
  });
  const sweepResponse = await fetch(`http://${HOST}:${SERVER_PORT}/sweepNow`);
  check(sweepResponse.ok, `deadline sweep triggered (http ${sweepResponse.status})`);
  const forfeited = await waitFor("game 3 forfeited", async () => {
    const updated = await readGame(a, game3.id);
    return updated.status === "forfeited" ? updated : null;
  });
  const expectedWinner = game3.playerUids.find((uid) => uid !== game3.activePlayerId);
  check(forfeited.winner === expectedWinner, "idle player forfeits, opponent wins");
  check(forfeited.activePlayerId === null, "no active player after forfeit");

  console.log("== stale open games are swept");
  await hostGame(a, uidA, "tictactoe");
  await adminDb.doc(`openGames/${uidA}_tictactoe`).update({
    createdAt: AdminTimestamp.fromMillis(Date.now() - 100 * 3600 * 1000),
  });
  await fetch(`http://${HOST}:${SERVER_PORT}/sweepNow`);
  check(!(await getDoc(doc(a.db, "openGames", `${uidA}_tictactoe`))).exists(), "stale open game deleted");

  console.log("== host can cancel an open game");
  await hostGame(a, uidA, "rps");
  await deleteDoc(doc(a.db, "openGames", `${uidA}_rps`));
  check(!(await getDoc(doc(a.db, "openGames", `${uidA}_rps`))).exists(), "host cancelled open game");

  console.log("== resign");
  const known4 = new Set([game1.id, game2.id, game3.id]);
  const game4 = await startGame(a, uidA, b, uidB, "tictactoe", known4);
  await expectDenied(
    "non-player cannot resign someone else's game",
    setDoc(doc(outsider.db, `games/${game4.id}/resignations`, uidC), { createdAt: serverTimestamp() })
  );
  await expectDenied(
    "cannot resign on behalf of the opponent",
    setDoc(doc(a.db, `games/${game4.id}/resignations`, uidB), { createdAt: serverTimestamp() })
  );
  const waitingUid = game4.playerUids.find((uid) => uid !== game4.activePlayerId);
  await setDoc(doc(clients[uids.indexOf(waitingUid)].db, `games/${game4.id}/resignations`, waitingUid), {
    createdAt: serverTimestamp(),
  });
  const resigned = await waitFor("game 4 resigned", async () => {
    const updated = await readGame(a, game4.id);
    return updated.status === "forfeited" ? updated : null;
  });
  check(resigned.winner === game4.activePlayerId, "resigning out of turn hands the win to the opponent");

  console.log("== rock paper scissors with a hidden first choice");
  const known5 = new Set([...known4, game4.id]);
  let rps = await startGame(a, uidA, b, uidB, "rps", known5);
  check(rps.gameType === "rps", "rps game created");
  const rpsPlay = async (choice) => {
    const uid = rps.activePlayerId;
    const round = rps.round;
    await submitPayload(clients[uids.indexOf(uid)], rps.id, round, uid, { choice });
    rps = await waitFor(`rps round ${round}`, async () => {
      const updated = await readGame(a, rps.id);
      return updated.round > round ? updated : null;
    });
    return uid;
  };
  const firstUid = await rpsPlay("rock");
  check(!rps.stateJson.includes("rock"), "first choice is not visible in the public game state");
  check(JSON.parse(rps.stateJson).pendingBy === firstUid, "public state shows who has chosen");
  await expectDenied(
    "players cannot read the secret state",
    getDoc(doc(clients[uids.indexOf(rps.activePlayerId)].db, `games/${rps.id}/secret`, "state"))
  );
  await rpsPlay("scissors");
  const afterRound = JSON.parse(rps.stateJson);
  check(afterRound.scores[firstUid] === 1, "rock beat scissors");
  check(afterRound.history[0].choices[firstUid] === "rock", "choices are revealed after the round");
  await rpsPlay("paper");
  await rpsPlay("scissors");
  check(rps.status === "complete" && rps.winner === firstUid, "first to two wins the rps game");

  console.log("== display names");
  await setDoc(doc(a.db, "users", uidA), { displayName: "Greg" });
  check((await getDoc(doc(b.db, "users", uidA))).data().displayName === "Greg", "name saved and readable");
  await expectDenied("cannot set another player's name", setDoc(doc(b.db, "users", uidA), { displayName: "Hax" }));

  console.log("== push tokens are private");
  const pushRef = (client, uid) => doc(client.db, `users/${uid}/private`, "push");
  await setDoc(pushRef(a, uidA), { token: "token-a", updatedAt: serverTimestamp() });
  check((await getDoc(pushRef(a, uidA))).data().token === "token-a", "player can save and read own push token");
  await expectDenied("other players cannot read a push token", getDoc(pushRef(b, uidA)));
  await expectDenied(
    "other players cannot overwrite a push token",
    setDoc(pushRef(b, uidA), { token: "evil", updatedAt: serverTimestamp() })
  );

  console.log("== notifications");
  const noticesFor = async (gameId) =>
    (await adminDb.collection("debugNotifications").where("gameId", "==", gameId).get()).docs.map((d) => d.data());
  const waitForNotice = (gameId, uid, titlePart) =>
    waitFor(`notice "${titlePart}" for ${uid.slice(0, 4)}`, async () =>
      (await noticesFor(gameId)).find((n) => n.uid === uid && n.title.includes(titlePart))
    );
  const known6 = new Set([...known5, rps.id]);
  let noticeGame = await startGame(a, uidA, b, uidB, "tictactoe", known6);
  check(Boolean(await waitForNotice(noticeGame.id, uidA, "joined")), "host is notified when someone joins");
  const firstMover = noticeGame.activePlayerId;
  const secondMover = noticeGame.playerUids.find((uid) => uid !== firstMover);
  await submitMove(clients[uids.indexOf(firstMover)], noticeGame.id, 0, firstMover, 0);
  check(Boolean(await waitForNotice(noticeGame.id, secondMover, "your turn")), "next player is notified of their turn");
  const moverNotices = (await noticesFor(noticeGame.id)).filter((n) => n.uid === firstMover && n.title.includes("your turn"));
  check(moverNotices.length === 0, "the player who just moved is not notified");
  await waitFor("move applied", async () => (await readGame(a, noticeGame.id)).round === 1);
  await setDoc(doc(clients[uids.indexOf(firstMover)].db, `games/${noticeGame.id}/resignations`, firstMover), {
    createdAt: serverTimestamp(),
  });
  check(Boolean(await waitForNotice(noticeGame.id, secondMover, "you win")), "winner is notified when the opponent resigns");
  const rpsNotices = await noticesFor(rps.id);
  check(rpsNotices.some((n) => n.title.includes("game over")), "loser is notified when the game ends");

  console.log("== mini rpg: a full game through the server");
  const known7 = new Set([...known6, noticeGame.id]);
  let rpg = await startGame(a, uidA, b, uidB, "miniRpg", known7);
  check(rpg.gameType === "miniRpg", "rpg game created");
  check(rpg.seed === undefined, "dice seed is not in the public game doc");
  await expectDenied("players cannot read the secret dice seed", getDoc(doc(a.db, `games/${rpg.id}/secret`, "seed")));
  let rpgPicks = 0;
  let samePlayerTwice = false;
  while (rpg.status === "active" && rpgPicks < 25) {
    const rpgState = JSON.parse(rpg.stateJson);
    const uid = rpg.activePlayerId;
    const hero = rpgState.heroes[uid];
    const weakest = rpgState.squares.filter((s) => s.claimedBy === null).sort((x, y) => x.tier - y.tier)[0];
    const payload = { squareId: weakest.id, boonChoices: hero.pendingOffers.map((offer) => offer[0]) };
    if (hero.pendingLoot) payload.lootDecision = "EQUIP";
    const round = rpg.round;
    await submitPayload(clients[uids.indexOf(uid)], rpg.id, round, uid, payload);
    const next = await waitFor(`rpg pick ${round}`, async () => {
      const updated = await readGame(a, rpg.id);
      return updated.round > round ? updated : null;
    });
    if (next.status === "active" && next.activePlayerId === uid) samePlayerTwice = true;
    rpg = next;
    rpgPicks += 1;
  }
  const rpgFinal = JSON.parse(rpg.stateJson);
  check(rpgPicks === 20, `rpg hunt took 20 picks (got ${rpgPicks})`);
  check(samePlayerTwice, "snake order gives a player two picks in a row");
  check(rpg.status === "complete" && rpgFinal.finalBattle !== null, "final battle resolved after the last pick");
  check(rpg.winner === rpgFinal.winner && rpg.playerUids.includes(rpg.winner), "rpg winner recorded on the game");
  check(Object.values(rpgFinal.heroes).every((hero) => hero.level >= 3), "both heroes levelled up during the hunt");

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
