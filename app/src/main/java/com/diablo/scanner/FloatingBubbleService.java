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
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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

    private TextView signalView;
    private TextView detailsView;
    private TextView tradeStatusView;
    private TextView statsView;
    private TextView modeButton;

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

        showBubble();

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
        return START_STICKY;
    }

    private GradientDrawable makeBackground(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(24);
        shape.setStroke(2, Color.rgb(255, 35, 65));
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

    // MOVABLE D BUBBLE

    private void showBubble() {
        TextView icon = makeText("D", 25, Color.WHITE);

        icon.setBackground(
                makeBackground(Color.rgb(10, 10, 16))
        );

        bubble = icon;

        bubbleParams = makeParams(58, 58);
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
                    }

                    bubbleParams.x =
                            Math.max(0, startX + (int) dx);

                    bubbleParams.y =
                            Math.max(0, startY + (int) dy);

                    try {
                        windowManager.updateViewLayout(
                                bubble,
                                bubbleParams
                        );
                    } catch (Exception ignored) {
                    }

                    return true;
                }

                if (event.getAction() == MotionEvent.ACTION_UP) {
                    if (!moved) {
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
        String current = prefs.getString("trading_mode", "TIMED");
        String next = "SCALPING".equalsIgnoreCase(current) ? "TIMED" : "SCALPING";
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
        if (expanded || bubble == null) {
            return;
        }

        expanded = true;

        int oldX = bubbleParams.x;
        int oldY = bubbleParams.y;

        try {
            windowManager.removeView(bubble);
        } catch (Exception ignored) {
        }

        bubble = null;

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(12, 10, 12, 10);
        layout.setBackground(
                makeBackground(Color.rgb(12, 12, 18))
        );

        layout.addView(
                makeText(
                        "DIABLO SCANNER",
                        16,
                        Color.rgb(255, 45, 65)
                )
        );

        signalView = makeText("WAIT", 27, Color.YELLOW);
        layout.addView(signalView);

        detailsView = makeText(
                "Waiting for a chart analysis...",
                12,
                Color.LTGRAY
        );
        layout.addView(detailsView);

        tradeStatusView = makeText(
                "Trade tracker ready.",
                13,
                Color.WHITE
        );
        layout.addView(tradeStatusView);

        statsView = makeText("", 12, Color.LTGRAY);
        layout.addView(statsView);

        modeButton = addButton(
                layout,
                currentModeLabel(),
                Color.rgb(45, 45, 65),
                v -> toggleTradingMode()
        );

        addButton(
                layout,
                "◢ SCAN CURRENT CHART ONCE",
                Color.rgb(120, 48, 30),
                v -> requestOneScan()
        );

        addButton(
                layout,
                autoScanButtonLabel(),
                Color.rgb(55, 55, 90),
                v -> toggleAutoScan()
        );

        addButton(
                layout,
                "I TOOK TRADE",
                Color.rgb(30, 100, 55),
                v -> startTrade()
        );

        addButton(
                layout,
                "MARK WIN",
                Color.rgb(20, 120, 65),
                v -> saveTradeResult("WIN")
        );

        addButton(
                layout,
                "MARK LOSS",
                Color.rgb(150, 35, 45),
                v -> saveTradeResult("LOSS")
        );

        addButton(
                layout,
                "MARK UNKNOWN",
                Color.rgb(65, 65, 75),
                v -> saveTradeResult("UNKNOWN")
        );

        addButton(
                layout,
                "REFRESH NOW",
                Color.rgb(35, 35, 45),
                v -> refreshLatestResult()
        );

        addButton(
                layout,
                "MINIMISE",
                Color.rgb(35, 35, 45),
                v -> collapsePanel()
        );

        addButton(
                layout,
                "CLOSE BUBBLE",
                Color.rgb(60, 25, 30),
                v -> stopSelf()
        );

        // Keep the expanded panel within the visible screen. Its contents
        // scroll vertically so every control remains reachable on small phones.
        ScrollView scrollContainer = new ScrollView(this);
        scrollContainer.setFillViewport(false);
        scrollContainer.setVerticalScrollBarEnabled(true);
        scrollContainer.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scrollContainer.addView(
                layout,
                new ScrollView.LayoutParams(
                        ScrollView.LayoutParams.MATCH_PARENT,
                        ScrollView.LayoutParams.WRAP_CONTENT
                )
        );

        panel = scrollContainer;

        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int maxPanelHeight = Math.max(300, (int) (screenHeight * 0.72f));

        panelParams = makeParams(
                290,
                maxPanelHeight
        );

        panelParams.gravity = Gravity.TOP | Gravity.START;
        panelParams.x = oldX;
        panelParams.y = oldY;

        try {
            windowManager.addView(panel, panelParams);
        } catch (Exception error) {
            panel = null;
            expanded = false;
            signalView = null;
            detailsView = null;
            tradeStatusView = null;
            statsView = null;
            modeButton = null;
            showBubble();
            return;
        }

        refreshLatestResult();
        updateTradeStatus();
        updateStats();
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
                .putLong("scan_started_at", System.currentTimeMillis())
                .putString("scan_message", "Capturing the visible chart for one analysis...")
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
            signalView.setText(currentRay + (processingAI ? " AI BUSY" : " SCANNING"));
            signalView.setTextColor(Color.CYAN);
            String progress = elapsedSeconds < 10L
                    ? "Elapsed: " + elapsedSeconds + "s / 10s target"
                    : "DELAYED: " + elapsedSeconds + "s elapsed (10s target exceeded)";
            String stageMessage = statusPrefs.getString("scan_message", "Preparing chart analysis...");
            detailsView.setText(
                    "BLACK-RAY SCAN ACTIVE\n" +
                    (processingAI ? "AI PROCESSING • AI BUSY\n" : "Capturing visible chart...\n") +
                    progress + "\n" + stageMessage + "\n" +
                    (elapsedSeconds >= 90L
                            ? "ANALYSIS TIMEOUT WARNING: model is taking too long."
                            : elapsedSeconds >= 10L
                            ? "Still working. Keep the chart open; do not tap again."
                            : "Please wait. One scan only; no repeated signals.")
            );
            return;
        }

        if ("AI_ERROR".equals(scanStatus)) {
            signalView.setText("AI ERROR");
            signalView.setTextColor(Color.RED);
            detailsView.setText("AI ERROR • Analysis failed\n" +
                    statusPrefs.getString("scan_message", "The AI could not complete this scan.") +
                    "\nCheck AI model/engine and storage, then tap D to retry.");
            return;
        }

        if ("NEEDS_CAPTURE".equals(scanStatus)) {
            signalView.setText("WAIT");
            signalView.setTextColor(Color.YELLOW);
            detailsView.setText(statusPrefs.getString(
                    "scan_message",
                    "Start live screen capture from the dashboard first."
            ));
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

        message.append(
                "\n\nNo signal guarantees a winning trade. DIABLO does not place trades."
        );

        detailsView.setText(message.toString());
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
                tradeStatusView.setText(
                        "Finish the current trade record first."
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
