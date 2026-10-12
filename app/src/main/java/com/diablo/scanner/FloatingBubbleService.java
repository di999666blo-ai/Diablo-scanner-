package com.diablo.scanner;

import android.app.Service;
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
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FloatingBubbleService extends Service {

    private static final String PREFS = "diablo_scanner";

    // AI results are saved in the same preferences as live scan results.
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

    private boolean expanded = false;
    private boolean tradeActive = false;
    private boolean awaitingResult = false;

    private long tradeStartMs = 0L;
    private long tradeDurationMs = DEFAULT_TRADE_DURATION_MS;

    private String tradeDirection = "WAIT";

    private final Handler handler =
            new Handler(Looper.getMainLooper());

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

        windowManager = (WindowManager)
                getSystemService(WINDOW_SERVICE);

        showBubble();

        handler.postDelayed(refreshRunnable, 1000L);
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
                        expandPanel();
                    }
                    return true;
                }

                return false;
            }
        });
    }

    private void addButton(
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

        panel = layout;

        panelParams = makeParams(
                290,
                WindowManager.LayoutParams.WRAP_CONTENT
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
            showBubble();
            return;
        }

        refreshLatestResult();
        updateTradeStatus();
        updateStats();
    }

    // READ LATEST RESULT

    private void refreshLatestResult() {
        if (signalView == null || detailsView == null) {
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

        // FIX: AI result fields are saved in diablo_scanner.
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

        if (isFreshAIResult(aiSignal, aiTime)) {
            signal = normaliseSignal(aiSignal);
            time = aiTime;
            source = "Source: Local AI chart analysis";
            explanation = aiDetails;
            score = Math.max(0, Math.min(100, aiScore));
            usingAI = true;
        }

        // Otherwise use the most recent non-AI history entry.
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

                    if ("LIVE_AI".equals(sourceValue)) {
                        continue;
                    }

                    signal = item.optString("signal", "WAIT");
                    time = item.optString("time", "Unknown time");
                    score = item.optInt("score", 0);

                    if ("LIVE_SCREEN".equals(sourceValue)) {
                        source = "Source: Live screen colour analysis";
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

        if ("UP".equals(signal)) {
            signalView.setTextColor(Color.rgb(50, 230, 120));
            tradeDirection = "UP";
        } else if ("DOWN".equals(signal)) {
            signalView.setTextColor(Color.rgb(255, 45, 65));
            tradeDirection = "DOWN";
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

            message.append(
                    "\n\nBasic visual analysis is not an AI prediction."
            );

            if (aiSignal == null || aiSignal.trim().isEmpty()) {
                message.append(
                        "\nAI result unavailable. Select both model files and check the engine."
                );
            } else {
                message.append(
                        "\nThe last AI result is old or not yet available. Waiting for a fresh result."
                );
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
