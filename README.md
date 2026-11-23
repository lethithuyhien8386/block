# Pixel Block Auto (Android 8.0–14+, Java)

A screenshot-based Block Blast-style helper for the **9×9 wooden game layout** shown in `testdata/example-screen.png`, with experimental support for 8×8 and 10×10 layouts. It uses **on-device pixels only** (no AI key, no server, no network permission) and an Android Accessibility gesture to drag blocks.

## Build on GitHub

1. Extract ZIP, upload its **contents** to the root of a GitHub repo (keep `.github/workflows/android.yml`).
2. Under **Actions**, run **Build and launch-test Android APK** (or push to `main`/`master`).
3. Download artifact **PixelBlockAuto-debug-APK**, unzip the artifact and install `app-debug.apk`.

CI uses `android-actions/setup-android@v4` and supported Android SDK packages `platform-tools platforms;android-35 build-tools;35.0.0` (no nonexistent `tools` SDK package). It checks unit tests, APK structure, debug signature, package name, minimum SDK, then actually installs and launches MainActivity in an API-34 emulator. The debug APK is automatically signed by Android Gradle Plugin; never change the `.apk` extension by renaming other files.

Local build with Gradle 8.10.2 + JDK 17 + Android SDK 35: `gradle :app:assembleDebug`.

## First launch

1. **CLICK**: open Android Accessibility settings and enable **Pixel Block Auto - CLICK**.
2. **FLOAT**: grant **Display over other apps**.
3. **SCREEN / Start**: approve Android's screen capture confirmation. A foreground notification remains while capturing.
4. Return to the game and press **▶** on the small floating bar to start auto-play. **■** stops issuing new gestures immediately; **×** closes the service and releases screen capture.
5. You may drag the floating bar by its label. Place it away from the game board and tray.

The main screen has a **vertical finger offset** adjustment in units of board cells. Some games render the lifted piece *above* the finger. Start around 1.0–1.5 cells and adjust if the piece lands off target. The app stops if a move cannot be verified instead of blindly repeating it.

## How it works / limitations

- Finds a large dark square board, estimates grid count from its repeated separator lines, classifies occupied tiles from board-background color, then segments bright wooden shapes from the tray on a pixel-by-pixel basis.
- Generates all legal placements and uses a 3-piece beam search. Rewards complete lines, reachable future moves and unfragmented space.
- Uses Android MediaProjection for frames and AccessibilityService `dispatchGesture` for touch; captures at a bounded rate, and checks that the observed board/tray changes after each move.
- **Not guaranteed to work with every game theme, resolution, animation or drag mechanic.** Pixel thresholds are calibrated primarily on the provided screenshot. The screenshot is a static empty board, so live gesture accuracy **cannot** be proven from the image alone. At runtime, the game UI may add status bars, popups or scale the pieces differently. The user should supervise the first moves.
- Android requires users to approve MediaProjection; apps cannot silently bypass the confirmation prompt. Android 15+ may impose additional capture-session restrictions. The manifest targets API 34 and runs on Android 8.0+ when platform policies allow.
- The CI smoke test checks **install and activity launch**, not actual gameplay (a test emulator doesn't have the third-party game installed). Gesture placement must be tested on-device.

## Source layout

- `app/src/main/java/.../vision/PixelAnalyzer.java` — pixel detection, confidence estimates, piece normalization.
- `app/src/main/java/.../strategy/StrategyEngine.java` — legal placements, line clearing, beam search.
- `CaptureService.java` — MediaProjection foreground service + safety/verification loop.
- `BotAccessibilityService.java` — gestures only when enabled by user.
- `FloatingControls.java` — draggable control bar.
- `.github/workflows/android.yml` — build, signature checks, emulator smoke test, upload APK.
