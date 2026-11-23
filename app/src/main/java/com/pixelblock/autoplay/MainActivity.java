package com.pixelblock.autoplay;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.media.projection.MediaProjectionManager;
import android.media.projection.MediaProjectionConfig;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 101;
    /** Used only after a user explicitly taps ▶ on the floating overlay / notification. */
    public static final String ACTION_REAUTHORIZE = "com.pixelblock.autoplay.REAUTHORIZE";
    private boolean resumeAfterPermission;
    private TextView accessibilityInfo, overlayInfo, screenInfo, offsetInfo;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(20));
        root.setBackgroundColor(Color.rgb(245, 240, 227));
        ScrollView scroller = new ScrollView(this);
        scroller.addView(root);
        setContentView(scroller);

        TextView title = text("Pixel Block Auto", 26, Color.rgb(65, 44, 27));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);
        root.addView(text("Điều khiển Block Blast bằng ảnh chụp màn hình, chạy hoàn toàn trên máy.", 14, Color.DKGRAY));
        addSpace(root, 15);

        accessibilityInfo = text("", 15, Color.DKGRAY);
        root.addView(button("1. CLICK — Cấp quyền Accessibility", () -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }));
        root.addView(accessibilityInfo);
        addSpace(root, 12);

        overlayInfo = text("", 15, Color.DKGRAY);
        root.addView(button("2. FLOAT — Hiển thị trên ứng dụng khác", () -> {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }));
        root.addView(overlayInfo);
        addSpace(root, 12);

        screenInfo = text("", 15, Color.DKGRAY);
        root.addView(button("3. SCREEN — Quyền chụp màn hình", this::requestScreen));
        root.addView(screenInfo);
        addSpace(root, 18);

        root.addView(text("Điều chỉnh điểm thả khi game nâng khối lên khỏi ngón tay:", 15, Color.DKGRAY));
        offsetInfo = text("", 15, Color.rgb(70, 51, 33));
        root.addView(offsetInfo);
        SeekBar offsetSlider = new SeekBar(this);
        offsetSlider.setMax(50); // -1.0 to +4.0 board-cell units
        int saved = getSharedPreferences("bot", MODE_PRIVATE).getInt("offsetProgress", 25);
        offsetSlider.setProgress(saved);
        showOffset(saved);
        offsetSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                showOffset(progress);
                getSharedPreferences("bot", MODE_PRIVATE).edit().putInt("offsetProgress", progress).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        root.addView(offsetSlider);
        addSpace(root, 16);

        Button start = button("START — Mở thanh điều khiển nổi", this::requestScreen);
        start.setTextSize(17);
        root.addView(start);
        root.addView(button("STOP — Dừng mọi nước kéo mới", () -> {
            startService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_PAUSE));
        }));
        root.addView(button("ĐÓNG thanh nổi và kết thúc chụp", () -> {
            startService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_CLOSE));
        }));
        addSpace(root, 12);
        root.addView(text("Sau khi mở thanh nổi, hãy chuyển sang game rồi nhấn ▶ trên thanh nổi. "
                + "Kéo thanh bằng vùng chữ. ■ dừng, × đóng. "
                + "Nếu hiện SCREEN đã ngắt/SCREEN thiếu ảnh, nhấn ▶ để cấp SCREEN lại. "
                + "Nếu khối rơi lệch, chỉnh Bù kéo Y rồi thử lại.", 13, Color.DKGRAY));

        if (ACTION_REAUTHORIZE.equals(getIntent().getAction())) {
            root.post(() -> beginFreshScreenPermission(true));
        }

        if (!ACTION_REAUTHORIZE.equals(getIntent().getAction())
                && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 201);
        }
    }

    private void showOffset(int progress) {
        float offset = (progress - 10) / 10f;
        offsetInfo.setText(String.format(java.util.Locale.US, "Bù kéo Y: %+.1f ô (mặc định +1.5)", offset));
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (ACTION_REAUTHORIZE.equals(intent.getAction())) {
            new android.os.Handler(getMainLooper()).post(() -> beginFreshScreenPermission(true));
        }
    }

    private void beginFreshScreenPermission(boolean autoPlay) {
        resumeAfterPermission = autoPlay;
        requestScreen();
    }

    private void requestScreen() {
        if (!BotAccessibilityService.isAvailable()) {
            accessibilityInfo.setText("⚠ Cần bật CLICK rồi quay lại.");
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            overlayInfo.setText("⚠ Cần bật FLOAT rồi quay lại.");
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        // Android 14+ defaults to sharing ONE app, which may stop producing game
        // frames as soon as the user changes apps. Request the full display,
        // still with the Android system's explicit consent dialog.
        Intent consent = Build.VERSION.SDK_INT >= 34
                ? mgr.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                : mgr.createScreenCaptureIntent();
        startActivityForResult(consent, REQUEST_CAPTURE);
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CAPTURE && resultCode == RESULT_OK && data != null) {
            Intent service = new Intent(this, CaptureService.class);
            service.setAction(CaptureService.ACTION_OPEN);
            service.putExtra(CaptureService.RESULT_CODE, resultCode);
            service.putExtra(CaptureService.RESULT_DATA, data);
            service.putExtra(CaptureService.RESUME_AFTER_PERMISSION, resumeAfterPermission);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
            screenInfo.setText("✓ SCREEN đã xác nhận. Hãy chọn 'Toàn bộ màn hình' và quay lại game.");
            if (resumeAfterPermission) moveTaskToBack(true);
            resumeAfterPermission = false;
        } else if (requestCode == REQUEST_CAPTURE) {
            screenInfo.setText("Chưa có quyền SCREEN. Bấm Start và chọn chia sẻ toàn bộ màn hình.");
            resumeAfterPermission = false;
        }
    }
    @Override protected void onResume() {
        super.onResume();
        if (accessibilityInfo == null) return;
        accessibilityInfo.setText(BotAccessibilityService.isAvailable() ? "✓ CLICK đang bật" : "○ CLICK chưa bật");
        overlayInfo.setText(Settings.canDrawOverlays(this) ? "✓ FLOAT đã cho phép" : "○ FLOAT chưa cho phép");
        if (screenInfo.getText().length() == 0) screenInfo.setText("○ SCREEN: cần xác nhận từ hệ thống khi nhấn Start");
    }
    private Button button(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(115, 73, 44)); bg.setCornerRadius(dp(13));
        b.setBackground(bg);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(50));
        p.bottomMargin = dp(7);
        b.setLayoutParams(p);
        b.setOnClickListener(v -> action.run());
        return b;
    }
    private TextView text(String content, int sizeSp, int color) {
        TextView t = new TextView(this);
        t.setText(content); t.setTextSize(sizeSp); t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL); t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }
    private void addSpace(LinearLayout root, int dp) {
        View v = new View(this); root.addView(v, new LinearLayout.LayoutParams(1, dp(dp)));
    }
    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
}
