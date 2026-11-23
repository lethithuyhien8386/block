# Pixel Block Auto (Android 8.0–14+, Java)

> **v1.1.1 / source fix v4 (2026-11-23):** Fixes **“Chưa thấy bàn cờ”** on highly occupied boards: grid-separator contrast is now unsigned because occupied light tiles invert its brightness, and a vertical frame-edge fallback rejects a stray dark stripe at x=0. Tray search is positioned relative to the board rather than screen height, so tall/letterboxed displays do not skip the pieces. This patch was regression-tested against the user's exact failing screenshot. SCREEN permissions from v3 and gesture callback fix from v2 are retained. The complete Android APK must still be built in GitHub Actions.

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
5. If the floating bar reads **SCREEN đã ngắt • ▶** or **SCREEN thiếu ảnh ▶**, tap **▶**: Android will ask for fresh consent, then the app automatically resumes upon acceptance. You can also tap the foreground notification. On Android 14+, the app requests the whole display instead of one-app sharing, which otherwise may stop showing the game after app switching.
6. You may drag the floating bar by its label. Place it away from the game board and tray.

The main screen has a **vertical finger offset** adjustment in units of board cells. Some games render the lifted piece *above* the finger. Start around 1.0–1.5 cells and adjust if the piece lands off target. The app stops if a move cannot be verified instead of blindly repeating it.

## How it works / limitations

- Finds a large dark square board, estimates grid count from its repeated separator lines, classifies occupied tiles from board-background color, then segments bright wooden shapes from the tray on a pixel-by-pixel basis.
- Tries all three available pieces **in any order**, using a bottom-up beam search (32 candidate board states per depth). A placement is rejected if it **creates a new disconnected empty region** without completing a clearing row/column. Completing a row/column is heavily rewarded, as is keeping accessible space and avoiding one-cell dead ends.
- On the **new screenshot that caused v3 to display “Chưa thấy bàn cờ”**, the updated detector recognizes a **9×9 board with 26 occupied cells** and **three tray pieces with 4, 3 and 3 tiles**. The strategy can choose a legal first move (middle vertical piece, row 7, column 9, counted from 1). No fixed left-to-right tray order is imposed.
- **13 local Java tests** passed, including regression tests using the exact real image from this report (compressed RGB resource; **no** `java.awt`, `javax.imageio` or Android dependencies in unit tests). The image was additionally tested at widths of 320/360/448/600/720 px. CI runs the same tests before building.
- Uses Android MediaProjection for frames and AccessibilityService `dispatchGesture` for touch; captures at a bounded rate, and checks that the observed board/tray changes after each move.
- **Not guaranteed to work with every game theme, resolution, animation or drag mechanic.** Pixel thresholds are calibrated primarily on the provided screenshots. The new screenshot tests recognition and legal move planning, but live gesture accuracy **cannot** be proven from images alone. At runtime, the game UI may add status bars, popups or scale the pieces differently. The user should supervise the first moves.
- Android requires users to approve MediaProjection; apps cannot silently bypass the confirmation prompt. If Android revokes screen projection for privacy reasons, the app must ask again. Android 15+ may impose additional capture-session restrictions. The manifest targets API 34 and runs on Android 8.0+ when platform policies allow.
- The CI smoke test checks **install and activity launch**, not actual gameplay (a test emulator doesn't have the third-party game installed). Gesture placement must be tested on-device.

## Source layout

- `app/src/main/java/.../vision/PixelAnalyzer.java` — pixel detection, confidence estimates, piece normalization.
- `app/src/main/java/.../strategy/StrategyEngine.java` — legal placements, line clearing, beam search.
- `CaptureService.java` — MediaProjection foreground service + safety/verification loop.
- `BotAccessibilityService.java` — gestures only when enabled by user.
- `FloatingControls.java` — draggable control bar.
- `.github/workflows/android.yml` — build, signature checks, emulator smoke test, upload APK.

## Troubleshooting v4

- **“Chưa thấy bàn cờ” despite a visible game:** v4 includes two separate fixes for dark lines at the image edge and light occupied grid cells. Confirm you installed version **1.1.1** instead of 1.1.0; send a new screenshot if the theme/position differs greatly from the tested screenshot.
- **Nothing happens; SCREEN has expired / no images:** tap the overlay **▶**, approve the Android screen-sharing dialog, return to the game. For Android 14+ full-display capture is requested via `MediaProjectionConfig.createConfigForDefaultDisplay()` (API 34). Verify that any device-specific screen-recording restrictions allow capture; protected game surfaces may show black.
- **Seeing “Nhấn ▶ ở game”:** screen sharing exists but analysis is paused. Switch to the game, then press ▶.
- **Recognized game but not placing:** confirm the Accessibility service is enabled. If a gesture is confirmed but blocks do not land, adjust the Y offset slider in the main app and try one supervised move.
- **App cannot replace older APK:** debug signing keys generated on separate GitHub runners may differ; if Android reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, back up settings and uninstall the older debug build before installing this one.
- **For Android errors:** inspect `adb logcat -s PixelBlock:D AndroidRuntime:E`, and capture an image of the screen plus floating bar status. CI installs and launches only the main UI; it cannot verify live gameplay automatically.
