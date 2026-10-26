package com.diablo.scanner;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.util.DisplayMetrics;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FloatingBubbleService extends Service {

    private static final String PREFS = "diablo_scanner";
    private static final String AI_PREFS = "diablo_scanner";
    private static final String TRADE_PREFS = "diablo_trade_records";
    private static final String TRADE_HISTORY = "trades";

    private static final long DEFAULT_TRADE_DURATION_MS = 60000L;
    private static final long AI_FRESHNESS_MS = 180000L;

    private WindowManager windowManager;
    private WindowManager.LayoutParams bubbleParams;
    private WindowManager.LayoutParams panelParams;

    private View bubble;
    private View panel;

    private TextView activityView;
    private TextView signalView;
    private TextView detailsView;
    private TextView tradeStatusView;
    private TextView statsView;
    private TextView modeButton;
    private TextView dismissTargetView;
    private boolean dismissTargetVisible = false;

    private boolean expanded = false;
    private boolean tradeActive = false;
    private boolean awaitingResult = false;
    private int scanRayFrame = 0;

    private long tradeStartMs = 0L;
    private long tradeDurationMs = DEFAULT_TRADE_DURATION_MS;

    private String tradeDirection = "WAIT";

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private static final long AUTO_SCAN_INTERVAL_MS = 10000L;

    private final Runnable autoScanRunnable = new Runnable() {
        @Override
        public void run() {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (!prefs.getBoolean("auto_scan_enabled", false)) return;

            if (prefs.getBoolean("screen_capture_active", false)) {
                String status = prefs.getString("scan_status", "WAIT");
                if (!"SCANNING".equals(status) && !"AI_PROCESSING".equals(status)) {
                    requestAutomaticScan();
                }
            }

            handler.postDelayed(this, AUTO_SCAN_INTERVAL_MS);
        }
    };

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (expanded && panel != null) {
                refreshLatestResult();
                updateTradeStatus();
            }

            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        // Promote the service immediately to help Android keep the
        // floating bubble alive when the user switches apps.
        createForegroundNotification();

        windowManager = (WindowManager)
                getSystemService(WINDOW_SERVICE);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean("bubble_dismissed_by_user", false)) {
            showBubble();
        }

        handler.postDelayed(refreshRunnable, 1000L);
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("auto_scan_enabled", false)) {
            handler.postDelayed(autoScanRunnable, AUTO_SCAN_INTERVAL_MS);
        }
    }

    private void createForegroundNotification() {
        final String channelId = "diablo_bubble_service";
        final int notificationId = 4105;

        NotificationManager manager =
                (NotificationManager)
                        getSystemService(NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel =
                    new NotificationChannel(
                            channelId,
                            "DIABLO Floating Scanner",
                            NotificationManager.IMPORTANCE_LOW
                    );

            channel.setDescription(
                    "Keeps the DIABLO floating scanner service running."
            );

            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, channelId);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("DIABLO SCANNER")
                .setContentText("Floating scanner service is running.")
                .setOngoing(true);

        startForeground(notificationId, builder.build());
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {
        if (intent != null && "com.diablo.scanner.APPLY_APPEARANCE".equals(intent.getAction())) {
            applySavedBubbleAppearance();
            return START_STICKY;
        }
        return START_STICKY;
    }

    private GradientDrawable makePanelBackground() {
        GradientDrawable shape = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(17, 29, 51), Color.rgb(8, 14, 30)});
        shape.setCornerRadius(24);
        shape.setStroke(2, Color.rgb(255, 10, 31));
        return shape;
    }

    private GradientDrawable makeBackground(int color) {
        GradientDrawable shape = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(17, 29, 51), color, Color.rgb(8, 14, 30)});
        shape.setCornerRadius(24);
        shape.setStroke(2, Color.rgb(30, 52, 90));
        return shape;
    }

    private void applySavedBubbleAppearance() {
        if (bubble instanceof TextView) {
            applySavedBubbleAppearance((TextView) bubble);
            if (bubbleParams != null && windowManager != null) {
                try { windowManager.updateViewLayout(bubble, bubbleParams); } catch (Exception ignored) { }
            }
        }
    }

    private void applySavedBubbleAppearance(TextView icon) {
        try {
            JSONObject appearance = new JSONObject(getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString("appearance_settings_json", "{}"));
            int color = Color.parseColor(appearance.optString("csBubble", "#FF0A1F"));
            int border = Color.parseColor(appearance.optString("csBubbleBorder", "#FF0A1F"));
            int sizeDp = Math.max(44, Math.min(76, appearance.optInt("csSize", 58)));
            int alpha = Math.max(65, Math.min(100, appearance.optInt("csOpacity", 100)));
            String shapeName = appearance.optString("csShape", "circle");
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(color);
            if ("circle".equals(shapeName)) shape.setShape(GradientDrawable.OVAL);
            else shape.setShape(GradientDrawable.RECTANGLE);
            shape.setCornerRadius("rounded".equals(shapeName) ? dp(13) : dp(5));
            shape.setStroke(dp(2), border);
            icon.setBackground(shape);
            icon.setText("D");
            icon.setTextColor(Color.WHITE);
            icon.setAlpha(alpha / 100f);
            boolean glow = appearance.optBoolean("csGlow", true);
            icon.setShadowLayer(glow ? 8f : 0f, 0f, 0f, glow ? color : Color.TRANSPARENT);
            int size = dp(sizeDp);
            if (bubbleParams != null) {
                bubbleParams.width = size;
                bubbleParams.height = size;
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("appearance_animations", appearance.optBoolean("csAnimations", true)
                            && !appearance.optBoolean("csPerformance", false)).apply();
        } catch (Exception ignored) {
            icon.setText("D");
        }
    }

    private GradientDrawable makeBubbleBackground() {
        GradientDrawable shape = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(255, 58, 76), Color.rgb(176, 8, 22), Color.rgb(64, 0, 8)});
        shape.setShape(GradientDrawable.OVAL);
        shape.setStroke(2, Color.rgb(255, 10, 31));
        return shape;
    }

    private TextView makeText(
            String text,
            int size,
            int color
    ) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(10, 9, 10, 9);
        return view;
    }

    private int getOverlayType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }

        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    private WindowManager.LayoutParams makeParams(
            int width,
            int height
    ) {
        return new WindowManager.LayoutParams(
                width,
                height,
                getOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
    }

    // MOVABLE D BUBBLE + DRAG-TO-X DISMISS TARGET

    private void showDismissTarget() {
        if (dismissTargetVisible || windowManager == null) return;
        TextView target = makeText("✕\\nREMOVE", 12, Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.rgb(150, 0, 18));
        bg.setStroke(dp(3), Color.rgb(255, 10, 31));
        target.setBackground(bg);
        target.setElevation(dp(12));
        target.setGravity(Gravity.CENTER);
        int size = dp(76);
        WindowManager.LayoutParams params = makeParams(size, size);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.x = 0;
        params.y = dp(32);
        try {
            windowManager.addView(target, params);
            dismissTargetView = target;
            dismissTargetVisible = true;
        } catch (Exception ignored) {
            dismissTargetView = null;
            dismissTargetVisible = false;
        }
    }

    private void hideDismissTarget() {
        if (dismissTargetView != null && windowManager != null) {
            try { windowManager.removeView(dismissTargetView); } catch (Exception ignored) { }
        }
        dismissTargetView = null;
        dismissTargetVisible = false;
    }

    private boolean bubbleDroppedOnDismissTarget() {
        if (bubbleParams == null) return false;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int bubbleSize = dp(54);
        float bubbleCenterX = bubbleParams.x + bubbleSize / 2f;
        float bubbleCenterY = bubbleParams.y + bubbleSize / 2f;
        float targetCenterX = screenWidth / 2f;
        float targetCenterY = screenHeight - dp(32) - dp(38);
        float dx = bubbleCenterX - targetCenterX;
        float dy = bubbleCenterY - targetCenterY;
        return dx * dx + dy * dy <= dp(82) * dp(82);
    }

    private void dismissBubbleUntilRestarted() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("bubble_dismissed_by_user", true)
                .putBoolean("auto_scan_enabled", false)
                .apply();
        handler.removeCallbacks(autoScanRunnable);
        hideDismissTarget();
        if (expanded) collapsePanel();
        if (bubble != null && windowManager != null) {
            try { windowManager.removeView(bubble); } catch (Exception ignored) { }
        }
        bubble = null;
        stopForeground(true);
        stopSelf();
    }

    private void showBubble() {
        TextView icon = makeText("D", 24, Color.WHITE);
        icon.setShadowLayer(8f, 0f, 0f, Color.rgb(255, 10, 31));
        icon.setBackground(makeBubbleBackground());
        icon.setElevation(10f);
        applySavedBubbleAppearance(icon);
        icon.setScaleX(0.94f);
        icon.setScaleY(0.94f);
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("appearance_animations", true)) {
            icon.animate().scaleX(1f).scaleY(1f).setDuration(850).start();
        } else {
            icon.setScaleX(1f);
            icon.setScaleY(1f);
        }

        bubble = icon;

        int bubbleSize = (int) (54 * getResources().getDisplayMetrics().density);
        bubbleParams = makeParams(bubbleSize, bubbleSize);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = 12;
        bubbleParams.y = 180;

        try {
            windowManager.addView(bubble, bubbleParams);
        } catch (Exception error) {
            bubble = null;
            stopSelf();
            return;
        }

        icon.setOnTouchListener(new View.OnTouchListener() {
            float downX;
            float downY;
            int startX;
            int startY;
            boolean moved;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    downX = event.getRawX();
                    downY = event.getRawY();
                    startX = bubbleParams.x;
                    startY = bubbleParams.y;
                    moved = false;
                    return true;
                }

                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;

                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                        moved = true;
                        showDismissTarget();
                    }

                    bubbleParams.x = Math.max(0, startX + (int) dx);
                    bubbleParams.y = Math.max(0, startY + (int) dy);

                    try {
                        windowManager.updateViewLayout(bubble, bubbleParams);
                    } catch (Exception ignored) { }

                    return true;
                }

                if (event.getAction() == MotionEvent.ACTION_UP) {
                    boolean dismiss = moved && bubbleDroppedOnDismissTarget();
                    hideDismissTarget();
                    if (dismiss) {
                        dismissBubbleUntilRestarted();
                    } else if (!moved) {
                        requestOneScan();
                    }
                    return true;
                }

                return false;
            }
        });
    }

    private TextView addButton(
            LinearLayout layout,
            String label,
            int backgroundColor,
            View.OnClickListener listener
    ) {
        TextView button = makeText(label, 13, Color.WHITE);

        button.setBackground(makeBackground(backgroundColor));
        button.setMinHeight((int) (44 * getResources().getDisplayMetrics().density));
        button.setLetterSpacing(0.025f);

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        params.topMargin = 4;
        params.bottomMargin = 2;

        layout.addView(button, params);
        button.setOnClickListener(listener);
        return button;
    }

    private String currentModeLabel() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String mode = prefs.getString("trading_mode", "TIMED");
        return "SCALPING".equalsIgnoreCase(mode)
                ? "MODE: AI SCALPING"
                : "MODE: TIMED SIGNAL";
    }

    private void toggleTradingMode() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String current = prefs.getString("trading_mode", "TIMED");        String next = "SCALPING".equalsIgnoreCase(current) ? "TIMED" : "SCALPING";
        prefs.edit()
                .putString("trading_mode", next)
                .putString("scan_message", "Mode selected: " + ("SCALPING".equals(next) ? "AI Scalping" : "Timed Signal") + ". Tap SCAN to analyse.")
                .apply();
        if (modeButton != null) modeButton.setText(currentModeLabel());
        if (detailsView != null) {
            detailsView.setText("Selected: " + ("SCALPING".equals(next) ? "AI Scalping" : "Timed Signal") +
                    "\nTap SCAN CURRENT CHART ONCE to start one analysis.");
        }
    }

    // EXPANDED BUBBLE PANEL

    private void expandPanel() {
        if (expanded || bubble == null) return;
        expanded = true;
        int oldX = bubbleParams.x;
        int oldY = bubbleParams.y;
        try { windowManager.removeView(bubble); } catch (Exception ignored) { }
        bubble = null;

        final float density = getResources().getDisplayMetrics().density;
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(10), dp(9), dp(10), dp(8));
        shell.setBackground(makePanelBackground());
        shell.setElevation(dp(12));

        TextView title = makeText("● DIABLO SCANNER  ·  DRAG TO MOVE", 13, Color.rgb(255,10,31));
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        title.setPadding(dp(4), dp(5), dp(4), dp(5));
        title.setContentDescription("Drag this header to move the Diablo Scanner panel");
        // Drag from the header only so buttons and ScrollView keep their normal touch behaviour.
        title.setOnTouchListener(new View.OnTouchListener() {
            private int startX, startY;
            private float downRawX, downRawY;
            private boolean dragging;
            @Override public boolean onTouch(View v, MotionEvent event) {
                if (panelParams == null || panel == null) return false;
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = panelParams.x; startY = panelParams.y;
                        downRawX = event.getRawX(); downRawY = event.getRawY();
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int)(event.getRawX() - downRawX);
                        int dy = (int)(event.getRawY() - downRawY);
                        if (Math.abs(dx) > dp(5) || Math.abs(dy) > dp(5)) dragging = true;
                        if (dragging) {
                            DisplayMetrics dm = getResources().getDisplayMetrics();
                            int w = panelParams.width, h = panelParams.height;
                            panelParams.x = Math.max(0, Math.min(startX + dx, dm.widthPixels - w));
                            panelParams.y = Math.max(0, Math.min(startY + dy, dm.heightPixels - h));
                            try { windowManager.updateViewLayout(panel, panelParams); } catch (Exception ignored) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                }
                return false;
            }
        });
        shell.addView(title);

        TextView subtitle = makeText("SHAZAM FOR TRADING CHARTS • BOARD 02 • LIVE VISION • LOCAL APP", 8, Color.rgb(138,155,184));
        subtitle.setGravity(Gravity.START);
        subtitle.setPadding(dp(4), 0, dp(4), dp(4));
        shell.addView(subtitle);

        TextView modeSummary = makeText("Total trades • marked WIN/LOSS only • " + currentModeLabel(), 8, Color.rgb(109,128,160));
        modeSummary.setGravity(Gravity.START);
        modeSummary.setPadding(dp(4), 0, dp(4), dp(5));
        shell.addView(modeSummary);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        // Dedicated live activity/result area: separates AI progress/errors from the final trade signal.
        TextView activityHeading = makeText("LIVE AI ACTIVITY / RESULT", 9, Color.rgb(255, 10, 31));
        activityHeading.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        activityHeading.setPadding(dp(5), dp(7), dp(5), dp(2));
        content.addView(activityHeading);

        activityView = makeText("● READY • Waiting for scan", 10, Color.rgb(0, 255, 122));
        activityView.setGravity(Gravity.CENTER);
        activityView.setBackground(makeBackground(Color.rgb(7, 16, 28)));
        activityView.setPadding(dp(7), dp(8), dp(7), dp(8));
        content.addView(activityView);

        signalView = makeText("WAIT", 23, Color.YELLOW);
        signalView.setPadding(dp(4), dp(4), dp(4), dp(2));
        content.addView(signalView);

        detailsView = makeText("Tap D to scan.", 9, Color.rgb(180,195,218));
        detailsView.setGravity(Gravity.CENTER);
        detailsView.setMaxLines(3);
        detailsView.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(detailsView);

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        String[][] labels = {
            {"🔍", "SCAN CURRENT CHART ONCE"},
            {"🧭", "START GUIDED SCALPING 4H → 15M → 1M"},
            {"✅", "CONFIRM TIMEFRAME & SCAN"},
            {"➡", "NEXT TIMEFRAME"},
            {"🎯", "END GUIDED SCALPING"},
            {"◷", autoScanButtonLabel()},
            {"⏱", durationButtonLabel()},
            {"✓", "MARK WIN"},
            {"✕", "MARK LOSS"},
            {"?", "MARK UNKNOWN"},
            {"↻", "REFRESH NOW"},
            {"↓", "MINIMIZE / CLOSE BUBBLE"}
        };
        for (int row = 0; row < labels.length; row += 2) {
            LinearLayout rowLayout = new LinearLayout(this);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setGravity(Gravity.CENTER_VERTICAL);
            addGridButton(rowLayout, labels[row][0], labels[row][1], row);
            addGridButton(rowLayout, labels[row + 1][0], labels[row + 1][1], row + 1);
            grid.addView(rowLayout, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        content.addView(grid);

        tradeStatusView = makeText("Trade tracker ready.", 9, Color.WHITE);
        content.addView(tradeStatusView);
        statsView = makeText("", 8, Color.rgb(109,128,160));
        content.addView(statsView);

        TextView patternFooter = makeText("Pattern Library 148 • Timed pattern library • chart patterns track trend structure and candlestick combinations • confidence is an estimate, not a verified win probability.", 8, Color.rgb(109,128,160));
        patternFooter.setGravity(Gravity.START);
        patternFooter.setPadding(dp(5), dp(5), dp(5), dp(3));
        content.addView(patternFooter);

        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        shell.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        panel = shell;
        DisplayMetrics screenMetrics = getResources().getDisplayMetrics();
        int screenHeight = screenMetrics.heightPixels;
        int screenWidth = screenMetrics.widthPixels;
        // Half-screen width, with a taller portrait panel. Keep the existing ScrollView:
        // long content scrolls inside the panel rather than pushing buttons off-screen.
        int panelWidth = Math.max(dp(160), Math.min((int)(screenWidth * 0.50f), screenWidth - dp(16)));
        int requestedHeight = Math.max(dp(280), (int)(screenWidth * 0.75f));
        int maxPanelHeight = Math.min(requestedHeight, Math.max(dp(220), (int)(screenHeight * 0.75f)));
        panelParams = makeParams(panelWidth, maxPanelHeight);
        panelParams.gravity = Gravity.TOP | Gravity.START;
        panelParams.x = Math.max(dp(8), Math.min(oldX, screenWidth - panelWidth - dp(8)));
        panelParams.y = Math.max(dp(8), Math.min(oldY, screenHeight - maxPanelHeight - dp(8)));
        try {
            windowManager.addView(panel, panelParams);
        } catch (Exception error) {
            panel = null; expanded = false;
            signalView = null; detailsView = null; tradeStatusView = null; statsView = null;
            showBubble(); return;
        }
        refreshLatestResult();
        updateTradeStatus();
        updateStats();
    }

    private int dp(float value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String durationButtonLabel() {
        long duration = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getLong("trade_duration_ms", DEFAULT_TRADE_DURATION_MS);
        return duration >= 300000L ? "TRADE DURATION: 5 MIN" : "TRADE DURATION: 1 MIN";
    }

    private void cycleTradeDuration() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        long current = prefs.getLong("trade_duration_ms", DEFAULT_TRADE_DURATION_MS);
        long next = current >= 300000L ? 60000L : 300000L;
        prefs.edit().putLong("trade_duration_ms", next).apply();
        tradeDurationMs = next;
        if (detailsView != null) detailsView.setText("Trade duration set to " + (next / 60000L) + " minute(s).");
        showActionToast((next / 60000L) + " MIN TRADE ACTIVATED");
        refreshGridDurationLabel();
    }

    private void refreshGridDurationLabel() {
        // Rebuild the expanded panel so the duration button label reflects the saved setting.
        if (expanded) {
            int x = panelParams != null ? panelParams.x : dp(8);
            int y = panelParams != null ? panelParams.y : dp(180);
            collapsePanel();
            if (bubbleParams != null) { bubbleParams.x = x; bubbleParams.y = y; }
            expandPanel();
        }
    }

    private void addGridButton(LinearLayout row, String iconText, String label, int actionIndex) {
        LinearLayout button = new LinearLayout(this);
        button.setOrientation(LinearLayout.HORIZONTAL);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(dp(6), dp(5), dp(5), dp(5));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(18,31,54));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), Color.rgb(30,51,86));
        button.setBackground(bg);

        TextView icon = makeText(iconText, 16, actionIndex == 7 ? Color.rgb(0,255,122) :
                (actionIndex == 4 || actionIndex == 8 ? Color.rgb(255,10,31) : Color.rgb(56,189,248)));
        icon.setBackground(makeIconBackground(actionIndex));
        icon.setMinWidth(dp(32));
        icon.setMinHeight(dp(32));
        icon.setPadding(dp(2), dp(2), dp(2), dp(2));
        TextView text = makeText(label, 8, Color.WHITE);
        text.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        text.setMaxLines(3);
        text.setEllipsize(TextUtils.TruncateAt.END);
        button.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textParams.leftMargin = dp(5);
        button.addView(text, textParams);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1f);
        params.setMargins(dp(2), dp(3), dp(2), dp(3));
        row.addView(button, params);

        button.setOnClickListener(v -> {
            switch (actionIndex) {
                case 0: showActionToast("SCAN CURRENT CHART"); requestOneScan(); break;
                case 1: showActionToast("GUIDED SCALPING STARTED 4H → 15M → 1M"); startGuidedScalping(); break;
                case 2: showActionToast("TIMEFRAME CONFIRMED & SCANNED"); confirmGuidedTimeframeAndScan(); break;
                case 3: showActionToast("NEXT TIMEFRAME"); advanceGuidedTimeframe(); break;
                case 4: endGuidedScalping(); showActionToast("GUIDED SCALPING ENDED"); break;
                case 5: toggleAutoScan(); showActionToast(autoScanButtonLabel()); break;
                case 6: cycleTradeDuration(); break;
                case 7: if (!tradeActive && !awaitingResult) startTrade(); saveTradeResult("WIN"); showActionToast("MARKED WIN"); break;
                case 8: if (!tradeActive && !awaitingResult) startTrade(); saveTradeResult("LOSS"); showActionToast("MARKED LOSS"); break;
                case 9: if (!tradeActive && !awaitingResult) startTrade(); saveTradeResult("UNKNOWN"); showActionToast("MARKED UNKNOWN"); break;
                case 10: refreshLatestResult(); updateTradeStatus(); updateStats(); showActionToast("REFRESHED NOW"); break;
                case 11: showActionToast("MINIMIZED"); collapsePanel(); break;
                default: break;
            }
        });
    }

    private GradientDrawable makeIconBackground(int actionIndex) {
        int fill = (actionIndex == 7) ? Color.argb(30,0,255,122) :
                ((actionIndex == 4 || actionIndex == 8) ? Color.argb(30,255,10,31) : Color.argb(30,56,189,248));
        int stroke = (actionIndex == 7) ? Color.argb(80,0,255,122) :
                ((actionIndex == 4 || actionIndex == 8) ? Color.argb(80,255,10,31) : Color.argb(80,56,189,248));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill); shape.setCornerRadius(dp(10)); shape.setStroke(dp(1), stroke);
        return shape;
    }

    private void showActionToast(String message) {
        Toast toast = Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT);
        toast.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, dp(28));
        toast.show();
    }

    // Guided workflow: user must manually change the broker timeframe.
    private static final String[] GUIDED_TIMEFRAMES = {"4h", "15m", "1m"};

    private String guidedProgressText() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int stage = prefs.getInt("guided_scalping_stage", -1);
        if (stage < 0 || stage >= GUIDED_TIMEFRAMES.length) return "";
        long started = prefs.getLong("guided_scalping_started_at", 0L);
        long elapsed = started > 0L ? Math.max(0L, (System.currentTimeMillis() - started) / 1000L) : 0L;
        String elapsedText = String.format(java.util.Locale.ROOT, "%02d:%02d", elapsed / 60L, elapsed % 60L);
        return "GUIDED SCALPING STEP " + (stage + 1) + "/3 • " +
                GUIDED_TIMEFRAMES[stage].toUpperCase(java.util.Locale.ROOT) +
                " • workflow elapsed " + elapsedText +
                "\nTarget: aim to finish AI processing within 5 minutes; this timer also includes manual chart switching, and completion is not guaranteed.";
    }

    private void startGuidedScalping() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.edit().putString("trading_mode", "SCALPING")
                .putBoolean("auto_scan_enabled", false)
                .putInt("guided_scalping_stage", 0)
                .putLong("guided_scalping_started_at", System.currentTimeMillis())
                .putString("chart_timeframe", GUIDED_TIMEFRAMES[0])
                .putString("guided_scalping_status", "WAITING_FOR_4H")
                .putString("scan_message", "GUIDED SCALPING 1/3: Manually change the broker chart to 4H. The app cannot change or verify the broker timeframe. Then tap CONFIRM TIMEFRAME & SCAN.")
                .apply();
        handler.removeCallbacks(autoScanRunnable);
        if (modeButton != null) modeButton.setText(currentModeLabel());
        if (detailsView != null) detailsView.setText(guidedInstructions(0));
        if (signalView != null) {
            signalView.setText("GUIDED 1/3");
            signalView.setTextColor(Color.rgb(255, 145, 48));
        }
    }

    private String guidedInstructions(int stage) {
        String tf = GUIDED_TIMEFRAMES[Math.max(0, Math.min(stage, GUIDED_TIMEFRAMES.length - 1))];
        return "GUIDED SCALPING • STEP " + (stage + 1) + "/3\n" +
                "Required broker timeframe: " + tf.toUpperCase(java.util.Locale.ROOT) + "\n" +
                "1. Change the broker chart timeframe manually.\n" +
                "2. Check that the visible chart matches this step.\n" +
                "3. Tap CONFIRM TIMEFRAME & SCAN.\n" +
                "4. Wait for this scan to finish before NEXT TIMEFRAME.\n\n" +
                "Elapsed workflow time: " + guidedElapsed() + "\n" +
                "The app cannot verify the broker timeframe from the screenshot alone. Each stage is saved separately; no combined setup is claimed until all three stages have been reviewed.";
    }

    private String guidedElapsed() {
        long started = getSharedPreferences(PREFS, MODE_PRIVATE).getLong("guided_scalping_started_at", 0L);
        if (started <= 0L) return "not started";
        long seconds = Math.max(0L, (System.currentTimeMillis() - started) / 1000L);
        return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60L, seconds % 60L);
    }

    private void confirmGuidedTimeframeAndScan() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int stage = prefs.getInt("guided_scalping_stage", -1);
        if (stage < 0 || stage >= GUIDED_TIMEFRAMES.length) {
            if (detailsView != null) detailsView.setText("Tap START GUIDED SCALPING first.");
            return;
        }
        String status = prefs.getString("scan_status", "WAIT");
        if ("SCANNING".equals(status) || "AI_PROCESSING".equals(status)) {
            if (detailsView != null) detailsView.setText("A scan is already running. Wait for it to finish before scanning this stage again.");
            return;
        }
        prefs.edit().putString("chart_timeframe", GUIDED_TIMEFRAMES[stage])
                .putString("guided_scalping_status", "SCANNING_" + GUIDED_TIMEFRAMES[stage].toUpperCase(java.util.Locale.ROOT))
                .putString("scan_message", "GUIDED SCALPING STEP " + (stage + 1) + "/3. You confirmed the visible chart timeframe as " + GUIDED_TIMEFRAMES[stage] + "; broker timeframe is not machine-verified.")
                .apply();
        requestOneScan();
    }

    private void maybeAutoAdvanceGuidedScalping(String scanStatus) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String guidedStatus = prefs.getString("guided_scalping_status", "");
        if (!guidedStatus.startsWith("SCANNING_")) return;
        if ("SCANNING".equals(scanStatus) || "AI_PROCESSING".equals(scanStatus)
                || "AI_ERROR".equals(scanStatus) || "NEEDS_CAPTURE".equals(scanStatus)
                || "SAVED_ANALYZING".equals(scanStatus)) return;

        int stage = prefs.getInt("guided_scalping_stage", -1);
        if (stage < 0 || stage >= GUIDED_TIMEFRAMES.length) return;
        String signal = normaliseSignal(prefs.getString("latest_ai_signal", "WAIT"));
        String details = prefs.getString("latest_ai_details", "");
        if (details == null) details = "";
        String upperDetails = details.toUpperCase(java.util.Locale.ROOT);
        boolean failed = "AI_ERROR".equals(scanStatus)
                || upperDetails.contains("ANALYSIS FAILED")
                || upperDetails.contains("MODEL ERROR")
                || upperDetails.contains("MARKET FEED ERROR")
                || upperDetails.contains("COULD NOT");
        boolean directional = "BUY".equals(signal) || "SELL".equals(signal)
                || "UP".equals(signal) || "DOWN".equals(signal);

        if (failed || "WAIT".equals(signal) || !directional) {
            prefs.edit().putString("guided_scalping_status",
                            stage == 2 ? "WAITING_FOR_ENTRY" : "WAITING_FOR_" + GUIDED_TIMEFRAMES[stage].toUpperCase(java.util.Locale.ROOT))
                    .putString("scan_message", "GUIDED " + (stage + 1) + "/3 • " +
                            GUIDED_TIMEFRAMES[stage].toUpperCase(java.util.Locale.ROOT) +
                            " was not completed: " + (failed ? "analysis error" : "model returned WAIT") +
                            ". Fix the chart/model issue and scan this same stage again.\nPrevious saved findings: " +
                            guidedContextSummary(prefs))
                    .apply();
            return;
        }

        if (stage == 2) {
            String entry = findTradeField(details, "ENTRY");
            String sl = findTradeField(details, "STOP LOSS|STOP_LOSS|SL");
            String tp = findTradeField(details, "TAKE PROFIT|TAKE_PROFIT|TP");
            if (entry.isEmpty() || sl.isEmpty() || tp.isEmpty()) {
                prefs.edit().putString("guided_scalping_status", "WAITING_FOR_ENTRY")
                        .putString("scan_message", "1M direction returned, but no complete entry + stop-loss + take-profit was found. WAITING FOR ENTRY; rescan later. No signal notification sent.")
                        .apply();
                return;
            }
            String key = signal + "|" + entry + "|" + sl + "|" + tp;
            if (!key.equals(prefs.getString("guided_scalping_last_alert_key", ""))) {
                postGuidedSignalNotification(signal, entry, sl, tp, details);
                prefs.edit().putString("guided_scalping_last_alert_key", key).apply();
            }
            prefs.edit().putString("guided_scalping_status", "COMPLETE")
                    .putString("scan_message", "GUIDED SCALPING COMPLETE • 1M entry details found. Signal notification issued.\nSaved 4H / 15M context: " + guidedContextSummary(prefs))
                    .apply();
            return;
        }

        try {
            JSONObject context = new JSONObject(prefs.getString("guided_scalping_context", "{}"));
            JSONObject finding = new JSONObject();
            finding.put("timeframe", GUIDED_TIMEFRAMES[stage].toUpperCase(java.util.Locale.ROOT));
            finding.put("signal", signal);
            finding.put("details", details);
            finding.put("summary", details);
            finding.put("savedAt", System.currentTimeMillis());
            context.put(GUIDED_TIMEFRAMES[stage], finding);
            int next = stage + 1;
            String summary = guidedContextSummary(prefs);
            prefs.edit().putString("guided_scalping_context", context.toString())
                    .putString("guided_scalping_last_stage", GUIDED_TIMEFRAMES[stage])
                    .putString("guided_scalping_last_updated", String.valueOf(System.currentTimeMillis()))
                    .putInt("guided_scalping_stage", next)
                    .putString("chart_timeframe", GUIDED_TIMEFRAMES[next])
                    .putString("guided_scalping_status", "WAITING_FOR_" + GUIDED_TIMEFRAMES[next].toUpperCase(java.util.Locale.ROOT))
                    .putString("scan_message", "GUIDED " + (stage + 1) + "/3 DONE • " +
                            GUIDED_TIMEFRAMES[stage].toUpperCase(java.util.Locale.ROOT) + " " + signal +
                            " saved. Previous findings: " + summary +
                            "\nManually switch the broker chart to " + GUIDED_TIMEFRAMES[next].toUpperCase(java.util.Locale.ROOT) +
                            " and tap CONFIRM TIMEFRAME & SCAN.")
                    .apply();
        } catch (Exception ignored) {
            prefs.edit().putString("guided_scalping_status", "WAITING_FOR_" + GUIDED_TIMEFRAMES[stage].toUpperCase(java.util.Locale.ROOT)).apply();
        }
    }

    private String guidedContextSummary(SharedPreferences prefs) {
        try {
            JSONObject context = new JSONObject(prefs.getString("guided_scalping_context", "{}"));
            StringBuilder out = new StringBuilder();
            for (String tf : GUIDED_TIMEFRAMES) {
                JSONObject item = context.optJSONObject(tf);
                if (item == null) continue;
                if (out.length() > 0) out.append(" • ");
                out.append(tf.toUpperCase(java.util.Locale.ROOT)).append(' ')
                        .append(item.optString("signal", "WAIT"));
            }
            return out.length() == 0 ? "No completed timeframe findings yet." : out.toString();
        } catch (Exception ignored) { return "Saved context unavailable."; }
    }

    private String findTradeField(String details, String labelPattern) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "(?im)\\b(?:" + labelPattern + ")\\b\\s*[:=\\-]?\\s*([^\\r\\n;|]+)").matcher(details);
            if (m.find()) {
                String value = m.group(1).trim();
                if (!value.isEmpty() && !value.equalsIgnoreCase("N/A")
                        && !value.equalsIgnoreCase("not available")) return value;
            }
        } catch (Exception ignored) { }
        return "";
    }

    private void postGuidedSignalNotification(String signal, String entry, String sl, String tp, String details) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        String channelId = "diablo_guided_signal_ready";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId, "Guided Scalping signal alerts", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Alerts when Guided Scalping finds entry, stop-loss and take-profit details.");
            channel.enableVibration(true);
            manager.createNotificationChannel(channel);
        }
        String side = ("BUY".equals(signal) || "UP".equals(signal)) ? "BUY" : "SELL";
        String body = "1M entry setup • Entry: " + entry + " • SL: " + sl + " • TP: " + tp
                + "\nModel confidence is an estimate, not a verified win probability.";
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, channelId) : new Notification.Builder(this);
        manager.notify(7201, builder.setSmallIcon(R.drawable.ic_diablo_launcher_vector)
                .setContentTitle("DIABLO — GUIDED SIGNAL READY: " + side)
                .setContentText("Entry " + entry + " • SL " + sl + " • TP " + tp)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setCategory(Notification.CATEGORY_STATUS).setPriority(Notification.PRIORITY_HIGH)
                .setAutoCancel(true).build());
    }

    private void advanceGuidedTimeframe() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int stage = prefs.getInt("guided_scalping_stage", -1);
        if (stage < 0 || stage >= GUIDED_TIMEFRAMES.length) {
            if (detailsView != null) detailsView.setText("Tap START GUIDED SCALPING first.");
            return;
        }
        String status = prefs.getString("scan_status", "WAIT");
        if ("SCANNING".equals(status) || "AI_PROCESSING".equals(status)) {
            if (detailsView != null) detailsView.setText("The current AI scan is still running. Wait for its result before moving to the next timeframe.");
            return;
        }
        if (stage >= GUIDED_TIMEFRAMES.length - 1) {
            prefs.edit().putString("guided_scalping_status", "COMPLETE").apply();
            if (detailsView != null) detailsView.setText("GUIDED SCALPING 3/3 COMPLETE. Review the separate 4H, 15M and 1M history entries together. Broker timeframe was not machine-verified; no validated combined win probability is calculated.\nElapsed: " + guidedElapsed());
            return;
        }
        int next = stage + 1;
        prefs.edit().putInt("guided_scalping_stage", next)
                .putString("chart_timeframe", GUIDED_TIMEFRAMES[next])
                .putString("guided_scalping_status", "WAITING_FOR_" + GUIDED_TIMEFRAMES[next].toUpperCase(java.util.Locale.ROOT))
                .putString("scan_message", "GUIDED SCALPING STEP " + (next + 1) + "/3: Manually change the broker chart to " + GUIDED_TIMEFRAMES[next].toUpperCase(java.util.Locale.ROOT) + ", then tap CONFIRM TIMEFRAME & SCAN.")
                .apply();
        if (detailsView != null) detailsView.setText(guidedInstructions(next));
    }

    private void endGuidedScalping() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("guided_scalping_stage", -1)
                .putString("guided_scalping_status", "STOPPED").apply();
        if (detailsView != null) detailsView.setText("Guided scalping stopped. Completed scans remain in history.");
    }

    // ONE TAP = ONE FRAME = ONE RESULT

    private void requestOneScan() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        String currentStatus = prefs.getString("scan_status", "WAIT");
        if ("SCANNING".equals(currentStatus) || "AI_PROCESSING".equals(currentStatus)) {
            expandPanel();
            refreshLatestResult();
            return;
        }

        if (!prefs.getBoolean("screen_capture_active", false)) {
            prefs.edit()
                    .putString("scan_status", "NEEDS_CAPTURE")
                    .putString("scan_message", "Start LIVE SCREEN CAPTURE from the dashboard first, then tap D to analyse once.")
                    .apply();
            expandPanel();
            refreshLatestResult();
            return;
        }

        prefs.edit()
                .putString("scan_status", "SCANNING")
                .putLong("scan_started_at", System.currentTimeMillis())                .putString("scan_message", "Capturing the visible chart for one analysis...")
                .apply();

        try {
            Intent intent = new Intent(this, ScreenCaptureService.class);
            intent.setAction(ScreenCaptureService.ACTION_ANALYZE_ONCE);
            startService(intent);
        } catch (Exception error) {
            prefs.edit()
                    .putString("scan_status", "WAIT")
                    .putString("scan_message", "Could not request a scan. Restart screen capture and try again.")
                    .apply();
        }

        expandPanel();
        refreshLatestResult();
    }

    private String autoScanButtonLabel() {
        boolean enabled = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean("auto_scan_enabled", false);
        return enabled ? "AUTO SCAN: ON • EVERY 10 SECONDS" : "AUTO SCAN: OFF • EVERY 10 SECONDS";
    }

    private void toggleAutoScan() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean enable = !prefs.getBoolean("auto_scan_enabled", false);
        prefs.edit().putBoolean("auto_scan_enabled", enable).apply();

        handler.removeCallbacks(autoScanRunnable);
        if (enable) {
            handler.postDelayed(autoScanRunnable, 1000L);
        }
        if (detailsView != null) {
            detailsView.setText(enable
                    ? "Automatic scanning enabled: one chart scan every 10 seconds while screen capture is active. A scan already running will not be duplicated."
                    : "Automatic scanning stopped. Tap SCAN CURRENT CHART ONCE whenever you want a manual scan.");
        }
        if (expanded && panel != null) {
            // Refresh the visible button label by rebuilding the panel on the next user action.
            // Keep the current panel stable; status text confirms the saved setting.
        }
    }

    private void requestAutomaticScan() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean("screen_capture_active", false)) return;

        prefs.edit()
                .putString("scan_status", "SCANNING")
                .putLong("scan_started_at", System.currentTimeMillis())
                .putString("scan_message", "Automatic scan: capturing the visible chart...")
                .apply();
        try {
            Intent intent = new Intent(this, ScreenCaptureService.class);
            intent.setAction(ScreenCaptureService.ACTION_ANALYZE_ONCE);
            startService(intent);
        } catch (Exception error) {
            prefs.edit().putString("scan_status", "WAIT")
                    .putString("scan_message", "Automatic scan could not start. Check screen capture permission.")
                    .apply();
        }
    }

    // READ LATEST RESULT

    private void refreshLatestResult() {
        if (signalView == null || detailsView == null) {
            return;
        }

        SharedPreferences statusPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String scanStatus = statusPrefs.getString("scan_status", "WAIT");

        if ("SCANNING".equals(scanStatus) || "AI_PROCESSING".equals(scanStatus)) {
            long startedAt = statusPrefs.getLong("scan_started_at", System.currentTimeMillis());
            long elapsedSeconds = Math.max(0L, (System.currentTimeMillis() - startedAt) / 1000L);
            String[] ray = {"◢", "◣", "◤", "◥"};
            String currentRay = ray[scanRayFrame++ % ray.length];
            boolean processingAI = "AI_PROCESSING".equals(scanStatus);
            signalView.setText("WAIT");
            signalView.setTextColor(Color.YELLOW);
            if (activityView != null) {
                activityView.setText((processingAI ? "◉ AI BUSY" : currentRay + " SCANNING") +
                        " • " + Math.min(99L, elapsedSeconds * 10L) + "% est." +
                        (elapsedSeconds >= 90L ? " • SLOW" : ""));
                activityView.setTextColor(Color.CYAN);
            }
            String progress = elapsedSeconds < 10L
                    ? "Elapsed: " + elapsedSeconds + "s / 10s target"
                    : "DELAYED: " + elapsedSeconds + "s elapsed (10s target exceeded)";
            String stageMessage = statusPrefs.getString("scan_message", "Preparing chart analysis...");
            detailsView.setText(
                    "BLACK-RAY SCAN ACTIVE\n" +
                    (processingAI ? "AI PROCESSING • AI BUSY\n" : "Capturing visible chart...\n") +
                    progress + "\n" + stageMessage + "\n" + guidedProgressText() + "\n" +
                    (elapsedSeconds >= 90L
                            ? "ANALYSIS TIMEOUT WARNING: model is taking too long."
                            : elapsedSeconds >= 10L
                            ? "Still working. Keep the chart open; do not tap again."
                            : "Please wait. One scan only; no repeated signals.")
            );
            return;
        }

        if ("AI_ERROR".equals(scanStatus)) {
            if (activityView != null) {
                activityView.setText("✕ AI ERROR");
                activityView.setTextColor(Color.RED);
            }
            signalView.setText("ERROR");
            signalView.setTextColor(Color.RED);
            detailsView.setText(statusPrefs.getString("scan_message", "Analysis failed. Check model/engine and retry."));
            return;
        }

        if ("NEEDS_CAPTURE".equals(scanStatus)) {
            signalView.setText("WAIT");
            signalView.setTextColor(Color.YELLOW);
            if (activityView != null) {
                activityView.setText("● NEEDS SCREEN CAPTURE");
                activityView.setTextColor(Color.YELLOW);
            }
            detailsView.setText(statusPrefs.getString("scan_message", "Start screen capture first."));
            return;
        }

        // Automatically move to the next guided instruction after a scan finishes.
        // The user still changes the broker chart timeframe manually and confirms it.
        maybeAutoAdvanceGuidedScalping(scanStatus);

        // Keep the guided instruction screen visible between scans instead of
        // letting the normal result renderer overwrite it on the next refresh.
        String guidedStatusNow = statusPrefs.getString("guided_scalping_status", "");
        int guidedStageNow = statusPrefs.getInt("guided_scalping_stage", -1);
        if (guidedStatusNow.startsWith("WAITING_FOR_") && guidedStageNow >= 0
                && guidedStageNow < GUIDED_TIMEFRAMES.length) {
            if (activityView != null) {
                activityView.setText("GUIDED SCALPING • STEP " + (guidedStageNow + 1) + "/3 • READY");
                activityView.setTextColor(Color.rgb(255, 145, 48));
            }
            signalView.setText("STEP " + (guidedStageNow + 1) + "/3");
            signalView.setTextColor(Color.rgb(255, 145, 48));
            detailsView.setText(guidedInstructions(guidedStageNow));
            return;
        }
        if ("COMPLETE".equals(guidedStatusNow)) {
            if (activityView != null) {
                activityView.setText("✓ GUIDED SCALPING COMPLETE");
                activityView.setTextColor(Color.rgb(0, 255, 122));
            }
            signalView.setText("DONE ✓");
            signalView.setTextColor(Color.rgb(0, 255, 122));
            detailsView.setText("GUIDED SCALPING 3/3 COMPLETE. Review the separate 4H, 15M and 1M history entries together. The app did not verify broker timeframes or calculate a validated combined win probability.\\nElapsed: " + guidedElapsed());
            return;
        }

        String signal = "WAIT";
        String time = "No saved result yet";
        String source = "No analysis recorded";
        String explanation = "";
        int score = 0;

        boolean usingAI = false;

        SharedPreferences prefs =
                getSharedPreferences(PREFS, MODE_PRIVATE);

        SharedPreferences aiPrefs =
                getSharedPreferences(AI_PREFS, MODE_PRIVATE);

        String aiSignal = aiPrefs.getString(
                "latest_ai_signal",
                ""
        );

        String aiTime = aiPrefs.getString(
                "latest_ai_time",
                ""
        );

        String aiDetails = aiPrefs.getString(
                "latest_ai_details",
                ""
        );

        int aiScore = aiPrefs.getInt("latest_ai_score", 0);

        if ("SCALPING".equalsIgnoreCase(prefs.getString("trading_mode", "TIMED"))
                && isFreshAIResult(aiSignal, aiTime)) {
            signal = normaliseSignal(aiSignal);
            time = aiTime;
            source = "Source: Local AI chart analysis";
            explanation = aiDetails;
            score = Math.max(0, Math.min(100, aiScore));
            usingAI = true;
        }

        if (!usingAI) {
            try {
                JSONArray history = new JSONArray(
                        prefs.getString("history", "[]")
                );

                for (int i = 0; i < history.length(); i++) {
                    JSONObject item = history.getJSONObject(i);

                    String sourceValue = item.optString(
                            "source",
                            "SAVED_SCREENSHOT"
                    );

                    boolean scalpingSelected = "SCALPING".equalsIgnoreCase(prefs.getString("trading_mode", "TIMED"));
                    if (scalpingSelected && ("LIVE_TIMED".equals(sourceValue) || "SAVED_TIMED".equals(sourceValue))) continue;
                    if (!scalpingSelected && ("LIVE_AI".equals(sourceValue) || "SAVED_AI".equals(sourceValue))) continue;

                    signal = item.optString("signal", "WAIT");
                    time = item.optString("time", "Unknown time");
                    score = item.optInt("score", 0);
                    explanation = item.optString("details", "");

                    if ("LIVE_SCREEN".equals(sourceValue)) {
                        source = "Source: Live screen colour analysis";
                    } else if ("LIVE_TIMED".equals(sourceValue)) {
                        source = "Source: Fast timed-signal rules";
                    } else if ("SAVED_TIMED".equals(sourceValue)) {
                        source = "Source: Saved-chart timed-signal rules";
                    } else {
                        source = "Source: Saved screenshot analysis";
                    }

                    break;
                }
            } catch (Exception ignored) {
                source = "Could not read saved history";
            }
        }

        signal = normaliseSignal(signal);
        if (activityView != null) {
            String confidenceLabel = " • " + Math.max(0, Math.min(100, score)) + "%";
            String stateLabel = "WAIT".equals(signal) ? "WAIT" :
                    (("BUY".equals(signal) || "UP".equals(signal)) ? "BUY" :
                    (("SELL".equals(signal) || "DOWN".equals(signal)) ? "SELL" : "WAIT"));
            activityView.setText("✓ RESULT • " + stateLabel + confidenceLabel);
            activityView.setTextColor("BUY".equals(signal) || "UP".equals(signal)
                    ? Color.rgb(0, 255, 122)
                    : ("SELL".equals(signal) || "DOWN".equals(signal) ? Color.RED : Color.YELLOW));
        }
        signalView.setText(signal);

        if ("UP".equals(signal) || "BUY".equals(signal)) {
            signalView.setTextColor(Color.rgb(50, 230, 120));
            tradeDirection = signal;
        } else if ("DOWN".equals(signal) || "SELL".equals(signal)) {
            signalView.setTextColor(Color.rgb(255, 45, 65));
            tradeDirection = signal;
        } else {
            signalView.setTextColor(Color.YELLOW);
            tradeDirection = "WAIT";
        }

        String patternMemory = prefs.getString(
                "latest_pattern_memory",
                "No saved chart comparison yet."
        );

        int patternCount = prefs.getInt(
                "chart_pattern_count",
                0
        );

        StringBuilder message = new StringBuilder();

        if ("AI_READY".equals(scanStatus)) {
            message.append("AI READY • ANALYSIS COMPLETE\n");
        } else if ("ANALYSIS_COMPLETE_WAIT".equals(scanStatus)) {
            message.append("ANALYSIS COMPLETE • WAIT\n");
        } else if ("SIGNAL_READY".equals(scanStatus)) {
            message.append("TIMED SIGNAL • ANALYSIS COMPLETE\n");
        }
        message.append(source);
        message.append("\nLast result: ").append(time);

        if (usingAI) {
            message.append("\nAI confidence estimate: ")
                    .append(score)
                    .append("%");

            message.append("\n\nAI explanation:\n");

            if (explanation == null || explanation.trim().isEmpty()) {
                message.append("No explanation was returned.");
            } else {
                message.append(explanation.trim());
            }

            message.append(
                    "\n\nAI confidence is subjective, not a verified win probability."
            );
        } else {
            message.append("\nVisual heuristic score: ")
                    .append(score);

            message.append("\nSaved chart patterns: ")
                    .append(patternCount);

            message.append("\nMemory comparison: ")
                    .append(patternMemory);

            if (explanation != null && !explanation.trim().isEmpty()) {
                message.append("\\n\\nTimed Signal explanation:\\n").append(explanation.trim());
            }
            if ("SCALPING".equalsIgnoreCase(prefs.getString("trading_mode", "TIMED"))) {
                message.append("\\n\\nAI result unavailable or not yet available. Check the AI diagnostic message and retry.");
            } else {
                message.append("\\n\\nFast image-rule analysis is not an AI prediction.");
            }
        }

        message.append("\n\n").append(guidedProgressText());
        message.append(
                "\n\nNo signal guarantees a winning trade. DIABLO does not place trades."
        );

        // Keep the floating bubble concise; full explanation remains available in history/dashboard.
        String shortSignal = ("BUY".equals(signal) || "UP".equals(signal)) ? "BUY" :
                (("SELL".equals(signal) || "DOWN".equals(signal)) ? "SELL" : "WAIT");
        detailsView.setMaxLines(2);
        detailsView.setEllipsize(TextUtils.TruncateAt.END);
        detailsView.setText(shortSignal + " • " + Math.max(0, Math.min(100, score)) + "%" +
                (usingAI ? " • AI" : " • FAST") +
                (time == null || time.isEmpty() ? "" : "\\n" + time));
    }

    private boolean isFreshAIResult(String signal, String time) {
        if (signal == null || signal.trim().isEmpty()
                || time == null || time.trim().isEmpty()) {
            return false;
        }

        String normal = normaliseSignal(signal);

        if (!"UP".equals(normal)
                && !"DOWN".equals(normal)
                && !"BUY".equals(normal)
                && !"SELL".equals(normal)
                && !"WAIT".equals(normal)) {
            return false;
        }

        try {
            SimpleDateFormat format = new SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    Locale.getDefault()
            );

            format.setLenient(false);

            Date parsed = format.parse(time);

            if (parsed == null) {
                return false;
            }

            long age = System.currentTimeMillis() - parsed.getTime();

            return age >= -60000L && age <= AI_FRESHNESS_MS;

        } catch (Exception error) {
            return false;
        }
    }

    private String normaliseSignal(String signal) {
        if (signal == null) {
            return "WAIT";
        }

        String upper = signal.trim().toUpperCase(Locale.ROOT);

        if (upper.contains("BUY")) {
            return "BUY";
        }

        if (upper.contains("SELL")) {
            return "SELL";
        }

        if (upper.contains("UP")) {
            return "UP";
        }

        if (upper.contains("DOWN")) {
            return "DOWN";
        }

        return "WAIT";
    }

    // TRADE TIMER

    private void startTrade() {
        if (tradeActive || awaitingResult) {
            if (tradeStatusView != null) {
                tradeStatusView.setText(                        "Finish the current trade record first."
                );
            }
            return;
        }

        SharedPreferences prefs =
                getSharedPreferences(PREFS, MODE_PRIVATE);

        tradeDurationMs = prefs.getLong(
                "trade_duration_ms",
                DEFAULT_TRADE_DURATION_MS
        );

        if (tradeDurationMs < 1000L) {
            tradeDurationMs = DEFAULT_TRADE_DURATION_MS;
        }

        tradeStartMs = System.currentTimeMillis();
        tradeActive = true;
        awaitingResult = false;

        if (tradeStatusView != null) {
            tradeStatusView.setText(
                    "Trade recorded: " + tradeDirection +
                    "\nDuration: " + formatDuration(tradeDurationMs)
            );
        }

        updateTradeStatus();
    }

    private void updateTradeStatus() {
        if (tradeStatusView == null) {
            return;
        }

        if (tradeActive) {
            long remaining = tradeDurationMs -
                    (System.currentTimeMillis() - tradeStartMs);

            if (remaining <= 0L) {
                tradeActive = false;
                awaitingResult = true;

                tradeStatusView.setText(
                        "Duration finished. Mark the actual result: WIN, LOSS or UNKNOWN."
                );
            } else {
                tradeStatusView.setText(
                        "TRADE TIMER RUNNING" +
                        "\nDirection at entry: " + tradeDirection +
                        "\nTime left: " + formatDuration(remaining) +
                        "\nThe timer does not execute trades."
                );
            }
        } else if (awaitingResult) {
            tradeStatusView.setText(
                    "Trade duration finished. Tap MARK WIN, MARK LOSS or MARK UNKNOWN."
            );
        }
    }

    private String formatDuration(long milliseconds) {
        long totalSeconds =
                Math.max(0L, (milliseconds + 999L) / 1000L);

        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;

        if (minutes > 0) {
            return String.format(
                    Locale.US,
                    "%d min %02d sec",
                    minutes,
                    seconds
            );
        }

        return String.format(
                Locale.US,
                "%d sec",
                seconds
        );
    }

    // SAVE USER-CONFIRMED TRADE RESULT

    private void saveTradeResult(String result) {
        if (!tradeActive && !awaitingResult) {
            if (tradeStatusView != null) {
                tradeStatusView.setText(
                        "Tap I TOOK TRADE first to start a trade record."
                );
            }
            return;
        }

        long duration = tradeDurationMs;
        long start = tradeStartMs;

        if (tradeActive) {
            duration = Math.max(
                    0L,
                    System.currentTimeMillis() - tradeStartMs
            );
        }

        try {
            SharedPreferences prefs =
                    getSharedPreferences(TRADE_PREFS, MODE_PRIVATE);

            JSONArray old = new JSONArray(
                    prefs.getString(TRADE_HISTORY, "[]")
            );

            JSONArray updated = new JSONArray();
            JSONObject item = new JSONObject();

            item.put("result", result);
            item.put("direction", tradeDirection);

            item.put(
                    "started_at",
                    new SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss",
                            Locale.getDefault()
                    ).format(new Date(start))
            );

            item.put("duration_ms", duration);

            item.put(
                    "recorded_at",
                    new SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss",
                            Locale.getDefault()
                    ).format(new Date())
            );

            updated.put(item);

            for (int i = 0; i < old.length() && i < 99; i++) {
                updated.put(old.get(i));
            }

            prefs.edit()
                    .putString(TRADE_HISTORY, updated.toString())
                    .apply();

        } catch (Exception ignored) {
        }

        tradeActive = false;
        awaitingResult = false;

        if (tradeStatusView != null) {
            tradeStatusView.setText(
                    "Saved result: " + result +
                    ". You can record another trade."
            );
        }

        updateStats();
    }

    // WIN RATE AND TRADE COUNTS

    private void updateStats() {
        if (statsView == null) {
            return;
        }

        int wins = 0;
        int losses = 0;
        int unknown = 0;

        try {
            JSONArray trades = new JSONArray(
                    getSharedPreferences(
                            TRADE_PREFS,
                            MODE_PRIVATE
                    ).getString(TRADE_HISTORY, "[]")
            );

            for (int i = 0; i < trades.length(); i++) {
                String result = trades.getJSONObject(i)
                        .optString("result", "UNKNOWN");

                if ("WIN".equals(result)) {
                    wins++;
                } else if ("LOSS".equals(result)) {
                    losses++;
                } else {
                    unknown++;
                }
            }
        } catch (Exception ignored) {
        }

        int decided = wins + losses;

        String rate = decided == 0
                ? "N/A"
                : String.format(
                        Locale.US,
                        "%.1f%%",
                        wins * 100.0 / decided
                );

        statsView.setText(
                "TRADE RECORDS" +
                "\nWins: " + wins +
                "  Losses: " + losses +
                "  Unknown: " + unknown +
                "\nRecorded win rate: " + rate +
                "\nOnly marked WIN/LOSS trades count."
        );
    }

    // MINIMISE PANEL

    private void collapsePanel() {
        if (panel != null) {
            try {
                windowManager.removeView(panel);
            } catch (Exception ignored) {
            }

            panel = null;
        }

        activityView = null;
        signalView = null;
        detailsView = null;
        tradeStatusView = null;
        statsView = null;

        expanded = false;
        showBubble();
    }

    // CLEAN SHUTDOWN

    @Override
    public void onDestroy() {
        handler.removeCallbacks(refreshRunnable);
        handler.removeCallbacks(autoScanRunnable);

        if (bubble != null) {
            try {
                windowManager.removeView(bubble);
            } catch (Exception ignored) {
            }
            bubble = null;
        }

        if (panel != null) {
            try {
                windowManager.removeView(panel);
            } catch (Exception ignored) {
            }
            panel = null;
        }

        signalView = null;
        detailsView = null;
        tradeStatusView = null;
        statsView = null;

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}