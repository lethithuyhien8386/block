package com.pixelblock.autoplay;

import android.app.Notification;
import android.app.PendingIntent;
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
import com.pixelblock.autoplay.strategy.MoveVerifier;

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
    public static final String RESUME_AFTER_PERMISSION = "resumeAfterPermission";
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
    private volatile MoveVerifier.Prediction pendingPrediction;
    private volatile int verifiedStableFrames;
    private volatile String dryRunShownFor;
    private volatile String previousFingerprint;
    private volatile String stableFingerprint;
    private volatile int stableFrames;
    private volatile long lastFrameAt;
    private volatile long lastImageAt;
    private final Runnable captureWatchdog = new Runnable() {
        @Override public void run() {
            if (running && projection != null &&
                    SystemClock.elapsedRealtime() - lastImageAt > 3500) {
                postStatus("SCREEN thiếu ảnh ▶");
            }
            main.postDelayed(this, 1500);
        }
    };
    private volatile String lastStatus = "";
    private int deviceWidth, deviceHeight, captureWidth, captureHeight;

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onCreate() {
        super.onCreate();
        main.postDelayed(captureWatchdog, 1500);
    }

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
            ensureFloatingControls();
            MediaProjectionManager manager = (MediaProjectionManager)
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            int code = intent.getIntExtra(RESULT_CODE, 0);
            @SuppressWarnings("deprecation")
            Intent data = (Intent) intent.getParcelableExtra(RESULT_DATA);
            if (data == null || code != android.app.Activity.RESULT_OK) {
                postStatus("Thiếu SCREEN • ▶"); return;
            }
            projection = manager.getMediaProjection(code, data);
            if (projection == null) { postStatus("SCREEN lỗi • ▶"); return; }
            // A projection may be revoked on app switches or when Android captures
            // just one app. Never let an OLD callback tear down a NEW projection.
            final MediaProjection token = projection;
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    main.post(() -> {
                        if (projection != token) return;
                        pause("SCREEN đã ngắt • ▶");
                        releaseCapture();
                    });
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
            lastImageAt = SystemClock.elapsedRealtime();
            reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 3);
            reader.setOnImageAvailableListener(this::onImage, worker);
            display = projection.createVirtualDisplay("pixelblock_capture", captureWidth, captureHeight,
                    dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, worker);
            floating.active(false);
            postStatus("Nhấn ▶ ở game");
            if (intent.getBooleanExtra(RESUME_AFTER_PERMISSION, false)) {
                // Explicitly initiated by the user via ▶; analysis can run while
                // the user switches back to the game (no movement until board seen).
                resume();
            }
        } catch (Exception ex) {
            android.util.Log.e("PixelBlock", "Screen capture setup failed", ex);
            pause("SCREEN lỗi • ▶");
            releaseCapture();
            // Leave the overlay visible so ▶ can request a fresh capture token.
            // If foreground setup itself failed, Android will stop the service.
            if (floating == null) stopSelf();
        }
    }

    private void ensureFloatingControls() {
        if (floating == null) {
            floating = new FloatingControls(this, new FloatingControls.Listener() {
                @Override public void start() {
                    if (projection == null || reader == null ||
                            SystemClock.elapsedRealtime() - lastImageAt > 3500) {
                        requestFreshPermission();
                    } else resume();
                }
                @Override public void stop() { pause("Đã dừng"); }
                @Override public void close() { shutDown(); }
            });
        }
        floating.show();
        floating.active(false);
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
        Intent reopen = new Intent(this, MainActivity.class);
        reopen.setAction(MainActivity.ACTION_REAUTHORIZE);
        reopen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent reopenTap = PendingIntent.getActivity(this, 100, reopen,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = builder.setContentTitle("Pixel Block Auto")
                .setContentText("Đang chia sẻ màn hình. ■ để dừng, × để đóng.")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(reopenTap)
                .setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else startForeground(NOTIFICATION_ID, notification);
    }

    private void requestFreshPermission() {
        postStatus("Cấp lại SCREEN…");
        Intent again = new Intent(this, MainActivity.class);
        again.setAction(MainActivity.ACTION_REAUTHORIZE);
        again.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(again);
        } catch (RuntimeException ex) {
            android.util.Log.e("PixelBlock", "Cannot open screen consent", ex);
            postStatus("Mở app cấp SCREEN");
        }
    }

    private void resume() {
        if (projection == null || reader == null) {
            // This is not a vision failure: without a live MediaProjection no
            // pixels can be read. ▶ is wired to re-request system consent.
            postStatus("SCREEN hết hạn • ▶"); return;
        }
        if (!BotAccessibilityService.isAvailable()) { postStatus("Cấp quyền CLICK"); return; }
        generation++;
        running = true;
        gestureInFlight = false;
        waitingForChange = false;
        stableFingerprint = null;
        stableFrames = 0;
        pendingPrediction = null; verifiedStableFrames = 0; dryRunShownFor = null;
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
        pendingPrediction = null; verifiedStableFrames = 0; dryRunShownFor = null;
        if (floating != null) floating.active(false);
        postStatus(message);
    }

    private void onImage(ImageReader imageReader) {
        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null) return;
            lastImageAt = SystemClock.elapsedRealtime();
            if (!running || gestureInFlight || !analyzing.compareAndSet(false, true)) return;
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
        // Verify the predicted occupied grid, not a generic change in pixels.
        // The moving floating label or an animation can change the fingerprint
        // without the block actually landing in the intended cells.
        if (waitingForChange) {
            if (MoveVerifier.matches(pendingPrediction, s)) {
                verifiedStableFrames++;
                if (verifiedStableFrames >= 2) {
                    waitingForChange = false;
                    pendingPrediction = null;
                    stableFingerprint = null; stableFrames = 0;
                    postStatus("Đã kiểm tra ô đích ✓");
                } else {
                    postStatus("Xác nhận bàn cờ 1/2");
                    return;
                }
            } else {
                verifiedStableFrames = 0;
                if (now - verifiedFrom > 2600) {
                    main.post(() -> { if (running && session == generation)
                        pause("Sai vị trí / không thấy khối • chỉnh Y"); });
                } else postStatus("Đang so khớp ô đích...");
                return;
            }
        }
        if (!s.playable()) {
            stableFingerprint = null; stableFrames = 0;
            postStatus(s.status);
            return;
        }
        String fingerprint = s.fingerprint();
        if (fingerprint.equals(stableFingerprint)) stableFrames++;
        else { stableFingerprint = fingerprint; stableFrames = 1; }
        if (stableFrames < 2) { postStatus(s.status); return; }
        StrategyEngine.Move best = strategy.bestMove(s);
        // STOP can be pressed during the 1.35s deep search. Never dispatch a
        // result calculated for an old, cancelled run.
        if (!running || session != generation) return;
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
        MoveVerifier.Prediction prediction = MoveVerifier.predict(s, best);
        if (prediction == null || prediction.linesCleared != best.linesCleared) {
            postStatus("Nước đi không khớp luật - không kéo"); return;
        }
        boolean preview = getSharedPreferences("bot", MODE_PRIVATE).getBoolean("previewOnly", false);
        if (preview) {
            // Dry run: never call Accessibility while preview is enabled.
            if (!fingerprint.equals(dryRunShownFor)) {
                dryRunShownFor = fingerprint;
                android.util.Log.i("PixelBlock", "PREVIEW khối=" + (best.pieceIndex + 1)
                        + " hàng=" + (best.row + 1) + " cột=" + (best.col + 1)
                        + " sẽ xóa=" + prediction.linesCleared);
            }
            postStatus("XEM khối " + (best.pieceIndex + 1) + " H" + (best.row + 1) + " C" + (best.col + 1));
            stableFrames = 1; // a new screenshot is still analyzed when the board changes
            return;
        }
        if (!running || session != generation) return;
        gestureInFlight = true;
        stableFingerprint = null;
        previousFingerprint = fingerprint;
        pendingPrediction = prediction;
        verifiedStableFrames = 0;
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
        // An analysis callback queued before STOP must never overwrite the final
        // "Đã dừng" indicator with an old "Kéo khối"/"Đang phân tích" label.
        main.post(() -> { if (message.equals(lastStatus) && floating != null) floating.status(message); });
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
        main.removeCallbacks(captureWatchdog);
        pause("Đã đóng");
        if (floating != null) { floating.remove(); floating = null; }
        releaseCapture();
        super.onDestroy();
    }
}
