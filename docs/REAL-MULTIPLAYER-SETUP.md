# Setting up real (non-emulator) multiplayer

This turns the emulator-only build into a real deployment where two people on
different devices, on different networks, can play against each other.

Everything today points at the local Firebase emulator with fake credentials.
"Going live" is three things: a real Firebase project, the server pieces
deployed to it, and the client apps pointed at it instead of the emulator.

---

## 0. Prerequisites

- A Google account and the Firebase CLI: `npm i -g firebase-tools` (or keep
  using `npx firebase`), then `firebase login`.
- **The Blaze (pay-as-you-go) plan.** Cloud Functions require it. There is a
  generous free tier; a friends-and-family tic-tac-toe will almost certainly
  cost $0, but a billing account must be attached.
- Xcode (for the iOS build) and the Android SDK (already set up here).

---

## 1. Create the Firebase project

Console path (easiest): https://console.firebase.google.com → **Add project**.
Pick a project id, e.g. `quickgames-prod`. Note the **project id** and the
**project number** (Project settings → General) — you need both below.

Then point this repo at it:

```jsonc
// .firebaserc
{
  "projects": { "default": "quickgames-prod" }
}
```

---

## 2. Enable the services the app uses

In the console for your new project:

1. **Authentication → Sign-in method → Anonymous → Enable.** The app signs in
   anonymously today; without this, sign-in fails and nothing loads.
2. **Firestore Database → Create database → Production mode.** Pick a region
   close to your players (e.g. `us-central`). Region is permanent.

(Our own security rules from `firestore.rules` replace the default ones in
step 6, so the "production mode" starting rules don't matter.)

---

## 3. Register the Android app and get its config

Console → Project settings → **Your apps → Add app → Android**.

- **Package name:** `com.greenopal.quickgames` (must match `applicationId` in
  `app/android/build.gradle.kts`).
- Download the generated **`google-services.json`**.

You need three values from it (or shown in the console):

| Client field | Where in `google-services.json` |
|---|---|
| `applicationId` | `client[0].client_info.mobilesdk_app_id` (looks like `1:PROJECTNUMBER:android:HASH`) |
| `apiKey` | `client[0].api_key[0].current_key` |
| `projectId` | `project_info.project_id` |

---

## 4. Register the iOS app and get its config

Console → Project settings → **Your apps → Add app → iOS**.

- **Bundle ID:** `com.greenopal.quickgames.iosApp` (matches
  `PRODUCT_BUNDLE_IDENTIFIER` in `app/ios/iosApp/project.yml`).
- Download **`GoogleService-Info.plist`**. You need `GOOGLE_APP_ID`,
  `GCM_SENDER_ID`, `API_KEY`, and `PROJECT_ID` from it.

---

## 5. Point the clients at production

Two edits per platform: real credentials, and turn the emulator flag off.

### Android — `app/android/.../app/QuickGamesApplication.kt`

Replace the fake `FirebaseOptions` with your real values and set
`useEmulators = false`:

```kotlin
Firebase.initialize(
    this,
    FirebaseOptions(
        applicationId = "1:PROJECTNUMBER:android:HASH", // mobilesdk_app_id
        apiKey = "AIzaSy...your-real-android-key",
        projectId = "quickgames-prod"
    )
)
client = FirebaseClient.create(
    useEmulators = false,
    emulatorHost = BuildConfig.EMULATOR_HOST // ignored when useEmulators = false
)
```

> Cleaner alternative: add the `com.google.gms.google-services` Gradle plugin,
> drop `google-services.json` into `app/android/`, and call
> `Firebase.initialize(this)` with no options — the plugin supplies them. Either
> works; the manual form above avoids touching the build config.

Optional hardening: remove `android:usesCleartextTraffic="true"` from
`app/android/src/main/AndroidManifest.xml` (it was only needed for the
plaintext emulator connection; production is HTTPS).

### iOS — `app/ios/iosApp/iosApp/iOSApp.swift`

Either drop the real `GoogleService-Info.plist` into `app/ios/iosApp/iosApp/`,
add it to the target in `project.yml` (`sources`), and simplify to
`FirebaseApp.configure()` — or paste the real values into the existing manual
options block.

### iOS — `app/ios/.../ios/MainViewController.kt`

```kotlin
val client = FirebaseClient.create(useEmulators = false, emulatorHost = "127.0.0.1")
```

> Recommended refinement (both platforms): make `useEmulators` a build flag
> (debug = emulator, release = production) instead of a hardcoded literal, so
> you never ship an emulator build by accident. Ask me and I'll wire it.

---

## 6. Deploy the server

From the repo root, with `.firebaserc` pointing at the real project:

```bash
# Firestore security rules + the composite index for the deadline sweep
npx firebase deploy --only firestore:rules,firestore:indexes

# Cloud Functions: matchmaking, turn resolution, deadline sweep
npm --prefix functions run build
npx firebase deploy --only functions
```

Deploying functions on Blaze automatically provisions **Cloud Scheduler** for
the hourly `deadlineSweep`. The first functions deploy also enables the
required Google Cloud APIs (Cloud Build, Artifact Registry) — approve if
prompted.

Verify in the console: Functions shows `onQueueEntryCreated`,
`onSubmissionCreated`, `deadlineSweep`; Firestore → Indexes shows the
`games (status, turnDeadline)` composite index as *Enabled*.

---

## 7. Build and install real apps

- **Android:** `./gradlew :app:android:assembleRelease` (or `installRelease` to
  a connected device). For sharing, sign the release build or use
  `assembleDebug` — a debug APK talks to production Firebase just fine.
- **iOS:** finish the one open build issue first (see
  `IMPLEMENTATION-PLAN.md` / project notes on the CocoaPods dummy-framework
  platform fix), then archive from Xcode and distribute via TestFlight.

Install on two different devices, open the app on each, tap Tic-Tac-Toe on
both, and they will be matched together — now over the internet, not localhost.

---

## 8. Known gaps for "real" async play

- **No push notifications yet.** Turn changes are delivered by the Firestore
  snapshot listener, which only fires while the app is open. For true async
  play ("it's your turn" hours later), FCM/APNs is Phase E in the platform
  plan — not built. Until then, players only see moves live while both have the
  app foregrounded.
- **Anonymous identity is per-device.** An anonymous user can't sign in on a
  second device or recover their games after uninstall. Switching to Play Games
  / Sign in with Apple (also platform-plan work) fixes this.
- **No App Check.** The security rules are server-authoritative and safe, but
  adding Firebase App Check later blocks abuse from outside your apps.

None of these block a working game between two real devices; they're the
difference between "playable" and "polished."
```
