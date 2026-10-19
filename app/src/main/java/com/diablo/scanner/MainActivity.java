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
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
    private final int panelColor = Color.rgb(31, 29, 27);
    private final int amber = Color.rgb(255, 45, 65);
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
            refreshControlSummary();
        }
    }


    // FUTURISTIC RUSTIC BLACK-AND-RED HOME DASHBOARD
    private void buildDashboard() {
        createPageShell("DIABLO SCANNER", "CHART INTELLIGENCE SYSTEM");
        addInfoPanel("● SCREEN SCANNER  •  READY TO CONFIGURE");
        addSectionTitle("LATEST RESULT");
        signalText = new TextView(this);
        signalText.setText("WAIT");
        signalText.setTextSize(34);
        signalText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        signalText.setTextColor(Color.YELLOW);
        signalText.setGravity(Gravity.CENTER);
        signalText.setPadding(0, 14, 0, 4);
        root.addView(signalText);

        statusText = new TextView(this);
        statusText.setText("Open AI Scanner to capture a live chart or analyse a saved chart.");
        statusText.setTextSize(13);
        statusText.setTextColor(white);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(8, 4, 8, 12);
        root.addView(statusText);

        countText = new TextView(this);
        countText.setTextColor(Color.LTGRAY);
        countText.setTextSize(12);
        countText.setGravity(Gravity.CENTER);
        countText.setPadding(0, 0, 0, 10);
        root.addView(countText);

        TextView controlSummary = new TextView(this);
        controlSummary.setTag("control_summary");
        controlSummary.setTextColor(Color.rgb(255, 150, 160));
        controlSummary.setTextSize(12);
        controlSummary.setGravity(Gravity.CENTER);
        controlSummary.setPadding(12, 12, 12, 12);
        controlSummary.setBackground(makePanel(Color.rgb(27, 16, 19), red, 1, 16));
        root.addView(controlSummary);
        refreshControlSummary();

        addSectionTitle("COMMAND DECK");
        addButton("◈  AI SCANNER", v -> openPage("AI SCANNER"));
        addButton("⚙  CONFIGURATION", v -> openPage("CONFIGURATION"));
        addButton("▤  SIGNAL HISTORY", v -> openPage("SIGNAL HISTORY"));
        addButton("▧  SAVED CHARTS", v -> openPage("SAVED CHARTS"));
        addButton("◎  AI ENGINE STATUS", v -> openPage("AI ENGINE"));

        addInfoPanel("BLACK + RED INDUSTRIAL THEME  •  SCROLLABLE SCREENS  •  MANUAL TRADING ONLY");
        refreshCount();
        refreshLatestResult();
    }

    private void createPageShell(String title, String subtitle) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(background);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(18, 18, 18, 24);
        root.setBackgroundColor(background);
        scroll.addView(root);
        setContentView(scroll);

        if (!"DIABLO SCANNER".equals(title)) {
            addButton("‹  BACK TO DASHBOARD", v -> buildDashboard());
        }

        TextView brand = new TextView(this);
        brand.setText("◈  DIABLO SCANNER");
        brand.setTextSize(12);
        brand.setTextColor(red);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        brand.setLetterSpacing(0.12f);
        brand.setGravity(Gravity.CENTER);
        brand.setPadding(0, 8, 0, 4);
        root.addView(brand);

        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextSize(25);
        heading.setTextColor(white);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setLetterSpacing(0.04f);
        heading.setGravity(Gravity.CENTER);
        heading.setPadding(0, 6, 0, 2);
        root.addView(heading);

        TextView sub = new TextView(this);
        sub.setText(subtitle);
        sub.setTextSize(11);
        sub.setTextColor(Color.rgb(255, 110, 125));
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 0, 0, 18);
        root.addView(sub);
    }

    private void addInfoPanel(String text) {
        TextView panel = new TextView(this);
        panel.setText(text);
        panel.setTextColor(Color.rgb(245, 205, 210));
        panel.setTextSize(12);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(14, 14, 14, 14);
        panel.setBackground(makePanel(Color.rgb(26, 15, 18), red, 1, 14));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 7, 0, 10);
        root.addView(panel, params);
    }

    private void openPage(String page) {
        createPageShell(page, "FUTURISTIC RUSTIC  •  BLACK / RED CONTROL SYSTEM");

        if ("AI SCANNER".equals(page)) {
            addInfoPanel("LIVE CHART CAPTURE  •  BLACK RAY SCAN  •  ONE SIGNAL PER TAP");
            addSectionTitle("LIVE SCANNER CONTROLS");
            addButton("START LIVE SCREEN CAPTURE", v -> requestScreenCapture());
            addButton("STOP SCREEN CAPTURE", v -> stopScreenCapture());
            addButton("START FLOATING D BUBBLE", v -> startFloatingBubble());
            addButton("ANALYSE A SAVED CHART", v -> chooseChart());
            addInfoPanel("Keep the trading chart visible while scanning. Signals are for manual review; no trades are executed.");
        } else if ("CONFIGURATION".equals(page)) {
            addInfoPanel("Change settings here. Each control opens its own selection screen.");
            addSectionTitle("SCAN CONFIGURATION");
            addButton("TRADE DURATION", v -> showTradeDurationSettings());
            addButton("CHART TIMEFRAME", v -> showChartTimeframeSettings());
            addButton("TRADING MODE", v -> showTradingModeSettings());
            addButton("TIMED SIGNAL SPEED / PRECISION", v -> showTimedAnalysisProfileSettings());
            addButton("VISUAL SENSITIVITY", v -> showSensitivitySettings());
            addButton("CANDLE TIMING", v -> showSignalTimingSettings());
            addButton("ALL SETTINGS MENU", v -> showSettings());
            addInfoPanel("Your saved settings are kept on this phone.");
        } else if ("SIGNAL HISTORY".equals(page)) {
            renderHistoryPage();
            addButton("CLEAR SIGNAL HISTORY", v -> {
                clearHistory();
                openPage("SIGNAL HISTORY");
            });
        } else if ("SAVED CHARTS".equals(page)) {
            addInfoPanel("Choose a chart image from your phone gallery to analyse it.");
            addButton("CHOOSE CHART FROM GALLERY", v -> chooseChart());
            addButton("OPEN AI SCANNER", v -> openPage("AI SCANNER"));
        } else if ("AI ENGINE".equals(page)) {
            SharedPreferences ai = getSharedPreferences("diablo_scanner_ai", MODE_PRIVATE);
            String lastSignal = ai.getString("latest_ai_signal", "No AI result recorded yet");
            String lastDetails = ai.getString("latest_ai_details", "No AI explanation recorded yet.");
            boolean modelFilesReady = LocalVisionAI.modelsReady(this);
            addInfoPanel("MODEL FILE CHECK: " + (modelFilesReady ? "PACKAGED MODEL FILES DETECTED" : "MODEL FILES NOT DETECTED") + "\nNative runtime execution still needs a real on-device test.");
            addSectionTitle("LATEST ENGINE OUTPUT");
            addInfoPanel("Last AI result: " + lastSignal + "\n\n" + lastDetails);
            addButton("RUN LIVE SCAN", v -> openPage("AI SCANNER"));
            addButton("OPEN CONFIGURATION", v -> openPage("CONFIGURATION"));
        }
    }

    private void renderHistoryPage() {
        addSectionTitle("SAVED SIGNAL RECORDS");
        try {
            JSONArray history = new JSONArray(prefs.getString(HISTORY, "[]"));
            if (history.length() == 0) {
                addInfoPanel("No saved signals yet. Run one scan to create a history entry.");
                return;
            }
            StringBuilder text = new StringBuilder();
            int count = Math.min(history.length(), 50);
            for (int i = 0; i < count; i++) {
                JSONObject item = history.getJSONObject(i);
                text.append(i + 1).append(". ")
                    .append(item.optString("signal", "WAIT"))
                    .append("  •  ")
                    .append(item.optString("time", "Unknown time"))
                    .append("\n")
                    .append(item.optString("details", "No details saved"))
                    .append("\n\n------------------------------\n\n");
            }
            addInfoPanel(text.toString());
        } catch (Exception error) {
            addInfoPanel("Could not read signal history. Existing saved data has not been deleted.");
        }
    }

    private void buildLegacyDashboard() {
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
        logo.setBackground(makePanel(Color.rgb(27, 16, 19), amber, 3, 24));
        logo.setPadding(0, 6, 0, 6);
        logo.setTextColor(red);
        logo.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        logo.setGravity(Gravity.CENTER);
        root.addView(logo);

        TextView title = new TextView(this);
        title.setText("DIABLO SCANNER");
        title.setTextSize(25);
        title.setLetterSpacing(0.08f);
        title.setTextColor(white);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("2026 FUTURISTIC-RUSTIC • LOCAL VISION CORE");
        subtitle.setTextSize(11);
        subtitle.setTextColor(Color.rgb(255, 45, 65));
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
        controlSummary.setTextColor(Color.rgb(255, 150, 160));
        controlSummary.setTextSize(12);
        controlSummary.setGravity(Gravity.CENTER);
        controlSummary.setPadding(12, 12, 12, 12);
        controlSummary.setBackground(makePanel(Color.rgb(27, 16, 19), amber, 1, 16));
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

        addSectionTitle("SETTINGS & LOCAL ANALYZER");
        addButton("SETTINGS / DURATION / TIMEFRAME / MODE / TIMING", v -> showSettings());

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
        aiStatus.setTextColor(Color.rgb(140, 220, 170));
        aiStatus.setTextSize(12);
        aiStatus.setPadding(8, 8, 8, 12);
        aiStatus.setText("SMOLVLM VISION AI: MODEL + IMAGE PROJECTOR + NATIVE ENGINE ARE PACKAGED IN THIS APK");
        root.addView(aiStatus);

        TextView warning = new TextView(this);

        warning.setText(
                "TRADING RISK WARNING\n\n" +
                "DIABLO SCANNER includes basic visual chart analysis and " +
                "source code for a local vision AI engine. Actual AI operation " +
                "must be verified on the phone.\n\n" +
                "Saved screenshots are analysed locally without a downloaded model. " +
                "Live analysis reads pixels from the captured screen; it is not a broker data feed. " +
                "The experimental visual score is not a verified win probability. No signal guarantees profit. " +
                "The app does not place trades."
        );

        warning.setTextColor(Color.rgb(255, 125, 140));
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
        String profile = prefs.getString("timed_analysis_profile", "BALANCED");
        String profileLabel = "FAST".equals(profile) ? "FAST SCAN"
                : "PRECISION".equals(profile) ? "PRECISION CHECK" : "BALANCED";

        ((TextView) view).setText(
                "◈ ACTIVE CONFIGURATION\n" +
                modeLabel +
                "\nChart timeframe: " + timeframe +
                "\nTrade duration: " + formatDuration(duration) +
                "\nTimed engine: " + profileLabel
        );
    }

    private GradientDrawable makePanel(int fill, int stroke, int strokeWidth, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(radius * getResources().getDisplayMetrics().density);
        shape.setStroke(Math.max(1, (int) (strokeWidth * getResources().getDisplayMetrics().density)), stroke);
        return shape;
    }

    private void addSectionTitle(String text) {
        TextView heading = new TextView(this);

        heading.setText(text);
        heading.setTextSize(13);
        heading.setTextColor(amber);
        heading.setLetterSpacing(0.12f);
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
        button.setBackground(makePanel(panelColor, red, 1, 14));
        button.setBackgroundTintList(null);
        button.setPadding(12, 8, 12, 8);
        button.setLetterSpacing(0.02f);

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        params.setMargins(0, 6, 0, 6);

        root.addView(button, params);
        button.setOnClickListener(listener);
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

    // SAVED CHART ANALYSIS — ON-DEVICE CHART FEATURE ENGINE
    private void analyseBitmap(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            Toast.makeText(this, "Unable to analyse this image.", Toast.LENGTH_LONG).show();
            return;
        }
        final boolean scalpingMode = "SCALPING".equalsIgnoreCase(prefs.getString("trading_mode", "TIMED"));
        signalText.setText("ANALYSING…");
        signalText.setTextColor(Color.CYAN);
        statusText.setText("Preparing shared SmolVLM vision analysis for " +
                (scalpingMode ? "Scalping Setup" : "Timed Signal") + "...");
        final Bitmap analysisBitmap;
        try {
            analysisBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        } catch (Exception e) {
            signalText.setText("WAIT");
            statusText.setText("Could not prepare the screenshot. Try another image.");
            return;
        }
        if (analysisBitmap == null) {
            signalText.setText("WAIT");
            statusText.setText("Could not prepare the screenshot. Try another image.");
            return;
        }
        final java.util.concurrent.atomic.AtomicBoolean savedAnalysisDone = new java.util.concurrent.atomic.AtomicBoolean(false);
        final Handler savedProgressHandler = new Handler(Looper.getMainLooper());
        final Runnable savedProgressUpdater = new Runnable() {
            @Override public void run() {
                if (savedAnalysisDone.get()) return;
                String stage = prefs.getString("scan_message", "Preparing saved chart analysis...");
                statusText.setText("SAVED CHART ANALYSIS\n" + stage + "\nPlease wait; one analysis is running.");
                savedProgressHandler.postDelayed(this, 700L);
            }
        };
        prefs.edit().putString("scan_message", "Preparing shared SmolVLM vision analysis for " +
                (scalpingMode ? "Scalping Setup" : "Timed Signal") + "...").apply();
        savedProgressHandler.post(savedProgressUpdater);
        new Thread(() -> {
            LocalVisionAI.Result result;
            try {
                // Keep saved-chart mode routing identical to live scanning:
                // fast strategy for Timed Signal, SmolVLM for Scalping Setup.
                result = scalpingMode
                        ? LocalVisionAI.analyze(getApplicationContext(), analysisBitmap)
                        : ChartAnalyzer.analyze(getApplicationContext(), analysisBitmap);
            } catch (Exception e) {
                result = new LocalVisionAI.Result("WAIT", "Local chart analysis failed safely. Please try a clearer screenshot.");
            } finally {
                if (!analysisBitmap.isRecycled()) analysisBitmap.recycle();
            }
            final LocalVisionAI.Result finalResult = result;
            runOnUiThread(() -> {
                savedAnalysisDone.set(true);
                savedProgressHandler.removeCallbacks(savedProgressUpdater);
                String direction = finalResult == null || finalResult.signal == null ? "WAIT" :
                        finalResult.signal.trim().toUpperCase(Locale.ROOT);
                String display = "UP".equals(direction) ? (scalpingMode ? "BUY" : "UP") :
                        "DOWN".equals(direction) ? (scalpingMode ? "SELL" : "DOWN") : "WAIT";
                int colour = "UP".equals(direction) ? Color.rgb(50,220,120) :
                        "DOWN".equals(direction) ? red : Color.YELLOW;
                String details = finalResult == null ? "No result returned." : finalResult.details;
                int confidence = 0;
                if (details != null) {
                    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("CONFIDENCE:\\s*(\\d+)").matcher(details);
                    if (matcher.find()) try { confidence = Integer.parseInt(matcher.group(1)); } catch (Exception ignored) {}
                }
                signalText.setText(display);
                signalText.setTextColor(colour);
                statusText.setText((scalpingMode ? "SAVED CHART • SMOLVLM AI SCALPING\n" : "SAVED CHART • SMOLVLM AI TIMED SIGNAL\n") + (details == null ? "" : details));
                prefs.edit().putString("scan_status", "SAVED_READY")
                        .putString("scan_message", "Saved chart analysis complete.").apply();
                saveHistory(display, confidence, 0, 0, scalpingMode ? "SAVED_AI" : "SAVED_TIMED");
                refreshCount();
            });
        }, "DiabloSavedChartAnalyzer").start();
    }

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

            if (signal.contains("UP") || signal.contains("BUY")) {
                signalText.setTextColor(Color.rgb(50, 220, 120));
            } else if (signal.contains("DOWN") || signal.contains("SELL")) {
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
            } else if ("LIVE_TIMED".equals(source) || "SAVED_TIMED".equals(source)) {
                statusText.setText(
                        ("LIVE_TIMED".equals(source) ? "Latest live Timed Signal result" : "Latest saved-chart Timed Signal result") +
                        "\nTime: " + time +
                        "\nExperimental image-analysis strength: " + score + "%" +
                        "\nThis is not a measured win probability."
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
                boolean timedResult = "LIVE_TIMED".equals(source) || "SAVED_TIMED".equals(source);
                text.append(item.optString("time", "Unknown time"))
                        .append(" — ")
                        .append(item.optString("signal", "WAIT"))
                        .append(" (")
                        .append(item.optInt("score", 0))
                        .append(aiResult ? "% experimental AI confidence)" : timedResult ? "% image-analysis strength)" : "% visual balance score)")
                        .append("\nSource: ")
                        .append("LIVE_AI".equals(source) ? "Live local AI" :
                                "SAVED_AI".equals(source) ? "Saved-chart local AI" :
                                "LIVE_TIMED".equals(source) ? "Live Timed Signal rules" :
                                "SAVED_TIMED".equals(source) ? "Saved-chart Timed Signal rules" :
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
                "Trade duration",
                "Chart timeframe",
                "Trading mode",
                "Timed analysis speed / precision",
                "Visual sensitivity",
                "Candle timing (forming/close)"
        };

        new AlertDialog.Builder(this)
                .setTitle("DIABLO CONTROL DECK")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showTradeDurationSettings();
                    } else if (which == 1) {
                        showChartTimeframeSettings();
                    } else if (which == 2) {
                        showTradingModeSettings();
                    } else if (which == 3) {
                        showTimedAnalysisProfileSettings();
                    } else if (which == 4) {
                        showSensitivitySettings();
                    } else {
                        showSignalTimingSettings();
                    }
                })
                .setNegativeButton("CLOSE", null)
                .show();
    }

    private void showTimedAnalysisProfileSettings() {
        String[] labels = {
                "FAST — quicker, lighter scan",
                "BALANCED — recommended default",
                "PRECISION — slower, stricter confirmation"
        };
        String[] values = {"FAST", "BALANCED", "PRECISION"};
        String current = prefs.getString("timed_analysis_profile", "BALANCED");
        int selected = "FAST".equals(current) ? 0 : "PRECISION".equals(current) ? 2 : 1;
        new AlertDialog.Builder(this)
                .setTitle("Timed Signal engine")
                .setMessage("Choose the balance between scan speed and stricter visual confirmation. This changes analysis settings, not the broker chart timeframe. No profile guarantees a winning signal.")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    prefs.edit().putString("timed_analysis_profile", values[which]).apply();
                    dialog.dismiss();
                    refreshControlSummary();
                    Toast.makeText(this, "Timed engine: " + labels[which], Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void showChartTimeframeSettings() {
        String[] options = {
                "15 seconds", "30 seconds", "1 minute", "2 minutes", "3 minutes",
                "5 minutes", "10 minutes", "15 minutes", "30 minutes",
                "1 hour", "4 hours", "1 day"
        };
        String[] values = {
                "15s", "30s", "1m", "2m", "3m", "5m", "10m", "15m",
                "30m", "1h", "4h", "1d"
        };
        String current = prefs.getString("chart_timeframe", "1m");
        int selected = 2;
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
                .setMessage("Mode 1 gives a directional chart read. Mode 2 analyses the visible chart for a scalping bias. Entry/TP/SL are not invented when prices cannot be read.")
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

    private void showSignalTimingSettings() {
        String[] labels = {"Analyse the forming candle", "Wait until candle close"};
        String[] values = {"FORMING", "CLOSE"};
        String current = prefs.getString("signal_timing", "CLOSE");
        int selected = "FORMING".equalsIgnoreCase(current) ? 0 : 1;
        new AlertDialog.Builder(this)
                .setTitle("Mode 1 candle timing")
                .setMessage("A screenshot cannot independently verify whether a broker candle has closed.")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    prefs.edit().putString("signal_timing", values[which]).apply();
                    dialog.dismiss();
                    refreshControlSummary();
                    Toast.makeText(this, "Candle timing saved: " + labels[which], Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void showTradeDurationSettings() {
        String[] options = {
                "5 seconds",
                "10 seconds",
                "15 seconds",
                "30 seconds",
                "1 minute",
                "2 minutes",
                "3 minutes",
                "5 minutes",
                "10 minutes",
                "15 minutes",
                "30 minutes",
                "1 hour",
                "Custom duration (seconds)"
        };

        long current = prefs.getLong(
                TRADE_DURATION_MS,
                DEFAULT_TRADE_DURATION_MS
        );

        int selected = current == 5000L ? 0
                : current == 10000L ? 1
                : current == 15000L ? 2
                : current == 30000L ? 3
                : current == 60000L ? 4
                : current == 120000L ? 5
                : current == 180000L ? 6
                : current == 300000L ? 7
                : current == 600000L ? 8
                : current == 900000L ? 9
                : current == 1800000L ? 10
                : current == 3600000L ? 11
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

                            if (which == 12) {
                                dialog.dismiss();
                                showCustomTradeDuration();
                                return;
                            }

                            long duration = which == 0 ? 5000L
                                    : which == 1 ? 10000L
                                    : which == 2 ? 15000L
                                    : which == 3 ? 30000L
                                    : which == 4 ? 60000L
                                    : which == 5 ? 120000L
                                    : which == 6 ? 180000L
                                    : which == 7 ? 300000L
                                    : which == 8 ? 600000L
                                    : which == 9 ? 900000L
                                    : which == 10 ? 1800000L
                                    : 3600000L;

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
