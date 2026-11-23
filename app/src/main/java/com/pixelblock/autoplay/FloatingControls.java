package com.pixelblock.autoplay;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Compact draggable overlay. Only three buttons and a movable status handle. */
final class FloatingControls {
    interface Listener { void start(); void stop(); void close(); }
    private final WindowManager wm;
    private final WindowManager.LayoutParams params;
    private final LinearLayout root;
    private final TextView state;
    private final TextView play;
    private final TextView pause;
    private boolean added;

    FloatingControls(Context context, Listener listener) {
        wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        float density = context.getResources().getDisplayMetrics().density;
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = Math.round(8 * density);
        params.y = Math.round(34 * density);

        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(px(density, 5), px(density, 3), px(density, 5), px(density, 3));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(232, 37, 32, 28)); bg.setCornerRadius(px(density, 12));
        bg.setStroke(px(density, 1), Color.argb(210, 234, 192, 106));
        root.setBackground(bg);

        state = item(context, "⠿ Sẵn sàng", 12, density);
        state.setMinWidth(px(density, 66));
        state.setTextColor(Color.WHITE);
        state.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private int initialX, initialY;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY();
                        initialX = params.x; initialY = params.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        android.util.DisplayMetrics screen = new android.util.DisplayMetrics();
                        wm.getDefaultDisplay().getRealMetrics(screen);
                        params.x = Math.max(0, Math.min(screen.widthPixels - root.getWidth(),
                                initialX + Math.round(e.getRawX() - downX)));
                        params.y = Math.max(0, Math.min(screen.heightPixels - root.getHeight(),
                                initialY + Math.round(e.getRawY() - downY)));
                        if (added) wm.updateViewLayout(root, params);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default: return false;
                }
            }
        });
        root.addView(state);
        play = item(context, "▶", 19, density);
        play.setTextColor(Color.rgb(137, 240, 153));
        play.setOnClickListener(v -> listener.start());
        root.addView(play);
        pause = item(context, "■", 19, density);
        pause.setTextColor(Color.rgb(255, 202, 108));
        pause.setOnClickListener(v -> listener.stop());
        root.addView(pause);
        TextView close = item(context, "×", 23, density);
        close.setTextColor(Color.rgb(255, 145, 145));
        close.setOnClickListener(v -> listener.close());
        root.addView(close);
    }
    private static int px(float density, float dp) { return Math.round(density * dp); }
    private static TextView item(Context ctx, String text, int size, float density) {
        TextView t = new TextView(ctx);
        t.setText(text); t.setTextSize(size); t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(density, 6), px(density, 6), px(density, 6), px(density, 6));
        return t;
    }
    void show() {
        if (!added) { wm.addView(root, params); added = true; }
    }
    void status(String text) {
        // Keep the bar narrow so it is less likely to obscure the playable board.
        if (text.length() > 18) text = text.substring(0, 18);
        state.setText("⠿ " + text);
    }
    void active(boolean active) {
        play.setAlpha(active ? .35f : 1f);
        pause.setAlpha(active ? 1f : .35f);
    }
    void remove() {
        if (added) { wm.removeViewImmediate(root); added = false; }
    }
}
