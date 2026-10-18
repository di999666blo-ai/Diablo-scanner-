package com.diablo.scanner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int PICK_CHART = 4101;
    private static final int REQUEST_SCREEN_CAPTURE = 4102;

    private static final String PREFS = "diablo_scanner";
    private static final String HISTORY = "history";
    private static final String THRESHOLD = "threshold";

    public static final String TRADE_DURATION_MS = "trade_duration_ms";
    public static final long DEFAULT_TRADE_DURATION_MS = 60000L;

    private LinearLayout root;
    private TextView signalText;
    private TextView statusText;
    private TextView countText;
    private SharedPreferences prefs;

    private final int red = Color.rgb(255, 45, 65);
    private final int background = Color.rgb(12, 12, 16);
    private final int panelColor = Color.rgb(30, 30, 38);
    private final int white = Color.WHITE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(Color.BLACK);

        buildDashboard();
        refreshCount();
        refreshLatestResult();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (prefs != null) {
            refreshCount();
            refreshLatestResult();
            refreshAIModelStatus();
            refreshControlSummary();
        }
    }

    private void buildDashboard() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(background);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 24, 20, 24);
        root.setBackgroundColor(background);

        scroll.addView(root);
        setContentView(scroll);

        TextView logo = new TextView(this);
        logo.setText("D");
        logo.setTextSize(62);
        logo.setTextColor(red);
        logo.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        logo.setGravity(Gravity.CENTER);
        root.addView(logo);

        TextView title = new TextView(this);
        title.setText("DIABLO SCANNER");
        title.setTextSize(25);
        title.setTextColor(white);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("2026 FUTURISTIC-RUSTIC • LOCAL VISION CORE");
        subtitle.setTextSize(11);
        subtitle.setTextColor(Color.rgb(215, 164, 91));
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 4, 0, 22);
        root.addView(subtitle);

        addSectionTitle("LATEST RESULT");

        signalText = new TextView(this);
        signalText.setText("WAIT");
        signalText.setTextSize(38);
        signalText.setTextColor(Color.YELLOW);
        signalText.setGravity(Gravity.CENTER);
        signalText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        signalText.setPadding(0, 18, 0, 8);
        root.addView(signalText);

        statusText = new TextView(this);
        statusText.setText("Choose a chart screenshot to begin.");
        statusText.setTextSize(14);
        statusText.setTextColor(white);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(8, 8, 8, 18);
        root.addView(statusText);

        countText = new TextView(this);
        countText.setTextColor(Color.LTGRAY);
        countText.setTextSize(12);
        countText.setGravity(Gravity.CENTER);
        countText.setPadding(0, 0, 0, 10);
        root.addView(countText);

        TextView controlSummary = new TextView(this);
        controlSummary.setTag("control_summary");
        controlSummary.setTextColor(Color.rgb(235, 205, 160));
        controlSummary.setTextSize(12);
        controlSummary.setGravity(Gravity.CENTER);
        controlSummary.setPadding(12, 12, 12, 12);
        controlSummary.setBackgroundColor(Color.rgb(38, 32, 27));
        root.addView(controlSummary);
        refreshControlSummary();

        addSectionTitle("CHART LAB");
        addButton("ANALYSE SAVED CHART", v -> chooseChart());

        addSectionTitle("LIVE SCANNER");
        addButton("START LIVE SCREEN CAPTURE", v -> requestScreenCapture());
        addButton("STOP SCREEN CAPTURE", v -> stopScreenCapture());
        addButton("START FLOATING BUBBLE", v -> startFloatingBubble());

        addSectionTitle("TRADE TRACKING");
        addButton("SIGNAL HISTORY", v -> showHistory());
        addButton("CLEAR HISTORY", v -> clearHistory());

        addSectionTitle("SETTINGS & AI CORE");
        addButton("SETTINGS / DURATION / TIMEFRAME / MODE", v -> showSettings());
        addButton("SET UP AI MODEL FILES", v -> showAIModelSetup());

        TextView instructions = new TextView(this);

        instructions.setText(
                "HOW TO USE\n\n" +
                "1. Open your trading chart.\n" +
                "2. Tap START LIVE SCREEN CAPTURE.\n" +
                "3. Approve Android's screen-capture request.\n" +
                "4. Tap START FLOATING BUBBLE.\n" +
                "5. Set your preferred trade duration in Settings.\n" +
                "6. Use STOP SCREEN CAPTURE when finished.\n\n" +
                "You can also analyse a saved chart screenshot."
        );

        instructions.setTextColor(Color.LTGRAY);
        instructions.setTextSize(13);
        instructions.setPadding(8, 24, 8, 12);
        root.addView(instructions);

        TextView aiStatus = new TextView(this);
        aiStatus.setTextColor(Color.LTGRAY);
        aiStatus.setTextSize(12);
        aiStatus.setPadding(8, 8, 8, 12);
        aiStatus.setTag("ai_model_status");
        root.addView(aiStatus);

        refreshAIModelStatus();

        TextView warning = new TextView(this);

        warning.setText(
                "TRADING RISK WARNING\n\n" +
                "DIABLO SCANNER includes basic visual chart analysis and " +
                "source code for a local vision AI engine. Actual AI operation " +
                "must be verified on the phone.\n\n" +
                "AI confidence is not a verified probability of winning. " +
                "No signal guarantees profit. The app does not place trades."
        );

        warning.setTextColor(Color.rgb(255, 190, 120));
        warning.setTextSize(12);
        warning.setGravity(Gravity.CENTER);
        warning.setPadding(8, 20, 8, 8);
        root.addView(warning);
    }

    private void refreshControlSummary() {
        if (root == null || prefs == null) {
            return;
        }

        View view = root.findViewWithTag("control_summary");
        if (!(view instanceof TextView)) {
            return;
        }

        String mode = prefs.getString("trading_mode", "TIMED");
        String modeLabel = "SCALPING".equals(mode)
                ? "MODE 2 • SCALPING SETUP"
                : "MODE 1 • TIMED SIGNAL";

        String timeframe = prefs.getString("chart_timeframe", "1m");
        long duration = prefs.getLong(TRADE_DURATION_MS, DEFAULT_TRADE_DURATION_MS);

        ((TextView) view).setText(
                "ACTIVE CONFIGURATION\n" +
                modeLabel +
                "\nChart timeframe: " + timeframe +
                "\nTrade duration: " + formatDuration(duration)
        );
    }

    private void addSectionTitle(String text) {
        TextView heading = new TextView(this);

        heading.setText(text);
        heading.setTextSize(13);
        heading.setTextColor(Color.rgb(215, 164, 91));
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);
        heading.setPadding(0, 12, 0, 4);

        root.addView(heading);
    }

    private void addButton(
            String text,
            View.OnClickListener listener
    ) {
        Button button = new Button(this);

        button.setText(text);
        button.setTextColor(white);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setBackgroundTintList(
                ColorStateList.valueOf(panelColor)
        );

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        params.setMargins(0, 6, 0, 6);

        root.addView(button, params);
        button.setOnClickListener(listener);
    }

    // AI MODEL FILE SETUP

    private void showAIModelSetup() {
        String[] options = {
                "Choose SmolVLM text model",
                "Choose SmolVLM image projector",
                "Check model file status"
        };

        new AlertDialog.Builder(this)
                .setTitle("DIABLO AI MODEL SETUP")
                .setItems(options, (dialog, which) -> {

                    if (which == 0) {
                        AIModelSetup.chooseTextModel(this);

                    } else if (which == 1) {
                        AIModelSetup.chooseImageModel(this);

                    } else {
                        boolean ready =
                                AIModelSetup.areBothModelsSelected(this);

                        String message;

                        if (ready) {
                            message =
                                    "Both model files are available to the app, either bundled in this APK or selected from storage.\n\n" +
                                    "The local vision engine still needs a successful build and on-phone inference test.";
                        } else {
                            message =
                                    "One or both model files are missing.\n\n" +
                                    "Select the SmolVLM text model and its matching " +
                                    "image projector using the two setup options.";
                        }

                        new AlertDialog.Builder(this)
                                .setTitle("AI model status")
                                .setMessage(message)
                                .setPositiveButton("OK", null)
                                .show();
                    }
                })
                .setNegativeButton("CLOSE", null)
                .show();
    }

    private void refreshAIModelStatus() {
        if (root == null) {
            return;
        }

        View view = root.findViewWithTag("ai_model_status");

        if (!(view instanceof TextView)) {
            return;
        }

        TextView label = (TextView) view;

        if (AIModelSetup.areBothModelsSelected(this)) {
            label.setText(
                    "AI MODEL FILES: AVAILABLE\n" +
                    "SmolVLM brain + image projector are bundled or selected. " +
                    "The first analysis prepares them in app storage; actual inference still needs phone testing."
            );
        } else {
            label.setText(
                    "AI MODEL FILES: NOT SET UP\n" +
                    "Tap SET UP AI MODEL FILES to select both GGUF files."
            );
        }
    }

    // LIVE SCREEN CAPTURE

    private void requestScreenCapture() {
        try {
            MediaProjectionManager manager =
                    (MediaProjectionManager)
                            getSystemService(MEDIA_PROJECTION_SERVICE);

            if (manager == null) {
                Toast.makeText(
                        this,
                        "Screen capture is unavailable.",
                        Toast.LENGTH_LONG
                ).show();
                return;
            }

            startActivityForResult(
                    manager.createScreenCaptureIntent(),
                    REQUEST_SCREEN_CAPTURE
            );

        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "Could not request screen capture permission.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void stopScreenCapture() {
        try {
            Intent intent = new Intent(
                    this,
                    ScreenCaptureService.class
            );

            intent.setAction(
                    ScreenCaptureService.ACTION_STOP_CAPTURE
            );

            startService(intent);

            Toast.makeText(
                    this,
                    "Stop request sent.",
                    Toast.LENGTH_SHORT
            ).show();

        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "Could not stop capture. Check the capture service.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void startScreenCaptureService(
            int resultCode,
            Intent resultData
    ) {
        Intent serviceIntent = new Intent(
                this,
                ScreenCaptureService.class
        );

        serviceIntent.putExtra(
                ScreenCaptureService.EXTRA_RESULT_CODE,
                resultCode
        );

        serviceIntent.putExtra(
                ScreenCaptureService.EXTRA_RESULT_DATA,
                resultData
        );

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }

            Toast.makeText(
                    this,
                    "Screen capture start requested.",
                    Toast.LENGTH_LONG
            ).show();

        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "Could not start capture. Check the service and manifest.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    // FLOATING BUBBLE

    private void startFloatingBubble() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {

            try {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())
                );

                startActivity(intent);

                Toast.makeText(
                        this,
                        "Allow display over other apps, return here, " +
                        "then tap START FLOATING BUBBLE again.",
                        Toast.LENGTH_LONG
                ).show();

            } catch (Exception e) {
                Toast.makeText(
                        this,
                        "Allow DIABLO SCANNER to display over other apps.",
                        Toast.LENGTH_LONG
                ).show();
            }

            return;
        }

        try {
            Intent serviceIntent = new Intent(
                    this,
                    FloatingBubbleService.class
            );

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }

            Toast.makeText(
                    this,
                    "Floating bubble start requested.",
                    Toast.LENGTH_SHORT
            ).show();

        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "Could not start the floating bubble.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    // RESULT HANDLING

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {
        super.onActivityResult(requestCode, resultCode, data);

        if (AIModelSetup.handleResult(
                this,
                requestCode,
                resultCode,
                data
        )) {
            refreshAIModelStatus();
            return;
        }

        if (requestCode == REQUEST_SCREEN_CAPTURE) {
            if (resultCode != RESULT_OK || data == null) {
                Toast.makeText(
                        this,
                        "Screen capture permission was cancelled.",
                        Toast.LENGTH_LONG
                ).show();
                return;
            }

            startScreenCaptureService(resultCode, data);
            return;
        }

        if (requestCode != PICK_CHART
                || resultCode != RESULT_OK
                || data == null
                || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();

        try (InputStream input =
                     getContentResolver().openInputStream(uri)) {

            if (input == null) {
                showImageError();
                return;
            }

            Bitmap bitmap = BitmapFactory.decodeStream(input);

            if (bitmap == null) {
                showImageError();
                return;
            }

            try {
                analyseBitmap(bitmap);
            } finally {
                bitmap.recycle();
            }

        } catch (Exception e) {
            showImageError();
        }
    }

    private void chooseChart() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);

        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");

        try {
            startActivityForResult(intent, PICK_CHART);

        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "Could not open the image picker.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void showImageError() {
        Toast.makeText(
                this,
                "Unable to read this image. Choose another screenshot.",
                Toast.LENGTH_LONG
        ).show();
    }

    // SAVED CHART ANALYSIS — LOCAL VISION AI FIRST

    private void analyseBitmap(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            Toast.makeText(this, "Unable to analyse this image.", Toast.LENGTH_LONG).show();
            return;
        }

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        if (width < 2 || height < 2) {
            Toast.makeText(this, "Image is too small to analyse.", Toast.LENGTH_LONG).show();
            return;
        }

        if (!LocalVisionAI.modelsReady(this)) {
            analyseBitmapHeuristicFallback(bitmap);
            return;
        }

        final Bitmap aiBitmap;
        try {
            aiBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        } catch (Exception copyError) {
            analyseBitmapHeuristicFallback(bitmap);
            return;
        }

        if (aiBitmap == null) {
            analyseBitmapHeuristicFallback(bitmap);
            return;
        }

        signalText.setText("ANALYSING…");
        signalText.setTextColor(Color.CYAN);
        statusText.setText("SmolVLM is analysing this saved chart. Please wait.");

        Thread aiThread = new Thread(() -> {
            LocalVisionAI.Result aiResult;
            try {
                aiResult = LocalVisionAI.analyze(getApplicationContext(), aiBitmap);
            } catch (Exception error) {
                aiResult = new LocalVisionAI.Result(
                        "WAIT",
                        "Saved-chart AI analysis failed: " +
                        error.getClass().getSimpleName()
                );
            } finally {
                if (!aiBitmap.isRecycled()) {
                    aiBitmap.recycle();
                }
            }

            final LocalVisionAI.Result finalResult = aiResult;
            runOnUiThread(() -> {
                if (finalResult == null) {
                    signalText.setText("WAIT");
                    signalText.setTextColor(Color.YELLOW);
                    statusText.setText("AI returned no result. Please try another chart.");
                    return;
                }

                String direction = finalResult.signal == null
                        ? "WAIT"
                        : finalResult.signal.trim().toUpperCase(java.util.Locale.ROOT);

                String displaySignal;
                int displayColour;

                if ("UP".equals(direction)) {
                    displaySignal = "UP BIAS";
                    displayColour = Color.rgb(50, 220, 120);
                } else if ("DOWN".equals(direction)) {
                    displaySignal = "DOWN BIAS";
                    displayColour = red;
                } else {
                    displaySignal = "WAIT";
                    displayColour = Color.YELLOW;
                }

                String details = finalResult.details == null
                        ? "No explanation returned."
                        : finalResult.details;

                int confidence = 0;
                for (String line : details.split("\\r?\\n")) {
                    String cleaned = line.trim().toUpperCase(java.util.Locale.ROOT);
                    if (!cleaned.startsWith("CONFIDENCE:")) {
                        continue;
                    }

                    String value = cleaned.substring("CONFIDENCE:".length()).trim();
                    StringBuilder digits = new StringBuilder();
                    for (int i = 0; i < value.length(); i++) {
                        char c = value.charAt(i);
                        if (Character.isDigit(c)) {
                            digits.append(c);
                        } else if (digits.length() > 0) {
                            break;
                        }
                    }

                    try {
                        confidence = Math.max(0, Math.min(100,
                                Integer.parseInt(digits.toString())));
                    } catch (Exception ignored) {
                        confidence = 0;
                    }
                    break;
                }

                signalText.setText(displaySignal);
                signalText.setTextColor(displayColour);
                statusText.setText(
                        "SAVED CHART • LOCAL VISION AI\n" +
                        details +
                        "\n\nExperimental confidence: " + confidence + "%" +
                        "\nConfidence is not a verified win probability."
                );

                saveHistory(displaySignal, confidence, 0, 0, "SAVED_AI");
                refreshCount();
            });
        }, "DiabloSavedChartAI");

        aiThread.start();
    }

    // Colour-count fallback used only when the local vision model is unavailable.
    private void analyseBitmapHeuristicFallback(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            Toast.makeText(this, "Unable to analyse this image.", Toast.LENGTH_LONG).show();
            return;
        }

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        if (width < 2 || height < 2) {
            Toast.makeText(this, "Image is too small to analyse.", Toast.LENGTH_LONG).show();
            return;
        }

        int step = Math.max(1, Math.max(width, height) / 500);
        long green = 0;
        long redPixels = 0;
        long checked = 0;

        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                int pixel = bitmap.getPixel(x, y);
                int r = Color.red(pixel);
                int g = Color.green(pixel);
                int b = Color.blue(pixel);
                checked++;

                int brightness = Math.max(r, Math.max(g, b));
                if (brightness < 70) {
                    continue;
                }

                boolean isGreen = g >= 90 && g > r * 1.20 && g > b * 1.08;
                boolean isRed = r >= 90 && r > g * 1.20 && r > b * 1.08;

                if (isGreen) {
                    green++;
                } else if (isRed) {
                    redPixels++;
                }
            }
        }

        long total = green + redPixels;
        double difference = total == 0 ? 0.0 : Math.abs(green - redPixels) / (double) total;
        double threshold = prefs.getInt(THRESHOLD, 15) / 100.0;

        String signal;
        String detail;
        int colour;
        int score;

        if (total < 20) {
            signal = "WAIT";
            colour = Color.YELLOW;
            score = 0;
            detail = "Too few clearly coloured pixels were found. Choose a clearer chart screenshot.";
        } else if (difference < threshold) {
            signal = "WAIT";
            colour = Color.YELLOW;
            score = (int) Math.round(difference * 100);
            detail = "Red and green pixels are too balanced to show a clear visual bias.";
        } else if (green > redPixels) {
            signal = "UP BIAS";
            colour = Color.rgb(50, 220, 120);
            score = (int) Math.round(Math.min(95, 50 + difference * 45));
            detail = "More green-dominant than red-dominant pixels were detected. This is only a visual colour comparison.";
        } else {
            signal = "DOWN BIAS";
            colour = red;
            score = (int) Math.round(Math.min(95, 50 + difference * 45));
            detail = "More red-dominant than green-dominant pixels were detected. This is only a visual colour comparison.";
        }

        signalText.setText(signal);
        signalText.setTextColor(colour);
        statusText.setText(
                detail +
                "\nGreen pixels: " + green +
                "\nRed pixels: " + redPixels +
                "\nSampled pixels: " + checked +
                "\nVisual balance score: " + score + "%" +
                "\nThis is not a probability of profit."
        );

        saveHistory(signal, score, green, redPixels);
        refreshCount();
    }

    // SAVE HISTORY

    private void saveHistory(
            String signal,
            int score,
            long green,
            long redPixels
    ) {
        saveHistory(signal, score, green, redPixels, "SAVED_SCREENSHOT");
    }

    private void saveHistory(
            String signal,
            int score,
            long green,
            long redPixels,
            String source
    ) {
        try {
            JSONArray old = new JSONArray(
                    prefs.getString(HISTORY, "[]")
            );

            JSONArray updated = new JSONArray();
            JSONObject item = new JSONObject();

            item.put(
                    "time",
                    new SimpleDateFormat(
                            "yyyy-MM-dd HH:mm",
                            Locale.getDefault()
                    ).format(new Date())
            );

            item.put("signal", signal);
            item.put("score", score);
            item.put("green", green);
            item.put("red", redPixels);
            item.put("source", source);

            updated.put(item);

            for (int i = 0; i < old.length() && i < 49; i++) {
                updated.put(old.get(i));
            }

            prefs.edit()
                    .putString(HISTORY, updated.toString())
                    .apply();

        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "Could not save the result.",
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    private void refreshLatestResult() {
        if (signalText == null || statusText == null) {
            return;
        }

        try {
            JSONArray history = new JSONArray(
                    prefs.getString(HISTORY, "[]")
            );

            if (history.length() == 0) {
                signalText.setText("WAIT");
                signalText.setTextColor(Color.YELLOW);

                statusText.setText(
                        "No saved chart results yet.\n" +
                        "Choose a screenshot to begin."
                );

                return;
            }

            JSONObject latest = history.getJSONObject(0);

            String signal = latest.optString("signal", "WAIT");
            String time = latest.optString("time", "Unknown time");
            int score = latest.optInt("score", 0);

            signalText.setText(signal);

            if (signal.contains("UP")) {
                signalText.setTextColor(Color.rgb(50, 220, 120));
            } else if (signal.contains("DOWN")) {
                signalText.setTextColor(red);
            } else {
                signalText.setTextColor(Color.YELLOW);
            }

            String source = latest.optString("source", "SAVED_SCREENSHOT");
            if ("LIVE_AI".equals(source) || "SAVED_AI".equals(source)) {
                statusText.setText(
                        ("LIVE_AI".equals(source) ? "Latest live AI result" : "Latest saved-chart AI result") +
                        "\nTime: " + time +
                        "\nExperimental AI confidence estimate: " + score + "%" +
                        "\nThis is not a verified probability of winning."
                );
            } else {
                statusText.setText(
                        ("LIVE_SCREEN".equals(source) ? "Latest live visual fallback" : "Latest saved-chart visual fallback") +
                        "\nTime: " + time +
                        "\nVisual balance score: " + score +
                        "%\nThis is a colour comparison, not AI prediction."
                );
            }

        } catch (Exception e) {
            signalText.setText("WAIT");
            signalText.setTextColor(Color.YELLOW);

            statusText.setText(
                    "Could not load the latest saved result."
            );
        }
    }

    private void showHistory() {
        try {
            JSONArray history = new JSONArray(
                    prefs.getString(HISTORY, "[]")
            );

            if (history.length() == 0) {
                new AlertDialog.Builder(this)
                        .setTitle("Signal history")
                        .setMessage("No chart images analysed yet.")
                        .setPositiveButton("OK", null)
                        .show();

                return;
            }

            StringBuilder text = new StringBuilder();

            for (int i = 0; i < history.length(); i++) {
                JSONObject item = history.getJSONObject(i);

                String source = item.optString("source", "SAVED_SCREENSHOT");
                boolean aiResult = "LIVE_AI".equals(source) || "SAVED_AI".equals(source);
                text.append(item.optString("time", "Unknown time"))
                        .append(" — ")
                        .append(item.optString("signal", "WAIT"))
                        .append(" (")
                        .append(item.optInt("score", 0))
                        .append(aiResult ? "% experimental AI confidence)" : "% visual balance score)")
                        .append("\nSource: ")
                        .append("LIVE_AI".equals(source) ? "Live local AI" :
                                "SAVED_AI".equals(source) ? "Saved-chart local AI" :
                                "LIVE_SCREEN".equals(source) ? "Live visual fallback" :
                                "Saved-chart visual fallback")
                        .append("\n\n");
            }

            new AlertDialog.Builder(this)
                    .setTitle("Recent visual results")
                    .setMessage(text.toString())
                    .setPositiveButton("CLOSE", null)
                    .show();

        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "Could not load history.",
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    private void clearHistory() {
        new AlertDialog.Builder(this)
                .setTitle("Clear history?")
                .setMessage(
                        "This removes saved results from this phone."
                )
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("CLEAR", (dialog, which) -> {
                    prefs.edit().remove(HISTORY).apply();

                    refreshCount();
                    refreshLatestResult();

                    Toast.makeText(
                            this,
                            "History cleared.",
                            Toast.LENGTH_SHORT
                    ).show();
                })
                .show();
    }

    // SETTINGS

    private void showSettings() {
        String[] options = {
                "Set trade duration",
                "Set chart timeframe",
                "Select trading mode",
                "Set visual sensitivity"
        };

        new AlertDialog.Builder(this)
                .setTitle("DIABLO SETTINGS")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showTradeDurationSettings();
                    } else if (which == 1) {
                        showChartTimeframeSettings();
                    } else if (which == 2) {
                        showTradingModeSettings();
                    } else {
                        showSensitivitySettings();
                    }
                })
                .setNegativeButton("CLOSE", null)
                .show();
    }

    private void showChartTimeframeSettings() {
        String[] options = {"30 seconds", "1 minute", "2 minutes", "5 minutes"};
        String[] values = {"30s", "1m", "2m", "5m"};
        String current = prefs.getString("chart_timeframe", "1m");
        int selected = 1;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) selected = i;
        }

        new AlertDialog.Builder(this)
                .setTitle("Chart timeframe")
                .setSingleChoiceItems(options, selected, (dialog, which) -> {
                    prefs.edit().putString("chart_timeframe", values[which]).apply();
                    dialog.dismiss();
                    refreshControlSummary();
                    Toast.makeText(this, "Chart timeframe saved: " + options[which],
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void showTradingModeSettings() {
        String[] labels = {"Mode 1 — Timed Signal", "Mode 2 — Scalping Setup"};
        String[] values = {"TIMED", "SCALPING"};
        String current = prefs.getString("trading_mode", "TIMED");
        int selected = "SCALPING".equals(current) ? 1 : 0;

        new AlertDialog.Builder(this)
                .setTitle("Trading mode")
                .setMessage("Mode 1 gives a directional chart read. Mode 2 asks the AI for a visible-chart scalping setup. Entry/TP/SL are shown only when the chart makes them readable.")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    prefs.edit().putString("trading_mode", values[which]).apply();
                    dialog.dismiss();
                    refreshControlSummary();
                    Toast.makeText(this, "Trading mode saved: " + labels[which],
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void showTradeDurationSettings() {
        String[] options = {
                "5 seconds",
                "15 seconds",
                "30 seconds",
                "1 minute",
                "5 minutes",
                "Custom duration (seconds)"
        };

        long current = prefs.getLong(
                TRADE_DURATION_MS,
                DEFAULT_TRADE_DURATION_MS
        );

        int selected = current == 5000L ? 0
                : current == 15000L ? 1
                : current == 30000L ? 2
                : current == 60000L ? 3
                : current == 300000L ? 4
                : -1;

        new AlertDialog.Builder(this)
                .setTitle("Trade duration")
                .setMessage(
                        "Current: " + formatDuration(current) +
                        "\nChoose your preferred tracking duration."
                )
                .setSingleChoiceItems(
                        options,
                        selected,
                        (dialog, which) -> {

                            if (which == 5) {
                                dialog.dismiss();
                                showCustomTradeDuration();
                                return;
                            }

                            long duration = which == 0 ? 5000L
                                    : which == 1 ? 15000L
                                    : which == 2 ? 30000L
                                    : which == 3 ? 60000L
                                    : 300000L;

                            prefs.edit()
                                    .putLong(TRADE_DURATION_MS, duration)
                                    .apply();

                            dialog.dismiss();

                            refreshControlSummary();
                            Toast.makeText(
                                    this,
                                    "Trade duration saved: " +
                                    formatDuration(duration),
                                    Toast.LENGTH_LONG
                            ).show();
                        }
                )
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void showCustomTradeDuration() {
        EditText input = new EditText(this);

        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setHint("Enter seconds (1 to 3600)");

        new AlertDialog.Builder(this)
                .setTitle("Custom trade duration")
                .setMessage(
                        "Enter the duration in seconds, from 1 to 3600."
                )
                .setView(input)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("SAVE", (dialog, which) -> {

                    String raw = input.getText().toString().trim();

                    try {
                        long seconds = Long.parseLong(raw);

                        if (seconds < 1 || seconds > 3600) {
                            Toast.makeText(
                                    this,
                                    "Enter between 1 and 3600 seconds.",
                                    Toast.LENGTH_LONG
                            ).show();

                            return;
                        }

                        long duration = seconds * 1000L;

                        prefs.edit()
                                .putLong(TRADE_DURATION_MS, duration)
                                .apply();

                        refreshControlSummary();
                        Toast.makeText(
                                this,
                                "Trade duration saved: " +
                                formatDuration(duration),
                                Toast.LENGTH_LONG
                        ).show();

                    } catch (Exception e) {
                        Toast.makeText(
                                this,
                                "Enter a valid number of seconds.",
                                Toast.LENGTH_LONG
                        ).show();
                    }
                })
                .show();
    }

    private String formatDuration(long durationMs) {
        long seconds = Math.max(1L, durationMs / 1000L);

        if (seconds < 60L) {
            return seconds +
                    (seconds == 1L ? " second" : " seconds");
        }

        long minutes = seconds / 60L;
        long remainingSeconds = seconds % 60L;

        if (remainingSeconds == 0L) {
            return minutes +
                    (minutes == 1L ? " minute" : " minutes");
        }

        return minutes + "m " + remainingSeconds + "s";
    }

    private void showSensitivitySettings() {
        String[] options = {
                "Low sensitivity (10%)",
                "Normal sensitivity (15%)",
                "High sensitivity (25%)"
        };

        int current = prefs.getInt(THRESHOLD, 15);

        int selected = current == 10 ? 0
                : current == 25 ? 2
                : 1;

        new AlertDialog.Builder(this)
                .setTitle("Visual sensitivity")
                .setSingleChoiceItems(
                        options,
                        selected,
                        (dialog, which) -> {

                            int value = which == 0 ? 10
                                    : which == 2 ? 25
                                    : 15;

                            prefs.edit()
                                    .putInt(THRESHOLD, value)
                                    .apply();

                            dialog.dismiss();

                            Toast.makeText(
                                    this,
                                    "Sensitivity saved.",
                                    Toast.LENGTH_SHORT
                            ).show();
                        }
                )
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void refreshCount() {
        if (countText == null) {
            return;
        }

        try {
            JSONArray history = new JSONArray(
                    prefs.getString(HISTORY, "[]")
            );

            countText.setText(
                    "Saved results on this phone: " + history.length()
            );

        } catch (Exception e) {
            countText.setText(
                    "Saved results on this phone: 0"
            );
        }
    }
}
