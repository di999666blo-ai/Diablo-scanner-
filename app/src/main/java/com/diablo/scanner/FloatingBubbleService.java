package com.diablo.scanner;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
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
    private View bubble;
    private View panel;
    private boolean expanded = false;

    private GradientDrawable background(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(24);
        shape.setStroke(2, Color.rgb(255, 35, 65));
        return shape;
    }

    private TextView makeText(
            String text, int size, int color) {

        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(12, 10, 12, 10);
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
            int width, int height) {

        return new WindowManager.LayoutParams(
                width,
                height,
                getOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
    }

    @Override
    public void onCreate() {
        super.onCreate();

        windowManager = (WindowManager)
                getSystemService(WINDOW_SERVICE);

        showBubble();
    }

    private void showBubble() {

        TextView icon = makeText(
                "D", 25, Color.WHITE);

        icon.setBackground(
                background(Color.rgb(10, 10, 16)));

        bubble = icon;
        bubbleParams = makeParams(58, 58);

        bubbleParams.gravity =
                Gravity.TOP | Gravity.START;

        bubbleParams.x = 12;
        bubbleParams.y = 180;

        windowManager.addView(bubble, bubbleParams);

        icon.setOnTouchListener(new View.OnTouchListener() {

            float downX, downY;
            int startX, startY;
            boolean moved;

            @Override
            public boolean onTouch(View view,
                                   MotionEvent event) {

                if (event.getAction()
                        == MotionEvent.ACTION_DOWN) {

                    downX = event.getRawX();
                    downY = event.getRawY();

                    startX = bubbleParams.x;
                    startY = bubbleParams.y;
                    moved = false;

                    return true;
                }

                if (event.getAction()
                        == MotionEvent.ACTION_MOVE) {

                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;

                    if (Math.abs(dx) > 8
                            || Math.abs(dy) > 8) {
                        moved = true;
                    }

                    bubbleParams.x =
                            Math.max(0, startX + (int) dx);

                    bubbleParams.y =
                            Math.max(0, startY + (int) dy);

                    windowManager.updateViewLayout(
                            bubble, bubbleParams);

                    return true;
                }

                if (event.getAction()
                        == MotionEvent.ACTION_UP) {

                    if (!moved) {
                        expandPanel();
                    }

                    return true;
                }

                return false;
            }
        });
    }

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
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(12, 10, 12, 10);
        layout.setBackground(
                background(Color.rgb(12, 12, 18)));

        layout.addView(makeText(
                "DIABLO SCANNER", 16,
                Color.rgb(255, 45, 65)));

        String signal = "WAIT";
        String time = "No saved result yet";

        try {
            JSONArray history = new JSONArray(
                    getSharedPreferences(
                            "diablo_scanner", MODE_PRIVATE)
                            .getString("history", "[]"));

            if (history.length() > 0) {
                JSONObject latest =
                        history.getJSONObject(0);

                signal = latest.optString(
                        "signal", "WAIT");

                time = latest.optString(
                        "time", "Saved result");
            }
        } catch (Exception ignored) {
        }

        int signalColor = Color.YELLOW;

        if (signal.contains("UP")) {
            signalColor = Color.rgb(50, 230, 120);
        } else if (signal.contains("DOWN")) {
            signalColor = Color.rgb(255, 45, 65);
        }

        layout.addView(makeText(
                signal, 27, signalColor));

        layout.addView(makeText(
                "LAST SAVED RESULT\n" + time
                + "\n\nLive screen scanning is not "
                + "connected yet.",
                12, Color.LTGRAY));

        TextView minimize = makeText(
                "MINIMISE", 13, Color.WHITE);

        minimize.setBackground(
                background(Color.rgb(35, 35, 45)));

        layout.addView(minimize);
        minimize.setOnClickListener(v -> collapsePanel());

        TextView close = makeText(
                "CLOSE BUBBLE", 12,
                Color.rgb(255, 100, 110));

        layout.addView(close);
        close.setOnClickListener(v -> stopSelf());

        panel = layout;

        WindowManager.LayoutParams panelParams =
                makeParams(260,
                        WindowManager.LayoutParams.WRAP_CONTENT);

        panelParams.gravity =
                Gravity.TOP | Gravity.START;

        panelParams.x = oldX;
        panelParams.y = oldY;

        windowManager.addView(panel, panelParams);
    }

    private void collapsePanel() {

        if (panel != null) {
            windowManager.removeView(panel);
            panel = null;
        }

        expanded = false;
        showBubble();
    }

    @Override
    public void onDestroy() {

        if (bubble != null) {
            windowManager.removeView(bubble);
        }

        if (panel != null) {
            windowManager.removeView(panel);
        }

        bubble = null;
        panel = null;

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
