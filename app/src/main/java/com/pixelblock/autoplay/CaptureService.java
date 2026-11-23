package com.pixelblock.autoplay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import com.pixelblock.autoplay.vision.PixelAnalyzer;
import com.pixelblock.autoplay.strategy.StrategyEngine;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/** User-approved MediaProjection -> pixel classifier -> strategy -> Accessibility gesture. */
public final class CaptureService extends Service {
    public static final String ACTION_OPEN = "com.pixelblock.autoplay.OPEN";
    public static final String ACTION_RESUME = "com.pixelblock.autoplay.RESUME";
    public static final String ACTION_PAUSE = "com.pixelblock.autoplay.PAUSE";
    public static final String ACTION_CLOSE = "com.pixelblock.autoplay.CLOSE";
    public static final String RESULT_CODE = "projectionResultCode";
    public static final String RESULT_DATA = "projectionResultIntent";
    private static final String CHANNEL_ID = "pixelblock_capture";
    private static final int NOTIFICATION_ID = 22;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final PixelAnalyzer analyzer = new PixelAnalyzer();
    private final StrategyEngine strategy = new StrategyEngine();
    private final AtomicBoolean analyzing = new AtomicBoolean(false);
    private HandlerThread workerThread;
    private Handler worker;
    private MediaProjection projection;
    private ImageReader reader;
    private VirtualDisplay display;
    private FloatingControls floating;
    private volatile boolean running, gestureInFlight, waitingForChange;
    private volatile long generation;
    private volatile long verifiedFrom;
    private volatile String previousFingerprint;
    private volatile String stableFingerprint;
    private volatile int stableFrames;
    private volatile long lastFrameAt;
    private volatile String lastStatus = "";
    private int deviceWidth, deviceHeight, captureWidth, captureHeight;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_CLOSE.equals(action)) {
            shutDown();
        } else if (ACTION_PAUSE.equals(action)) {
            pause("Đã dừng");
        } else if (ACTION_RESUME.equals(action)) {
            resume();
        } else if (ACTION_OPEN.equals(action)) {
            openProjection(intent);
        }
        return START_NOT_STICKY; // never restart screen capture without fresh user consent
    }

    private void openProjection(Intent intent) {
        pause("Sẵn sàng");
        releaseCapture();
        try {
            createForegroundNotification();
            MediaProjectionManager manager = (MediaProjectionManager)
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            int code = intent.getIntExtra(RESULT_CODE, 0);
            @SuppressWarnings("deprecation")
            Intent data = (Intent) intent.getParcelableExtra(RESULT_DATA);
            if (data == null || code != android.app.Activity.RESULT_OK) {
                postStatus("Thiếu quyền SCREEN"); stopSelf(); return;
            }
            projection = manager.getMediaProjection(code, data);
            if (projection == null) { postStatus("Không khởi tạo SCREEN"); stopSelf(); return; }
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    main.post(() -> { pause("SCREEN đã kết thúc"); releaseCapture(); });
                }
            }, main);
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            @SuppressWarnings("deprecation") android.view.Display defaultDisplay = wm.getDefaultDisplay();
            defaultDisplay.getRealMetrics(dm);
            deviceWidth = dm.widthPixels; deviceHeight = dm.heightPixels;
            captureWidth = Math.min(720, deviceWidth);
            captureHeight = Math.max(1, Math.round(deviceHeight * (captureWidth / (float) deviceWidth)));
            workerThread = new HandlerThread("pixel-analysis"); workerThread.start();
            worker = new Handler(workerThread.getLooper());
            reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 3);
            reader.setOnImageAvailableListener(this::onImage, worker);
            display = projection.createVirtualDisplay("pixelblock_capture", captureWidth, captureHeight,
                    dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, worker);
            if (floating == null) {
                floating = new FloatingControls(this, new FloatingControls.Listener() {
                    @Override public void start() { resume(); }
                    @Override public void stop() { pause("Đã dừng"); }
                    @Override public void close() { shutDown(); }
                });
            }
            floating.show();
            floating.active(false);
            postStatus("Nhấn ▶ ở game");
        } catch (Exception ex) {
            android.util.Log.e("PixelBlock", "Screen capture setup failed", ex);
            postStatus("Lỗi SCREEN: xem Logcat");
            pause("Không chụp được");
            releaseCapture();
            stopSelf();
        }
    }

    private void createForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Pixel Block - chụp màn hình", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Hiển thị khi app đang ghi nhận màn hình để nhận diện khối");
            nm.createNotificationChannel(channel);
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        Notification notification = builder.setContentTitle("Pixel Block Auto")
                .setContentText("Đang chia sẻ màn hình. ■ để dừng, × để đóng.")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else startForeground(NOTIFICATION_ID, notification);
    }

    private void resume() {
        if (projection == null || reader == null) { postStatus("Mở SCREEN lại"); return; }
        if (!BotAccessibilityService.isAvailable()) { postStatus("Cấp quyền CLICK"); return; }
        generation++;
        running = true;
        gestureInFlight = false;
        waitingForChange = false;
        stableFingerprint = null;
        stableFrames = 0;
        BotAccessibilityService.setActive(true);
        if (floating != null) floating.active(true);
        postStatus("Đang phân tích...");
    }

    private void pause(String message) {
        // This flag is checked again immediately before each dispatchGesture.
        generation++;
        running = false;
        BotAccessibilityService.setActive(false);
        gestureInFlight = false;
        waitingForChange = false;
        stableFingerprint = null;
        stableFrames = 0;
        if (floating != null) floating.active(false);
        postStatus(message);
    }

    private void onImage(ImageReader imageReader) {
        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null || !running || gestureInFlight || !analyzing.compareAndSet(false, true)) return;
            long now = SystemClock.elapsedRealtime();
            if (now - lastFrameAt < 180) return;
            lastFrameAt = now;
            final long session = generation;
            Image.Plane plane = image.getPlanes()[0];
            int rowStride = plane.getRowStride(), pixelStride = plane.getPixelStride();
            ByteBuffer buffer = plane.getBuffer();
            byte[] scanline = new byte[rowStride];
            int[] pixels = new int[captureWidth * captureHeight];
            for (int y = 0; y < captureHeight; y++) {
                int rowStart = y * rowStride;
                buffer.position(rowStart);
                // Some devices omit the unused padding bytes after the final row.
                int bytesAvailable = Math.min(rowStride, buffer.limit() - rowStart);
                if (bytesAvailable < captureWidth * pixelStride) throw new IllegalStateException("Short RGBA row");
                buffer.get(scanline, 0, bytesAvailable);
                int dst = y * captureWidth;
                for (int x = 0; x < captureWidth; x++) {
                    int off = x * pixelStride;
                    // RGBA_8888 plane bytes are R, G, B, A (independent of ByteBuffer endianness).
                    int red = scanline[off] & 255, green = scanline[off + 1] & 255;
                    int blue = scanline[off + 2] & 255;
                    pixels[dst + x] = 0xff000000 | (red << 16) | (green << 8) | blue;
                }
            }
            if (session != generation || !running) return;
            PixelAnalyzer.Snapshot snapshot = analyzer.analyze(pixels, captureWidth, captureHeight);
            processSnapshot(snapshot, session, now);
        } catch (Exception ex) {
            android.util.Log.e("PixelBlock", "Frame analysis failed", ex);
            postStatus("Lỗi ảnh - thử lại");
        } finally {
            if (image != null) image.close();
            analyzing.set(false);
        }
    }

    private void processSnapshot(PixelAnalyzer.Snapshot s, long session, long now) {
        if (!running || generation != session) return;
        if (!s.playable()) {
            stableFingerprint = null; stableFrames = 0;
            if (waitingForChange && now - verifiedFrom > 1500) {
                main.post(() -> { if (running && session == generation) pause("Không xác nhận được kéo"); });
            } else if (!waitingForChange) postStatus(s.status);
            return;
        }
        String fingerprint = s.fingerprint();
        if (waitingForChange) {
            if (!fingerprint.equals(previousFingerprint)) {
                waitingForChange = false;
                stableFingerprint = null; stableFrames = 0;
                postStatus("Đã đặt khối ✓");
            } else if (now - verifiedFrom > 1500) {
                main.post(() -> { if (running && session == generation) pause("Kéo chưa đúng - chỉnh Y"); });
                return;
            } else return;
        }
        if (fingerprint.equals(stableFingerprint)) stableFrames++;
        else { stableFingerprint = fingerprint; stableFrames = 1; }
        if (stableFrames < 2) { postStatus(s.status); return; }
        StrategyEngine.Move best = strategy.bestMove(s);
        if (best == null) { postStatus("Không có nước hợp lệ"); return; }
        // Touch coordinates cannot be reused if the device rotated since SCREEN approval.
        DisplayMetrics live = new DisplayMetrics();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(live);
        if (Math.abs(live.widthPixels - deviceWidth) > 4 || Math.abs(live.heightPixels - deviceHeight) > 4) {
            main.post(() -> { if (session == generation) pause("Đã xoay màn - mở lại"); });
            return;
        }
        PixelAnalyzer.Piece piece = s.pieces.get(best.pieceIndex);
        float targetX = s.board.cx(best.col + piece.touchCol);
        float targetY = s.board.cy(best.row + piece.touchRow);
        float cellsOffset = (getSharedPreferences("bot", MODE_PRIVATE).getInt("offsetProgress", 25) - 10) / 10f;
        targetY += (float) s.board.pitch() * cellsOffset;
        float startX = piece.touchX * deviceWidth / (float) captureWidth;
        float startY = piece.touchY * deviceHeight / (float) captureHeight;
        float endX = targetX * deviceWidth / (float) captureWidth;
        float endY = targetY * deviceHeight / (float) captureHeight;
        endX = Math.max(3, Math.min(deviceWidth - 4, endX));
        endY = Math.max(3, Math.min(deviceHeight - 4, endY));
        if (best.confidence < 0.55) { postStatus("Nhận diện chưa chắc"); return; }
        gestureInFlight = true;
        stableFingerprint = null;
        previousFingerprint = fingerprint;
        postStatus("Kéo khối " + (best.pieceIndex + 1) + " → " + (best.row + 1) + "," + (best.col + 1));
        boolean accepted = BotAccessibilityService.drag(startX, startY, endX, endY,
                success -> main.post(() -> {
                    if (session != generation || !running) return;
                    gestureInFlight = false;
                    if (!success) { pause("Thao tác bị hủy"); return; }
                    waitingForChange = true;
                    verifiedFrom = SystemClock.elapsedRealtime();
                    postStatus("Đang xác nhận...");
                }));
        if (!accepted) {
            gestureInFlight = false;
            main.post(() -> { if (session == generation) pause("CLICK chưa sẵn sàng"); });
        }
    }

    private void postStatus(String message) {
        if (message.equals(lastStatus)) return;
        lastStatus = message;
        main.post(() -> { if (floating != null) floating.status(message); });
    }

    private void releaseCapture() {
        ImageReader oldReader = reader; reader = null;
        VirtualDisplay oldDisplay = display; display = null;
        MediaProjection oldProjection = projection; projection = null;
        if (oldReader != null) { oldReader.setOnImageAvailableListener(null, null); oldReader.close(); }
        if (oldDisplay != null) oldDisplay.release();
        if (oldProjection != null) {
            try { oldProjection.stop(); } catch (Exception ignored) { }
        }
        if (workerThread != null) {
            workerThread.quitSafely(); workerThread = null; worker = null;
        }
    }
    private void shutDown() {
        pause("Đã đóng");
        if (floating != null) { floating.remove(); floating = null; }
        releaseCapture();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
    @Override public void onDestroy() {
        pause("Đã đóng");
        if (floating != null) { floating.remove(); floating = null; }
        releaseCapture();
        super.onDestroy();
    }
}
