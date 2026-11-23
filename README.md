# Pixel Block Auto v6 — Android + Offline Screenshot Dashboard

**Version:** `1.3.0` (`versionCode 6`) — project Android Java, bản sửa từ v5, kết hợp các ý tưởng trong `archive (1).zip` mà bạn cung cấp.

## Những điểm mới của bản v6

- **Đã tích hợp:** cách đánh giá độ linh hoạt và vùng bị phân mảnh của solver mẫu (`backend/core/solver.py`) vào `StrategyEngine.java`. Giữ bàn cờ phát hiện linh động **8×8 / 9×9 / 10×10**, giới hạn tìm kiếm ba khối và không tái sử dụng một khối nhiều lần. Có thể đặt khối ở vị trí khay bất kỳ trước, không cố định theo thứ tự.
- **Bảo vệ khi kéo:** `MoveVerifier.java` dự đoán chính xác các ô sau khi thả (bao gồm xóa hàng/cột đồng thời); yêu cầu kết quả trên màn hình khớp dự đoán trong **hai khung hình**. Nếu không khớp, dừng thay vì tự động nhấn tiếp. Không còn hiểu nhầm thay đổi chữ trên thanh FLOAT là bằng chứng kéo thành công.
- **Stop thực sự chặn thao tác mới:** kiểm tra lại trạng thái session **sau khi thuật toán tính xong** và ngay trước Accessibility. Những kết quả cũ bị bỏ khi đã nhấn Stop.
- **Chế độ kiểm tra không kéo:** trong ứng dụng chính có ô **CHỈ PHÂN TÍCH (không kéo)**. Khi bật, thanh FLOAT hiển thị `XEM khối n Hx Cy`; chi tiết qua `adb logcat -s PixelBlock:I`. Mặc định **tắt** để nút Play vẫn có thể tự kéo theo yêu cầu của bạn.
- **Dashboard để test trước GitHub:** `tools/screenshot-debug/start-dashboard.bat` (Windows) / `.sh` (macOS/Linux). Chỉ cần **JDK 17+ và Python 3**, mở `http://127.0.0.1:8765`, tải ảnh game lên. Giao diện vẽ khung bàn, vị trí khối, dự báo nước đi; sử dụng **đúng Java core của APK**, không cần AI API, không cần Android SDK, không cần Gradle và không truy cập internet. Có endpoint local `POST /analyze` tương tự file FastAPI/React bạn gửi.
- **Đã kiểm tra:** 20 bài kiểm tra Java độc lập qua bộ chạy JUnit-compatible cục bộ, gồm 3 ảnh chụp thật (0, 5, 26 ô có khối), kích thước nhiều màn hình, khối hình khác nhau, nước đi nhiều bước và xác nhận kết quả sau kéo. Kết quả 20/20 đạt. Đã gọi thử `POST /analyze` local và nhận dữ liệu JSON hợp lệ từ ảnh thật.

### Chạy thử nhanh không cần build APK

1. Cài **JDK 17+** và **Python 3** trên Windows, đảm bảo gọi được `javac` và `python` trong Terminal.
2. Giải nén toàn bộ ZIP. Bấm `tools/screenshot-debug/start-dashboard.bat`. Nếu Windows không nhận `python`, chạy `py dashboard.py` trong thư mục đó.
3. Mở `http://127.0.0.1:8765` và chọn một ảnh screenshot. Nhấn **Phân tích ảnh** để xem: (a) bàn 8/9/10 ô, (b) các ô chiếm, (c) từng khối nhận diện, (d) gợi ý đặt, và (e) hình vẽ chồng lên ảnh.
4. Sửa Java core, đóng terminal dashboard, khởi động lại để thử mã mới. Không cần GitHub.
5. Chỉ khi kết quả đúng mới build APK bằng workflow ở dưới và kiểm tra chế độ **CHỈ PHÂN TÍCH** trên điện thoại, sau đó tắt và cho kéo.

**Các giới hạn cần biết:** File ZIP đính kèm ban đầu là bộ mẫu desktop, không phải code Android có thể cài trực tiếp. `vision.py` của mẫu dùng ROI cố định và ngưỡng màu chung, `solver.py` cố định BOARD_N=8 và không theo dõi những khối đã dùng; `agent.py` chỉ click, không kéo. Vì vậy v6 **đưa các ý tưởng phù hợp sang Android**, không sao chép nguyên code Python. Không thể bảo đảm nước đi tối ưu cho các khối chưa xuất hiện; giới hạn tính toán cho phép ứng dụng đáp ứng trên máy yếu. **Chưa build/cài/kiểm chứng kéo thực tế trong môi trường hiện tại vì không có Android SDK**. Android 14+ vẫn cần người dùng chấp thuận SCREEN qua hộp thoại hệ thống.

---

# Ghi chú bản trước: Pixel Block Auto v1.2.0 (source v5)

**Lịch sử v1.2.0 / v5 (23 November 2026)**

- New `StrategyEngine`: bounded exhaustive lookahead of up to **three visible tray pieces in any order**. It first tries to place the highest possible number of currently visible pieces, then prefers completing rows/columns and avoiding newly sealed pockets, and uses 3×3, 2×3 and horizontal openings to estimate flexibility for unseen next pieces. A piece on the right can be the first choice. Importantly, the old v4 beam-width-32 bottleneck has been removed.
- Bounded work: around **1.35 s** / **1.8 million legal candidate visits** maximum per decision, so it may stop early on very open boards; this is NOT a mathematical guarantee of a globally optimal move. Real performance varies by device.
- Includes `tools/BlockBlast_Simulator_v5.html`: open locally in Chrome/Edge to edit the board and three block shapes, analyze, step, auto-run or stop WITHOUT pushing to GitHub. The simulator runs strategy on a background web worker, but does NOT test Android screen capture or gestures.
- Regression tests now include a board where the small second-tray piece must be played first to clear a row and allow the 3×3 first-tray piece. **15 pure-Java tests passed locally**, without the Android SDK. APK build/installation and live game gestures remain unverified here.
- Screen recognition logic and capture/accessibility from v4 are unchanged; if game screenshots are not recognized, improving strategy alone cannot fix capture/detection.

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
- Searches up to three visible pieces **in any order** with bounded multi-step lookahead (not beam width 32). It optimizes, in sequence: (1) how many visible pieces fit, (2) rows and columns cleared, (3) avoidance of permanently separated empty regions, and (4) openness for future unknown pieces. It does not blindly reject a temporarily split region if later clearing a row or column repairs it. The fallback always returns a legal move if one exists.
- On the **new screenshot that caused v3 to display “Chưa thấy bàn cờ”**, the updated detector recognizes a **9×9 board with 26 occupied cells** and **three tray pieces with 4, 3 and 3 tiles**. The strategy can choose a legal first move (middle vertical piece, row 7, column 9, counted from 1). No fixed left-to-right tray order is imposed.
- **15 local Java tests** passed, including regression tests using the exact real image from this report (compressed RGB resource; **no** `java.awt`, `javax.imageio` or Android dependencies in unit tests). The image was additionally tested at widths of 320/360/448/600/720 px. CI runs the same tests before building.
- Uses Android MediaProjection for frames and AccessibilityService `dispatchGesture` for touch; captures at a bounded rate, and checks that the observed board/tray changes after each move.
- **Not guaranteed to work with every game theme, resolution, animation or drag mechanic.** Pixel thresholds are calibrated primarily on the provided screenshots. The new screenshot tests recognition and legal move planning, but live gesture accuracy **cannot** be proven from images alone. At runtime, the game UI may add status bars, popups or scale the pieces differently. The user should supervise the first moves.
- Android requires users to approve MediaProjection; apps cannot silently bypass the confirmation prompt. If Android revokes screen projection for privacy reasons, the app must ask again. Android 15+ may impose additional capture-session restrictions. The manifest targets API 34 and runs on Android 8.0+ when platform policies allow.
- The CI smoke test checks **install and activity launch**, not actual gameplay (a test emulator doesn't have the third-party game installed). Gesture placement must be tested on-device.

## Source layout

- `app/src/main/java/.../vision/PixelAnalyzer.java` — pixel detection, confidence estimates, piece normalization.
- `app/src/main/java/.../strategy/StrategyEngine.java` — bounded multi-block combinatorial search and survival scoring.
- `CaptureService.java` — MediaProjection foreground service + safety/verification loop.
- `BotAccessibilityService.java` — gestures only when enabled by user.
- `FloatingControls.java` — draggable control bar.
- `.github/workflows/android.yml` — build, signature checks, emulator smoke test, upload APK.

## Troubleshooting v5

- **“Chưa thấy bàn cờ” despite a visible game:** v4 includes two separate fixes for dark lines at the image edge and light occupied grid cells. Confirm you installed version **1.3.0** instead of 1.1.1; send a new screenshot if the theme/position differs greatly from the tested screenshot.
- **Nothing happens; SCREEN has expired / no images:** tap the overlay **▶**, approve the Android screen-sharing dialog, return to the game. For Android 14+ full-display capture is requested via `MediaProjectionConfig.createConfigForDefaultDisplay()` (API 34). Verify that any device-specific screen-recording restrictions allow capture; protected game surfaces may show black.
- **Seeing “Nhấn ▶ ở game”:** screen sharing exists but analysis is paused. Switch to the game, then press ▶.
- **Recognized game but not placing:** confirm the Accessibility service is enabled. If a gesture is confirmed but blocks do not land, adjust the Y offset slider in the main app and try one supervised move.
- **App cannot replace older APK:** debug signing keys generated on separate GitHub runners may differ; if Android reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, back up settings and uninstall the older debug build before installing this one.
- **For Android errors:** inspect `adb logcat -s PixelBlock:D AndroidRuntime:E`, and capture an image of the screen plus floating bar status. CI installs and launches only the main UI; it cannot verify live gameplay automatically.
