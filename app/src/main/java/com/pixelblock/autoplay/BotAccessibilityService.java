package com.pixelblock.autoplay;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import java.util.concurrent.atomic.AtomicBoolean;

/** Accessibility is used ONLY to dispatch explicitly activated gameplay gestures. */
public final class BotAccessibilityService extends AccessibilityService {
    public interface GestureCallback { void completed(boolean success); }
    private static volatile BotAccessibilityService instance;
    private final AtomicBoolean allowGestures = new AtomicBoolean(false);
    private final Handler main = new Handler(Looper.getMainLooper());

    public static boolean isAvailable() { return instance != null; }
    public static void setActive(boolean active) {
        BotAccessibilityService service = instance;
        if (service != null) service.allowGestures.set(active);
    }
    public static boolean drag(float startX, float startY, float endX, float endY,
                               GestureCallback callback) {
        BotAccessibilityService service = instance;
        if (service == null || !service.allowGestures.get()) return false;
        service.main.post(() -> {
            if (!service.allowGestures.get() || instance != service) { callback.completed(false); return; }
            Path path = new Path();
            path.moveTo(startX, startY);
            // Move smoothly; some games ignore instantaneous teleport-style gestures.
            for (int i = 1; i <= 12; i++) {
                float t = i / 12f;
                float ease = t * t * (3 - 2 * t);
                path.lineTo(startX + (endX - startX) * ease, startY + (endY - startY) * ease);
            }
            GestureDescription desc = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 240)).build();
            boolean accepted = service.dispatchGesture(desc, new GestureDescription.GestureResultCallback() {
                @Override public void onCompleted(GestureDescription gestureDescription) {
                    callback.completed(service.allowGestures.get());
                }
                @Override public void onCancelled(GestureDescription gestureDescription) {
                    callback.completed(false);
                }
            }, service.main);
            if (!accepted) callback.completed(false);
        });
        return true;
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected(); instance = this; allowGestures.set(false);
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { allowGestures.set(false); }
    @Override public void onDestroy() {
        allowGestures.set(false);
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
