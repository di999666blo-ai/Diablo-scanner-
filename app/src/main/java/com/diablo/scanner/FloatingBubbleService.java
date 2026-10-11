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

public class FloatingBubbleService extends Service {

    private WindowManager windowManager;

    private WindowManager.LayoutParams bubbleParams;
    private WindowManager.LayoutParams panelParams;

    private View bubble;
    private View panel;

    private TextView signalView;
    private TextView detailsView;

    private boolean expanded = false;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private final Runnable refreshRunnable = new Runnable() {

        @Override
        public void run() {

            if (expanded && panel != null) {
                refreshLatestResult();
            }

            handler.postDelayed(this, 3000L);
        }
    };

    @Override
    public void onCreate() {

        super.onCreate();

        windowManager = (WindowManager)
                getSystemService(WINDOW_SERVICE);

        showBubble();

        handler.postDelayed(
                refreshRunnable,
                3000L
        );
    }

    // ================================================
    // LOOK AND FEEL
    // ================================================

    private GradientDrawable makeBackground(int color) {

        GradientDrawable shape = new GradientDrawable();

        shape.setColor(color);
        shape.setCornerRadius(24);
        shape.setStroke(
                2,
                Color.rgb(255, 35, 65)
        );

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

        view.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
        );

        view.setPadding(
                12,
                10,
                12,
                10
        );

        return view;
    }

    private int getOverlayType() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            return WindowManager.LayoutParams
                    .TYPE_APPLICATION_OVERLAY;
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

    // ================================================
    // SHOW MOVABLE BUBBLE
    // ================================================

    private void showBubble() {

        TextView icon = makeText(
                "D",
                25,
                Color.WHITE
        );

        icon.setBackground(
                makeBackground(
                        Color.rgb(10, 10, 16)
                )
        );

        bubble = icon;

        bubbleParams = makeParams(
                58,
                58
        );

        bubbleParams.gravity =
                Gravity.TOP | Gravity.START;

        bubbleParams.x = 12;
        bubbleParams.y = 180;

        windowManager.addView(
                bubble,
                bubbleParams
        );

        icon.setOnTouchListener(
                new View.OnTouchListener() {

                    float downX;
                    float downY;

                    int startX;
                    int startY;

                    boolean moved;

                    @Override
                    public boolean onTouch(
                            View view,
                            MotionEvent event
                    ) {

                        if (
                                event.getAction() ==
                                MotionEvent.ACTION_DOWN
                        ) {

                            downX = event.getRawX();
                            downY = event.getRawY();

                            startX = bubbleParams.x;
                            startY = bubbleParams.y;

                            moved = false;

                            return true;
                        }

                        if (
                                event.getAction() ==
                                MotionEvent.ACTION_MOVE
                        ) {

                            float dx =
                                    event.getRawX() - downX;

                            float dy =
                                    event.getRawY() - downY;

                            if (
                                    Math.abs(dx) > 8 ||
                                    Math.abs(dy) > 8
                            ) {

                                moved = true;
                            }

                            bubbleParams.x =
                                    Math.max(
                                            0,
                                            startX + (int) dx
                                    );

                            bubbleParams.y =
                                    Math.max(
                                            0,
                                            startY + (int) dy
                                    );

                            try {

                                windowManager.updateViewLayout(
                                        bubble,
                                        bubbleParams
                                );

                            } catch (Exception ignored) {
                            }

                            return true;
                        }

                        if (
                                event.getAction() ==
                                MotionEvent.ACTION_UP
                        ) {

                            if (!moved) {
                                expandPanel();
                            }

                            return true;
                        }

                        return false;
                    }
                }
        );
    }

    // ================================================
    // EXPAND BUBBLE
    // ================================================

    private void expandPanel() {

        if (expanded || bubble == null) {
            return;
        }

        expanded = true;

        int oldX = bubbleParams.x;
        int oldY = bubbleParams.y;

        windowManager.removeView(bubble);
        bubble = null;

        LinearLayout layout = new LinearLayout(this);

        layout.setOrientation(
                LinearLayout.VERTICAL
        );

        layout.setPadding(
                12,
                10,
                12,
                10
        );

        layout.setBackground(
                makeBackground(
                        Color.rgb(12, 12, 18)
                )
        );

        TextView title = makeText(
                "DIABLO SCANNER",
                16,
                Color.rgb(255, 45, 65)
        );

        layout.addView(title);

        signalView = makeText(
                "WAIT",
                27,
                Color.YELLOW
        );

        layout.addView(signalView);

        detailsView = makeText(
                "Loading latest result...",
                12,
                Color.LTGRAY
        );

        layout.addView(detailsView);

        TextView refresh = makeText(
                "REFRESH NOW",
                13,
                Color.WHITE
        );

        refresh.setBackground(
                makeBackground(
                        Color.rgb(35, 35, 45)
                )
        );

        layout.addView(refresh);

        refresh.setOnClickListener(
                v -> refreshLatestResult()
        );

        TextView minimize = makeText(
                "MINIMISE",
                13,
                Color.WHITE
        );

        minimize.setBackground(
                makeBackground(
                        Color.rgb(35, 35, 45)
                )
        );

        layout.addView(minimize);

        minimize.setOnClickListener(
                v -> collapsePanel()
        );

        TextView close = makeText(
                "CLOSE BUBBLE",
                12,
                Color.rgb(255, 100, 110)
        );

        layout.addView(close);

        close.setOnClickListener(
                v -> stopSelf()
        );

        panel = layout;

        panelParams = makeParams(
                270,
                WindowManager.LayoutParams.WRAP_CONTENT
        );

        panelParams.gravity =
                Gravity.TOP | Gravity.START;

        panelParams.x = oldX;
        panelParams.y = oldY;

        windowManager.addView(
                panel,
                panelParams
        );

        refreshLatestResult();
    }

    // ================================================
    // READ LATEST RESULT FROM APP HISTORY
    // ================================================

    private void refreshLatestResult() {

        if (
                signalView == null ||
                detailsView == null
        ) {
            return;
        }

        String signal = "WAIT";
        String time = "No saved result yet";
        String source = "No analysis recorded";
        int score = 0;

        try {

            SharedPreferences prefs =
                    getSharedPreferences(
                            "diablo_scanner",
                            MODE_PRIVATE
                    );

            JSONArray history = new JSONArray(
                    prefs.getString(
                            "history",
                            "[]"
                    )
            );

            if (history.length() > 0) {

                JSONObject latest =
                        history.getJSONObject(0);

                signal = latest.optString(
                        "signal",
                        "WAIT"
                );

                time = latest.optString(
                        "time",
                        "Unknown time"
                );

                score = latest.optInt(
                        "score",
                        0
                );

                String sourceValue = latest.optString(
                        "source",
                        "SAVED_SCREENSHOT"
                );

                if ("LIVE_SCREEN".equals(sourceValue)) {

                    source = "Source: Live screen capture";

                } else {

                    source = "Source: Saved screenshot";
                }
            }

        } catch (Exception ignored) {

            source = "Could not read saved history";
        }

        signalView.setText(signal);

        if (signal.contains("UP")) {

            signalView.setTextColor(
                    Color.rgb(50, 230, 120)
            );

        } else if (signal.contains("DOWN")) {

            signalView.setTextColor(
                    Color.rgb(255, 45, 65)
            );

        } else {

            signalView.setTextColor(
                    Color.YELLOW
            );
        }

        detailsView.setText(
                source +
                "\nLast result: " + time +
                "\nVisual heuristic score: " + score + "%" +
                "\n\nUpdates while this panel is open." +
                "\nThis is not a guaranteed trading signal."
        );
    }

    // ================================================
    // MINIMISE PANEL
    // ================================================

    private void collapsePanel() {

        if (panel != null) {

            windowManager.removeView(panel);
            panel = null;
        }

        signalView = null;
        detailsView = null;

        expanded = false;

        showBubble();
    }

    // ================================================
    // STOP SERVICE CLEANLY
    // ================================================

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

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
