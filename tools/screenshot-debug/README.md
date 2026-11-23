# Screenshot Debug — Kiểm thử TRỰC TIẾP không cần GitHub

Đây là dashboard chạy offline, được xây lại từ ý tưởng `backend/` + `frontend/` trong ZIP mẫu của bạn. Thay vì dùng FastAPI/React/OpenCV rồi có thể trả kết quả khác APK, nó **gọi trực tiếp `PixelAnalyzer` và `StrategyEngine` Java đang có trong ứng dụng Android**. Không cần AI API, internet hoặc Android SDK. Chỉ cần **JDK 17+ và Python 3**.

## Giao diện trực quan

- **Windows:** bấm đúp `start-dashboard.bat` (nếu máy có `python` và `javac` trong PATH).
- **macOS/Linux:** chạy `./start-dashboard.sh`.
- Vào **http://127.0.0.1:8765** bằng trình duyệt Chrome/Edge, chọn ảnh PNG/JPG chụp Block Blast rồi nhấn **Phân tích ảnh**.
- Dashboard hiển thị ảnh với đường viền bàn cờ, tọa độ, các ô đã chiếm, hình dạng của từng khối, nước đi màu xanh và JSON kết quả. Không chạm vào trò chơi.
- Thay file `app/src/main/java/com/pixelblock/autoplay/strategy/StrategyEngine.java` hoặc `vision/PixelAnalyzer.java` rồi **khởi động lại dashboard** để xem ngay kết quả mới. Không cần push GitHub.

## Chạy không cần giao diện

- Linux/macOS: `./compile-and-analyze.sh ../../testdata/example-screen.png`
- Windows: `compile-and-analyze.bat ..\..\testdata\example-screen.png`

Kết quả JSON có các khóa `imageSize`, `playable`, `status`, `confidence`, `boardBox`, `board`, `pieces`, `move` với thứ tự `piece` từ **0** (tương ứng vị trí khay trái sang phải đã nhận diện), `row`/`col` từ **0**.

**Giới hạn:** Dashboard đọc ảnh tĩnh, không thể kiểm tra `MediaProjection`, quyền SCREEN hay cử chỉ kéo thật của Android. Phần này cần bật `CHỈ PHÂN TÍCH` trong APK trước rồi kiểm tra trên thiết bị.
