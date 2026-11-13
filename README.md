# QuickGames

A Kotlin Multiplatform + Firebase platform for small async multiplayer games
(tic-tac-toe is the reference game). Game rules live once, in Kotlin
(`core/engine`, `games/<name>`), and are shared by the client apps and the
server — there is no separate server-side reimplementation.

## Local testing (emulators) — do this every time you test

You do **not** need `gcloud`, billing, or a deploy for this. Three things run
locally: the Firestore/Auth emulators, the Kotlin server, and (optionally) the
Android/iOS app.

### Start

1. **Emulators** (Firestore + Auth), in one terminal:
   ```
   npx firebase emulators:start --only firestore,auth
   ```
2. **Server**, in a second terminal — build once, then run (rebuild only after
   changing code under `server/` or `games/*`):
   ```
   npm run server:build   # ./gradlew :server:installDist
   npm run server         # runs server/build/install/server/bin/server
   ```
   You'll see log lines like `matchmaking poll loop started` and
   `http control listening on :8081` once it's ready. Check it with:
   ```
   curl http://127.0.0.1:8081/healthz
   ```

### Stop

- Server: `Ctrl+C` in its terminal. If it's stuck in the background:
  ```
  lsof -ti:8081 | xargs kill
  ```
- Emulators: `Ctrl+C` in their terminal.

### Automated end-to-end test

This spins up the emulators **and** builds/starts/stops the server for you —
nothing needs to be running beforehand:
```
npm run e2e
```

### Note: the app currently points at the real project

`QuickGamesApplication.kt` (Android) and `iOSApp.swift` (iOS) are wired to the
real Firebase project `quickgame-336e4` (`useEmulators = false`). To run the
Android/iOS app against your local emulators instead, temporarily flip
`useEmulators` back to `true` in `QuickGamesApplication.kt` (and swap the
`apiKey`/`projectId` back to `"fake-emulator-api-key"` / `"demo-quickgames"` in
both that file and `iOSApp.swift`) before building. `npm run e2e` doesn't need
any of this — it talks to the emulators directly.

## Playing for real on two phones

The server runs on Cloud Run in project `quickgame-336e4`. It sleeps until
Firestore wakes it for a new queue entry or move, so there is nothing to start
or stop before playing. A weekly Cloud Scheduler job forfeits games whose turn
deadline has passed.

Install the app on each Android phone:
```
./gradlew :app:android:installDebug
```

Redeploy the server after changing code under `server/`, `core/` or `games/`:
```
./deploy.sh
```

Read the server logs:
```
gcloud run services logs read quickgames-server --project quickgame-336e4 --region us-central1
```

## Turning the server on and off

You normally don't need to. The server sleeps when nobody is playing and only
costs money for the seconds it spends handling a move.

Turn it off (matchmaking and moves stop being processed):
```
./server-off.sh
```

Turn it back on:
```
./server-on.sh
```

Off blocks Firestore events from reaching the server and pauses the weekly
sweep. Nothing is deleted and no redeploy is needed. Events that arrive while
it is off are retried for about a day, so moves made during a short off
period are applied once it is back on. Moves made during a longer off period
are not picked up; the player has to wait for the turn to be forfeited.

Remove everything from the cloud for good (run `./deploy.sh` and recreate the
triggers to come back from this):
```
gcloud eventarc triggers delete quickgames-queue-entry --project quickgame-336e4 --location us-central1
gcloud eventarc triggers delete quickgames-submission --project quickgame-336e4 --location us-central1
gcloud scheduler jobs delete quickgames-sweep --project quickgame-336e4 --location us-central1
gcloud run services delete quickgames-server --project quickgame-336e4 --region us-central1
```
