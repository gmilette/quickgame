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
Firestore wakes it when someone joins a game, makes a move or resigns, so there is nothing to start
or stop before playing. A weekly Cloud Scheduler job forfeits games whose turn
deadline has passed and removes open games nobody joined within three days.

Install the app on each Android phone:
```
./gradlew :app:android:installDebug
```

Push changes live, in this order:
```
npx firebase deploy --only firestore --project quickgame-336e4   # rules and indexes
./deploy.sh                                                      # server code
./setup-triggers.sh                                              # triggers and push messaging, run once
```
Then reinstall the app on each phone. The app, rules and server have to match,
so do all of these together.

Read the server logs:
```
gcloud run services logs read quickgames-server --project quickgame-336e4 --region us-central1
```

## Push notifications

The server sends a notification through Firebase Cloud Messaging when:

- someone joins a game you started
- it becomes your turn
- a game ends, by a final move, a resignation or a missed deadline

Tapping a notification opens that game. Nothing is shown while the app is open
in the foreground, since the screens update live.

Each phone saves its device token at `users/{uid}/private/push`, readable only
by that player. Android 13 and later ask for notification permission on first
launch; a player who declines gets no notifications. Android only for now.

Against the emulators the server sends nothing. The end-to-end test sets
`QUICKGAMES_NOTIFY=log`, which records each notification in a
`debugNotifications` collection so the test can check who was notified.

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
gcloud eventarc triggers delete quickgames-open-game --project quickgame-336e4 --location us-central1
gcloud eventarc triggers delete quickgames-resignation --project quickgame-336e4 --location us-central1
gcloud eventarc triggers delete quickgames-submission --project quickgame-336e4 --location us-central1
gcloud scheduler jobs delete quickgames-sweep --project quickgame-336e4 --location us-central1
gcloud run services delete quickgames-server --project quickgame-336e4 --region us-central1
```

## Adding a game

1. Create `games/<name>` (engine, no UI) and `games/<name>-ui` (Compose screen),
   copying `games/rps` and `games/rps-ui`.
2. Add a `GameType` entry in `core/model`.
3. Register the definition in `server/.../Registry.kt` and in `MainActivity.kt`
   (definition and UI).

A game that needs hidden information overrides `publicStateJson`; the server
then keeps the full state where players cannot read it.
