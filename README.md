# Loose

Android app for **Loose Leaf**. Turn on a floating circle that sits over every app; tap it, write an idea, press send, and it is saved to your Loose Leaf server with your API key.

## Using the app
1. Open **Loose**, enter your server address (for example `mysite.com/loose`) and paste your API key (`ll_...`, created on the Loose Leaf website under API keys). Press **Save**. The key is checked against the server first.
2. Turn on **Floating circle**. Android asks for "Display over other apps": allow it for Loose and come back.
3. Drag the circle anywhere. Tap it to open the text box (Enter = new line, the box grows). Press the paper plane to send. Back closes the box.

Only **one** key is stored at a time; saving a new one replaces it. Each key writes to its own idea file on the server (`action=idea_add`).

## Get the APK from GitHub
1. Create a GitHub repository and push this folder to it (`main` branch).
2. Open the **Actions** tab, then **Build APK**. It runs on every push; you can also press *Run workflow*.
3. When it finishes, download **Loose-apk** from the run page and install `Loose.apk` (allow "install unknown apps").
4. To publish a release with the APK attached: `git tag v1.0.0 && git push --tags`.

## Build locally
Needs Node 20, JDK 17 and the Android SDK.
```
npm install --legacy-peer-deps
npx expo prebuild --platform android --clean
cd android && ./gradlew assembleRelease
```
The APK is in `android/app/build/outputs/apk/release/`. (The release build is signed with the debug key, which is fine for installing yourself; use your own keystore for Play Store.)

## How it is built
- `App.js`: the app screen (switch + server/API key + Save).
- `modules/loose-overlay/`: a local Expo native module (Kotlin). `OverlayService` is a foreground service that draws the circle and text box over other apps and posts ideas; `LoosePrefs` stores the single key.
- `.github/workflows/build-apk.yml`: builds the APK in the cloud.

Server notes: use an `https://` address (plain `http://` also works). The key is sent as `Authorization: Bearer` and `X-API-Key`.
