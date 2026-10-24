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
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
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

    private WebView dashboardWebView;
    private LinearLayout root;
    private TextView signalText;
    private TextView statusText;
    private TextView countText;
    private SharedPreferences prefs;

    private final int red = Color.rgb(255, 45, 65);
    private final int background = Color.rgb(12, 12, 16);
    private final int panelColor = Color.rgb(24, 20, 20);
    private final int amber = Color.rgb(255, 145, 48);
    private final int white = Color.WHITE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        // Keep native analysis result holders alive for the existing saved-chart pipeline.
        signalText = new TextView(this);
        statusText = new TextView(this);
        countText = new TextView(this);

        getWindow().setStatusBarColor(Color.rgb(5, 7, 12));
        getWindow().setNavigationBarColor(Color.rgb(5, 7, 12));

        dashboardWebView = new WebView(this);
        dashboardWebView.setBackgroundColor(Color.rgb(5, 7, 12));
        WebSettings settings = dashboardWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        dashboardWebView.addJavascriptInterface(new DashboardBridge(), "DiabloAndroid");
        dashboardWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                refreshDashboardWebView();
            }
        });
        setContentView(dashboardWebView);
        loadDashboardAsset();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs != null) {
            refreshCount();
            refreshLatestResult();
            refreshControlSummary();
            refreshDashboardWebView();
        }
    }

    private void loadDashboardAsset() {
        try (InputStream input = getAssets().open("dashboard.html");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            String html = new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            dashboardWebView.loadDataWithBaseURL(
                    "https://appassets.androidplatform.net/",
                    html, "text/html", "UTF-8", null);
        } catch (Exception error) {
            Toast.makeText(this, "Dashboard failed to load: " + error.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void refreshDashboardWebView() {
        if (dashboardWebView == null) return;
        dashboardWebView.post(() -> dashboardWebView.evaluateJavascript(
                "if (window.refreshDashboard) window.refreshDashboard();", null));
    }

    private void runDashboardAction(String action) {
        if (action == null) return;
        switch (action) {
            case "start_live_scan":
                prefs.edit().putString("trading_mode", "TIMED").apply();
                requestScreenCapture();
                break;
            case "start_timed_scan":
                prefs.edit().putString("trading_mode", "TIMED").apply();
                requestScreenCapture();
                break;
            case "start_guided_scalping":
                prefs.edit().putString("trading_mode", "SCALPING").apply();
                startFloatingBubble();
                Toast.makeText(this, "Guided scalping selected. Keep the chart visible and follow 4H → 15M → 1M.",
                        Toast.LENGTH_LONG).show();
                break;
            case "stop_live_scan":
                stopScreenCapture();
                break;
            case "start_bubble":
                startFloatingBubble();
                break;
            case "choose_chart":
                chooseChart();
                break;
            case "trade_duration":
                showTradeDurationSettings();
                break;
            case "scan_window":
                showTimedScanDurationSettings();
                break;
            case "timeframe":
                showChartTimeframeSettings();
                break;
            case "trading_mode":
                showTradingModeSettings();
                break;
            case "analysis_profile":
                showTimedAnalysisProfileSettings();
                break;
            case "sensitivity":
                showSensitivitySettings();
                break;
            case "candle_timing":
                showSignalTimingSettings();
                break;
            case "timed_smolvlm": {
                boolean enabled = !prefs.getBoolean("timed_smolvlm_enabled", false);
                prefs.edit().putBoolean("timed_smolvlm_enabled", enabled).apply();
                Toast.makeText(this, enabled
                        ? "Optional SmolVLM confirmation enabled for Timed Signal. Fast hybrid remains primary."
                        : "Optional SmolVLM confirmation disabled. Timed Signal will use the fast hybrid analyzer only.",
                        Toast.LENGTH_LONG).show();
                break;
            }
            case "scalping_engine":
                showScalpingEngineSettings();
                break;
            case "kronos_config":
                prefs.edit().putString("scalping_engine", "KRONOS").apply();
                showKronosCandleJsonSettings();
                break;
            case "kronos_run":
                runKronosForecast();
                break;
            case "all_settings":
                showSettings();
                break;
            case "clear_history":
                clearHistory();
                break;
            case "overlay_permission":
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    Intent overlayIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(overlayIntent);
                } else {
                    Toast.makeText(this, "Overlay permission is already enabled.", Toast.LENGTH_SHORT).show();
                }
                break;
            default:
                break;
        }
        refreshDashboardWebView();
    }

    private JSONObject dashboardData() {
        JSONObject data = new JSONObject();
        try {
            SharedPreferences ai = prefs;
            JSONArray history = new JSONArray(prefs.getString(HISTORY, "[]"));
            int up = 0, down = 0, wait = 0;
            for (int i = 0; i < history.length(); i++) {
                String signal = history.getJSONObject(i).optString("signal", "WAIT").toUpperCase(Locale.ROOT);
                if (signal.contains("UP") || signal.contains("BUY")) up++;
                else if (signal.contains("DOWN") || signal.contains("SELL")) down++;
                else wait++;
            }
            data.put("signal", ai.getString("latest_ai_signal", "WAIT"));
            data.put("details", ai.getString("latest_ai_details", "No analysis recorded yet. Start a live scan or analyze a saved chart."));
            String latestDetails = ai.getString("latest_ai_details", "");
            String latestSignal = ai.getString("latest_ai_signal", "WAIT");
            data.put("direction", dashboardDirection(latestSignal));
            data.put("entry", extractTradeValue(latestDetails, "ENTRY"));
            data.put("stopLoss", extractTradeValue(latestDetails, "STOP[_ ]?LOSS|SL"));
            data.put("takeProfit", extractTradeValue(latestDetails, "TAKE[_ ]?PROFIT|TP"));
            data.put("savedChartPreview", ai.getString("saved_chart_preview_data", ""));
            data.put("history", history);
            data.put("total", history.length());
            data.put("up", up);
            data.put("down", down);
            data.put("wait", wait);
            data.put("modelFilesReady", LocalVisionAI.modelsReady(this));
            data.put("scanStatus", prefs.getString("scan_status", "READY"));
            data.put("scanMessage", prefs.getString("scan_message", "Scanner ready. No live chart read yet."));
            data.put("mode", prefs.getString("trading_mode", "TIMED"));
            data.put("tradeDuration", formatDuration(prefs.getLong(TRADE_DURATION_MS, DEFAULT_TRADE_DURATION_MS)));
            data.put("scanWindow", formatDuration(prefs.getLong("timed_scan_duration_ms", 30000L)));
            data.put("timeframe", prefs.getString("chart_timeframe", "1m"));
            data.put("profile", prefs.getString("timed_analysis_profile", "BALANCED"));
            data.put("timedSmolAssist", prefs.getBoolean("timed_smolvlm_enabled", false));
            data.put("scalpingEngine", prefs.getString("scalping_engine", "SMOLVLM"));
            data.put("kronosSymbol", prefs.getString("kronos_symbol", "EURUSD"));
            data.put("kronosInterval", prefs.getString("kronos_interval", "1m"));
            data.put("kronosServiceConfigured", !prefs.getString("kronos_service_url", "").trim().isEmpty());
            data.put("kronosSignal", prefs.getString("kronos_latest_signal", "WAIT"));
            data.put("kronosStatus", prefs.getString("kronos_status", "NOT RUN"));
            data.put("kronosDetails", prefs.getString("kronos_latest_details", "No Kronos forecast has run yet."));
            data.put("kronosFeedNote", prefs.getString("kronos_feed_note", "BigQuote completed candles are fetched on this phone; Kronos-mini inference runs locally without a host."));
            KronosLocalRuntime.Status localKronos = KronosLocalRuntime.inspect(this);
            data.put("kronosLocalReady", localKronos.isReady());
            data.put("kronosLocalModelPresent", localKronos.state != KronosLocalRuntime.State.MODEL_MISSING && localKronos.state != KronosLocalRuntime.State.INVALID_METADATA);
            data.put("kronosLocalTokenizerPresent", localKronos.state != KronosLocalRuntime.State.TOKENIZER_MISSING);
            data.put("kronosRuntimeMessage", localKronos.message);
            try { data.put("kronosCandles", new JSONObject(prefs.getString("kronos_chart_candles", "{}")).optJSONArray("candles") == null ? new JSONArray() : new JSONObject(prefs.getString("kronos_chart_candles", "{}")).optJSONArray("candles")); } catch (Exception ignored) { data.put("kronosCandles", new JSONArray()); }
            data.put("kronosLastFeedAt", prefs.getString("kronos_chart_updated_at", "Not fetched yet"));
            SharedPreferences tradePrefs = getSharedPreferences("diablo_trade_records", MODE_PRIVATE);
            JSONArray trades = new JSONArray(tradePrefs.getString("trades", "[]"));
            int wins = 0, losses = 0, unknownTrades = 0;
            for (int i = 0; i < trades.length(); i++) {
                String result = trades.getJSONObject(i).optString("result", "UNKNOWN");
                if ("WIN".equalsIgnoreCase(result)) wins++;
                else if ("LOSS".equalsIgnoreCase(result)) losses++;
                else unknownTrades++;
            }
            int decidedTrades = wins + losses;
            data.put("tradeCount", trades.length());
            data.put("tradeWins", wins);
            data.put("tradeLosses", losses);
            data.put("tradeUnknown", unknownTrades);
            data.put("tradeDecided", decidedTrades);
            data.put("tradeRate", decidedTrades == 0 ? "—%" :
                    String.format(Locale.US, "%.1f%%", wins * 100.0 / decidedTrades));
        } catch (Exception ignored) {
        }
        return data;
    }

    private String dashboardDirection(String signal) {
        if (signal == null) return "WAIT";
        String s = signal.trim().toUpperCase(Locale.ROOT);
        if (s.contains("UP") || s.equals("BUY")) return "UP";
        if (s.contains("DOWN") || s.equals("SELL")) return "DOWN";
        return "WAIT";
    }

    private String extractTradeValue(String details, String labelPattern) {
        if (details == null || details.trim().isEmpty()) return "N/A";
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "(?im)\\b(?:" + labelPattern + ")\\b\\s*[:=\\-]?\\s*([^\\r\\n;|]+)");
            java.util.regex.Matcher m = p.matcher(details);
            if (m.find()) {
                String value = m.group(1).trim();
                value = value.replaceAll("(?i)\\s+(REASON|CONFIDENCE|DIRECTION|SIGNAL|TREND)\\s*:.*$", "").trim();
                if (!value.isEmpty() && !value.equalsIgnoreCase("N/A")
                        && !value.equalsIgnoreCase("not available")) return value;
            }
        } catch (Exception ignored) { }
        return "N/A";
    }

    public final class DashboardBridge {
        @JavascriptInterface
        public String getDashboardData() {
            return dashboardData().toString();
        }

        @JavascriptInterface
        public void action(String action) {
            runOnUiThread(() -> runDashboardAction(action));
        }
    }

    private void buildDashboard() {
        createPageShell("DIABLO SCANNER", "Shazam for Trading Charts");
        addHeroBrandPanel();
        addInfoPanel("● SCREEN SCANNER READY");

        addSectionTitle("TRADING MODE");
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        Button scalping = makeDashboardModeButton("AI SCALPING", true);
        Button timed = makeDashboardModeButton("TIMED SIGNAL", false);
        LinearLayout.LayoutParams modeParams = new LinearLayout.LayoutParams(0,
                (int) (52 * getResources().getDisplayMetrics().density), 1f);
        modeParams.setMargins(3, 4, 3, 4);
        modeRow.addView(scalping, modeParams);
        modeRow.addView(timed, modeParams);
        root.addView(modeRow);
        scalping.setOnClickListener(v -> {
            prefs.edit().putString("trading_mode", "SCALPING").apply();
            openPage("AI SCANNER");
        });
        timed.setOnClickListener(v -> {
            prefs.edit().putString("trading_mode", "TIMED").apply();
            openPage("AI SCANNER");
        });

        addSectionTitle("TRADE DURATION");
        addButton("◷  " + formatDuration(prefs.getLong(TRADE_DURATION_MS, DEFAULT_TRADE_DURATION_MS))
                + "  •  CHANGE", v -> showTradeDurationSettings());

        addSectionTitle("SCAN");
        addReferenceCard("START SCREEN SCAN", "Analyze the currently visible broker chart", true,
                v -> openPage("AI SCANNER"));
        addReferenceCard("ANALYZE SAVED CHART", "Choose a chart image from your phone", false,
                v -> chooseChart());

        addSectionTitle("LATEST SIGNAL");
        signalText = new TextView(this);
        signalText.setText("WAIT");
        signalText.setTextSize(30);
        signalText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        signalText.setTextColor(Color.rgb(255, 193, 7));
        signalText.setGravity(Gravity.CENTER);
        signalText.setPadding(0, 12, 0, 6);
        signalText.setBackground(makePanel(Color.rgb(0, 12, 19), red, 1, 15));
        root.addView(signalText);

        statusText = new TextView(this);
        statusText.setText("Scanner ready • manual trading only");
        statusText.setTextSize(12);
        statusText.setTextColor(white);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(8, 5, 8, 8);
        root.addView(statusText);

        countText = new TextView(this);
        countText.setTextColor(Color.LTGRAY);
        countText.setTextSize(11);
        countText.setGravity(Gravity.CENTER);
        countText.setPadding(0, 0, 0, 8);
        root.addView(countText);
        refreshCount();
        refreshLatestResult();

        addSectionTitle("QUICK ACTIONS");
        addReferenceCard("⚙  SETTINGS", "Mode, timeframe and trade duration", false,
                v -> openPage("CONFIGURATION"));
        addReferenceCard("▤  SIGNAL HISTORY", "Review saved signals and outcomes", false,
                v -> openPage("SIGNAL HISTORY"));
        addReferenceCard("▧  GALLERY ANALYSIS", "Analyze a saved chart image", false,
                v -> chooseChart());
        addReferenceCard("◉  FLOATING D BUBBLE", "Start the overlay scanner", false, v -> {
            startFloatingBubble();
            Toast.makeText(this, "Open the floating D bubble over your broker chart.",
                    Toast.LENGTH_LONG).show();
        });
        addReferenceCard("✦  AI ENGINE STATUS", "View engine status", false,
                v -> openPage("AI ENGINE"));

        addTransmissionPanel();
        addSectionTitle("QUICK ACCESS");
        addReferenceCard("LATEST SIGNAL", "Open the most recent chart read", false,
                v -> openPage("LATEST SIGNAL"));
        addReferenceCard("RESULTS & STATS", "Review recorded scans and outcomes", false,
                v -> openPage("RESULTS & STATS"));

        addInfoPanel("Dark navy/black surfaces • bright red accents • white text. Signals are experimental and for manual trading only.");
    }

    private Button makeDashboardModeButton(String label, boolean selected) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(12);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setBackground(makePanel(selected ? Color.rgb(238, 0, 35) : Color.rgb(17, 31, 43),
                selected ? Color.rgb(255, 35, 55) : Color.rgb(45, 65, 82), 1, 12));
        button.setBackgroundTintList(null);
        return button;
    }

    private void addReferenceCard(String title, String subtitle, boolean primary,
                                  View.OnClickListener listener) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(14, 10, 14, 10);
        card.setBackground(makePanel(primary ? Color.rgb(16, 14, 19) : Color.rgb(9, 22, 32),
                primary ? Color.rgb(255, 20, 45) : Color.rgb(37, 56, 70), primary ? 2 : 1, 13));
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(14);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView detail = new TextView(this);
        detail.setText(subtitle);
        detail.setTextColor(Color.rgb(160, 180, 194));
        detail.setTextSize(11);
        card.addView(heading);
        card.addView(detail);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 4, 0, 4);
        root.addView(card, params);
        card.setOnClickListener(listener);
    }

    private void createPageShell(String title, String subtitle) {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(background);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(background);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(18, 18, 18, 24);
        root.setBackgroundColor(background);
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        screen.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // Persistent, horizontally scrollable nine-destination navigation.
        HorizontalScrollView navScroll = new HorizontalScrollView(this);
        navScroll.setHorizontalScrollBarEnabled(false);
        navScroll.setFillViewport(false);
        navScroll.setBackground(makePanel(Color.rgb(8, 8, 11), red, 1, 12));
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        String[] icons = {"⌂", "↗", "▤", "⚙", "✦", "◉", "▥", "ⓘ", "?"};
        String[] labels = {"HOME", "SIGNAL", "HISTORY", "SETTINGS", "AI MODEL", "BUBBLE", "STATS", "ABOUT", "HELP"};
        String[] pages = {"HOME", "LATEST SIGNAL", "SIGNAL HISTORY", "CONFIGURATION", "AI MODEL", "FLOATING BUBBLE OVERLAY", "RESULTS & STATS", "ABOUT", "HELP & FAQ"};
        for (int i = 0; i < labels.length; i++) {
            final String destination = pages[i];
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(8, 8, 8, 7);
            TextView icon = new TextView(this);
            icon.setText(icons[i]);
            icon.setTextColor(red);
            icon.setTextSize(19);
            icon.setGravity(Gravity.CENTER);
            TextView label = new TextView(this);
            label.setText(labels[i]);
            label.setTextColor(white);
            label.setTextSize(8);
            label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            label.setGravity(Gravity.CENTER);
            item.addView(icon);
            item.addView(label);
            item.setOnClickListener(v -> {
                if ("HOME".equals(destination)) buildDashboard();
                else openPage(destination);
            });
            nav.addView(item, new LinearLayout.LayoutParams(
                    (int) (66 * getResources().getDisplayMetrics().density),
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        navScroll.addView(nav);
        LinearLayout.LayoutParams navParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        navParams.setMargins(8, 4, 8, 8);
        screen.addView(navScroll, navParams);
        setContentView(screen);

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

    // Animated red-bull hero identity, matching the approved black/red concept.
    private void addHeroBrandPanel() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(16, 18, 16, 18);
        card.setBackground(makePanel(Color.rgb(12, 7, 10), red, 2, 22));

        TextView top = new TextView(this);
        top.setText("D I A B L O  / /  V I S I O N  E N G I N E");
        top.setTextColor(Color.rgb(255, 100, 115));
        top.setTextSize(10);
        top.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        top.setGravity(Gravity.CENTER);
        card.addView(top);

        TextView bull = new TextView(this);
        bull.setText("🐂");
        bull.setTextSize(54);
        bull.setGravity(Gravity.CENTER);
        bull.setShadowLayer(18f, 0f, 0f, red);
        LinearLayout.LayoutParams bullParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (82 * getResources().getDisplayMetrics().density));
        bullParams.topMargin = 5;
        bullParams.bottomMargin = 2;
        card.addView(bull, bullParams);

        TextView logo = new TextView(this);
        logo.setText("D");
        logo.setTextSize(34);
        logo.setTypeface(Typeface.DEFAULT, Typeface.BOLD_ITALIC);
        logo.setTextColor(Color.WHITE);
        logo.setGravity(Gravity.CENTER);
        GradientDrawable orb = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(120, 8, 24), Color.rgb(12, 8, 12), Color.rgb(60, 4, 15)});
        orb.setShape(GradientDrawable.OVAL);
        orb.setStroke((int) (2 * getResources().getDisplayMetrics().density), red);
        logo.setBackground(orb);
        int logoSize = (int) (68 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(logoSize, logoSize);
        logoParams.bottomMargin = 9;
        card.addView(logo, logoParams);

        TextView tagline = new TextView(this);
        tagline.setText("SHAZAM FOR TRADING CHARTS");
        tagline.setTextColor(Color.WHITE);
        tagline.setTextSize(13);
        tagline.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tagline.setLetterSpacing(0.10f);
        tagline.setGravity(Gravity.CENTER);
        card.addView(tagline);

        TextView status = new TextView(this);
        status.setText("LIVE SCREEN VISION  •  LOCAL AI  •  MANUAL TRADING");
        status.setTextColor(Color.rgb(255, 95, 110));
        status.setTextSize(10);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 8, 0, 0);
        card.addView(status);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 5, 0, 10);
        root.addView(card, params);

        final Runnable pulse = new Runnable() {
            @Override public void run() {
                if (!bull.isAttachedToWindow()) return;
                bull.animate().scaleX(1.06f).scaleY(1.06f).setDuration(650)
                        .withEndAction(() -> bull.animate().scaleX(0.94f).scaleY(0.94f)
                                .setDuration(650).withEndAction(this).start()).start();
            }
        };
        bull.setScaleX(0.94f);
        bull.setScaleY(0.94f);
        bull.post(pulse);
        logo.setAlpha(0.82f);
        logo.animate().alpha(1f).setDuration(1000).start();
    }

    // Diablo black-and-red scanning ray. Keep the dashboard visual language consistent
    // with the approved mockup: no green transmission strip.
    private void addTransmissionPanel() {
        final TextView transmission = new TextView(this);
        transmission.setText("◢  BLACK RAY STANDBY  ›  CHART LOCK  ›  AI ANALYSIS  ›  SIGNAL");
        transmission.setTextColor(red);
        transmission.setTextSize(11);
        transmission.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        transmission.setLetterSpacing(0.035f);
        transmission.setGravity(Gravity.CENTER);
        transmission.setPadding(12, 15, 12, 15);
        transmission.setBackground(makePanel(Color.rgb(8, 8, 10), red, 1, 13));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 5, 0, 9);
        root.addView(transmission, params);
        transmission.setAlpha(0.72f);
        transmission.post(() -> animateTransmission(transmission));
    }

    private void animateTransmission(final TextView transmission) {
        if (!transmission.isAttachedToWindow()) return;
        final String[] rayFrames = {"◢", "◣", "◤", "◥"};
        final int frame = (int) (System.currentTimeMillis() / 350L % rayFrames.length);
        transmission.setText(rayFrames[frame] +
                "  BLACK RAY SCAN  ›  CHART LOCK  ›  AI ANALYSIS  ›  SIGNAL");
        float nextAlpha = transmission.getAlpha() > 0.85f ? 0.58f : 1f;
        transmission.animate().alpha(nextAlpha).setDuration(350)
                .withEndAction(() -> animateTransmission(transmission)).start();
    }

    private void addInfoPanel(String text) {
        TextView panel = new TextView(this);
        panel.setText(text);
        panel.setTextColor(Color.rgb(245, 205, 210));
        panel.setTextSize(12);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(14, 14, 14, 14);
        panel.setBackground(makePanel(Color.rgb(25, 18, 17), amber, 1, 14));
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
            addTransmissionPanel();
            addSectionTitle("LIVE SCANNER CONTROLS");
            addButton("START LIVE SCREEN CAPTURE", v -> requestScreenCapture());
            addButton("STOP SCREEN CAPTURE", v -> stopScreenCapture());
            addButton("START FLOATING D BUBBLE", v -> startFloatingBubble());
            addButton("START GUIDED SCALPING (4H → 15M → 1M)", v -> {
                prefs.edit().putString("trading_mode", "SCALPING").apply();
                startFloatingBubble();
                Toast.makeText(this, "Tap the floating D, expand it, then choose START GUIDED SCALPING.", Toast.LENGTH_LONG).show();
            });
            addButton("ANALYSE A SAVED CHART", v -> chooseChart());
            addInfoPanel("Timed Signal uses the lightweight image analyzer. Scalping uses the bundled SmolVLM vision model. Keep the chart visible, confirm each timeframe manually, and wait for the scan to finish. Signals are manual-review only; no broker trades are executed.");
        } else if ("CONFIGURATION".equals(page)) {
            addInfoPanel("Change settings here. Each control opens its own selection screen.");
            addSectionTitle("SCAN CONFIGURATION");
            addButton("TRADE DURATION: " + formatDuration(prefs.getLong(TRADE_DURATION_MS, DEFAULT_TRADE_DURATION_MS)), v -> showTradeDurationSettings());
            addButton("TIMED SCAN WINDOW: " + formatDuration(prefs.getLong("timed_scan_duration_ms", 30000L)), v -> showTimedScanDurationSettings());
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
        } else if ("FLOATING BUBBLE OVERLAY".equals(page)) {
            addInfoPanel("FLOATING D OVERLAY\nThe D bubble is designed to stay above your broker chart. Android may require you to grant display-over-other-apps permission.");
            addButton("START FLOATING D BUBBLE", v -> startFloatingBubble());
            addButton("OPEN LIVE SCANNER CONTROLS", v -> openPage("AI SCANNER"));
            addButton("OPEN OVERLAY PERMISSION SETTINGS", v -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } else {
                    Toast.makeText(this, "Overlay permission is already enabled.", Toast.LENGTH_SHORT).show();
                }
            });
        } else if ("EXPANDABLE SCAN PANEL".equals(page)) {
            addInfoPanel("SCROLLABLE SCAN PANEL\nThe panel should keep its controls accessible while long analysis results scroll inside the panel.");
            addButton("START FLOATING D BUBBLE", v -> startFloatingBubble());
            addButton("START LIVE SCREEN CAPTURE", v -> requestScreenCapture());
            addButton("ANALYSE A SAVED CHART", v -> chooseChart());
            addButton("OPEN FULL RESULTS", v -> openPage("FULL RESULTS"));
        } else if ("SCANNING ANIMATION".equals(page)) {
            addTransmissionPanel();
            addInfoPanel("SCAN STATUS\nThe animation is a visual progress indicator; it does not by itself prove that AI inference has completed.");
            addButton("START LIVE SCREEN CAPTURE", v -> requestScreenCapture());
            addButton("OPEN AI ENGINE STATUS", v -> openPage("AI ENGINE"));
        } else if ("FULL RESULTS".equals(page)) {
            SharedPreferences ai = getSharedPreferences("diablo_scanner_ai", MODE_PRIVATE);
            addSectionTitle("LATEST SIGNAL");
            addInfoPanel(ai.getString("latest_ai_signal", "No signal recorded yet."));
            addSectionTitle("LATEST AI EXPLANATION");
            addInfoPanel(ai.getString("latest_ai_details", "No AI explanation recorded yet. Run a scan first."));
            addButton("OPEN SIGNAL HISTORY", v -> openPage("SIGNAL HISTORY"));
            addButton("OPEN SETTINGS", v -> openPage("CONFIGURATION"));
            addButton("RUN ANOTHER SCAN", v -> openPage("AI SCANNER"));
        } else if ("AI ENGINE".equals(page) || "AI MODEL".equals(page)) {
            SharedPreferences ai = getSharedPreferences("diablo_scanner_ai", MODE_PRIVATE);
            boolean modelFilesReady = LocalVisionAI.modelsReady(this);
            addInfoPanel("VISION MODEL STATUS: " + (modelFilesReady ? "MODEL FILES DETECTED" : "MODEL FILES NOT DETECTED"));
            addSectionTitle("MODEL DETAILS");
            addInfoPanel("SmolVLM vision model / local inference target\nModel files must be packaged with the APK and the native runtime must successfully load them before this can be called a working AI engine.");
            addSectionTitle("LATEST MODEL OUTPUT");
            addInfoPanel(ai.getString("latest_ai_signal", "No AI result recorded yet") + "\n\n" + ai.getString("latest_ai_details", "Run a scan to see the latest output."));
            addButton("RUN LIVE SCAN", v -> openPage("AI SCANNER"));
            addButton("CHECK MODEL STATUS", v -> openPage("AI MODEL"));
        } else if ("LATEST SIGNAL".equals(page)) {
            SharedPreferences ai = getSharedPreferences("diablo_scanner_ai", MODE_PRIVATE);
            addSectionTitle("CURRENT SIGNAL");
            addInfoPanel(ai.getString("latest_ai_signal", "🟡 WAIT — no signal recorded yet"));
            addSectionTitle("ANALYSIS");
            addInfoPanel(ai.getString("latest_ai_details", "Run a live scan or analyze a saved chart to populate this screen."));
            addButton("SCAN CURRENT BROKER CHART", v -> openPage("AI SCANNER"));
            addButton("ANALYZE SAVED CHART", v -> chooseChart());
            addButton("VIEW HISTORY", v -> openPage("SIGNAL HISTORY"));
        } else if ("RESULTS & STATS".equals(page)) {
            addSectionTitle("SCAN PERFORMANCE");
            try {
                JSONArray history = new JSONArray(prefs.getString(HISTORY, "[]"));
                int up=0, down=0, wait=0;
                for (int i=0;i<history.length();i++) {
                    String sig=history.getJSONObject(i).optString("signal","WAIT").toUpperCase(Locale.ROOT);
                    if (sig.contains("UP")) up++; else if (sig.contains("DOWN")) down++; else wait++;
                }
                addInfoPanel("TOTAL SCANS  " + history.length() + "\nUP SIGNALS  " + up + "\nDOWN SIGNALS  " + down + "\nWAIT SIGNALS  " + wait + "\n\nWin rate is not available until outcomes are explicitly tracked; signal direction alone is not a win/loss.");
            } catch (Exception e) {
                addInfoPanel("No scan statistics available yet.");
            }
            addButton("OPEN SCAN HISTORY", v -> openPage("SIGNAL HISTORY"));
            addButton("RUN NEW SCAN", v -> openPage("AI SCANNER"));
        } else if ("ABOUT".equals(page)) {
            addSectionTitle("ABOUT DIABLO SCANNER");
            addInfoPanel("SHAZAM FOR TRADING CHARTS\n\nA chart-vision assistant designed to inspect the visible trading chart and present an experimental UP / DOWN / WAIT reading. It does not place trades.");
            addSectionTitle("IMPORTANT");
            addInfoPanel("Signals are experimental, may be wrong, and are not financial advice. Confirm every result yourself and trade manually.");
            addButton("OPEN AI MODEL STATUS", v -> openPage("AI MODEL"));
        } else if ("HELP & FAQ".equals(page)) {
            addSectionTitle("HOW TO SCAN");
            addInfoPanel("1. Open your broker chart.\n2. Start the floating D bubble or open Live Scanner.\n3. Grant screen-capture permission when Android asks.\n4. Keep the chart visible while scanning.\n5. Review the result and decide manually.");
            addSectionTitle("COMMON QUESTIONS");
            addInfoPanel("Why WAIT? The chart may not provide enough visual confirmation.\n\nDoes it place trades? No.\n\nDoes animation prove AI is running? No; model/runtime status must be tested separately.");
            addButton("OPEN LIVE SCANNER", v -> openPage("AI SCANNER"));
            addButton("OPEN SETTINGS", v -> openPage("CONFIGURATION"));
        }
    }

    private String modelFromHistorySource(String source) {
        if ("KRONOS_MARKET_FORECAST".equals(source)) return "Kronos";
        if ("SAVED_AI".equals(source)) return "SmolVLM";
        if ("SAVED_TIMED".equals(source)) return "SmolVLM";
        if ("LIVE_TIMED".equals(source)) return "Hybrid Fast";
        if ("LIVE_AI".equals(source)) return "SmolVLM";
        return "Legacy / unknown";
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
                    .append("  •  MODEL: ")
                    .append(item.optString("model", modelFromHistorySource(item.optString("source", ""))))
                    .append("  •  ")
                    .append(item.optString("time", "Unknown time"))
                    .append("\n")
                    .append("Mode: ").append(item.optString("mode", "Unknown"))
                    .append("  •  Timeframe: ").append(item.optString("timeframe", "Unknown"))
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
        long scanWindow = prefs.getLong("timed_scan_duration_ms", 30000L);
        String profile = prefs.getString("timed_analysis_profile", "BALANCED");
        String profileLabel = "FAST".equals(profile) ? "FAST SCAN"
                : "PRECISION".equals(profile) ? "PRECISION CHECK" : "BALANCED";

        ((TextView) view).setText(
                "◈ ACTIVE CONFIGURATION\n" +
                modeLabel +
                "\nChart timeframe: " + timeframe +
                "\nTrade duration: " + formatDuration(duration) +
                "\nTimed scan window: " + formatDuration(scanWindow) +
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
        button.setBackground(makePanel(panelColor, amber, 1, 14));
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
                saveSavedChartPreview(bitmap);
                prefs.edit().putString("scan_status", "SAVED_IMAGE_LOADED")
                        .putString("scan_message", "Saved chart image loaded. Preparing visual analysis…")
                        .apply();
                refreshDashboardWebView();
                analyseBitmap(bitmap);
            } finally {
                bitmap.recycle();
            }

        } catch (Exception e) {
            showImageError();
        }
    }

    private void saveSavedChartPreview(Bitmap source) {
        if (source == null || source.isRecycled()) return;
        Bitmap preview = null;
        try {
            int maxWidth = 720;
            float scale = Math.min(1f, maxWidth / (float) source.getWidth());
            int width = Math.max(1, Math.round(source.getWidth() * scale));
            int height = Math.max(1, Math.round(source.getHeight() * scale));
            preview = Bitmap.createScaledBitmap(source, width, height, true);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            preview.compress(Bitmap.CompressFormat.JPEG, 68, bytes);
            byte[] imageBytes = bytes.toByteArray();
            // Keep the preview small because dashboard data is polled on a low-memory phone.
            if (imageBytes.length > 220 * 1024) {
                bytes.reset();
                preview.compress(Bitmap.CompressFormat.JPEG, 48, bytes);
                imageBytes = bytes.toByteArray();
            }
            String dataUrl = "data:image/jpeg;base64,"
                    + android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP);
            prefs.edit().putString("saved_chart_preview_data", dataUrl).apply();
        } catch (Exception ignored) {
            prefs.edit().remove("saved_chart_preview_data").apply();
        } finally {
            if (preview != null && preview != source && !preview.isRecycled()) preview.recycle();
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
                // Keep saved-chart routing identical to live scanning.
                if (scalpingMode) {
                    String selectedEngine = prefs.getString("scalping_engine", "SMOLVLM");
                    if ("KRONOS".equalsIgnoreCase(selectedEngine)) {
                        try {
                            JSONObject candleInput = KronosRuntimeClient.fetchLocalCandleInput(getApplicationContext());
                            result = KronosLocalRuntime.forecast(getApplicationContext(), candleInput.toString());
                        } catch (Exception feedError) {
                            result = new LocalVisionAI.Result("WAIT", "KRONOS LOCAL MARKET FEED ERROR: "
                                    + feedError.getClass().getSimpleName() + " — "
                                    + (feedError.getMessage() == null ? "no details" : feedError.getMessage())
                                    + ". No hosted service or substitute model was used.");
                        }
                    } else {
                        result = LocalVisionAI.analyze(getApplicationContext(), analysisBitmap);
                    }
                } else {
                    result = ChartAnalyzer.analyze(getApplicationContext(), analysisBitmap);
                    if (prefs.getBoolean("timed_smolvlm_enabled", false) && result != null) {
                        LocalVisionAI.Result visualResult =
                                LocalVisionAI.analyze(getApplicationContext(), analysisBitmap);
                        result = combineTimedHybrid(result, visualResult);
                    }
                }
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
                publishSavedChartResult(display, details, confidence);
                refreshCount();
            });
        }, "DiabloSavedChartAnalyzer").start();
    }

    private void publishSavedChartResult(String signal, String details, int score) {
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(new Date());
        prefs.edit()
                .putString("latest_ai_signal", signal)
                .putString("latest_ai_details", details == null ? "" : details)
                .putInt("latest_ai_score", score)
                .putString("latest_ai_time", time)
                .apply();
        try {
            JSONArray history = new JSONArray(prefs.getString(HISTORY, "[]"));
            if (history.length() > 0) {
                JSONObject latest = history.getJSONObject(0);
                latest.put("details", details == null ? "" : details);
                prefs.edit().putString(HISTORY, history.toString()).apply();
            }
        } catch (Exception ignored) {
        }
        refreshDashboardWebView();
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
            String modelUsed = "KRONOS_MARKET_FORECAST".equals(source) ? "Kronos"
                    : "SAVED_AI".equals(source) ? "SmolVLM"
                    : "SAVED_TIMED".equals(source) ? "SmolVLM"
                    : "SMOLVLM".equalsIgnoreCase(prefs.getString("scalping_engine", "SMOLVLM")) ? "SmolVLM"
                    : prefs.getString("scalping_engine", "Hybrid Fast");
            item.put("model", modelUsed);
            item.put("engine", prefs.getString("scalping_engine", "SMOLVLM"));
            item.put("mode", prefs.getString("trading_mode", "TIMED"));
            item.put("timeframe", prefs.getString("chart_timeframe", "1m"));
            item.put("tradeDuration", prefs.getLong(TRADE_DURATION_MS, DEFAULT_TRADE_DURATION_MS));
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

    private LocalVisionAI.Result combineTimedHybrid(
            LocalVisionAI.Result fast,
            LocalVisionAI.Result visual) {
        if (fast == null) return new LocalVisionAI.Result("WAIT", "Fast chart analysis returned no result.");
        String fastSignal = normalizeDirection(fast.signal);
        if (visual == null) {
            return new LocalVisionAI.Result(fastSignal,
                    fast.details + "\n\nOptional SmolVLM check returned no result; fast hybrid result retained.");
        }
        String visualSignal = normalizeDirection(visual.signal);
        String visualDetails = visual.details == null ? "" : visual.details;
        String lower = visualDetails.toLowerCase(Locale.ROOT);
        boolean visualError = lower.contains("local ai error")
                || lower.contains("engine failed")
                || lower.contains("engine is missing")
                || lower.contains("missing or incomplete")
                || lower.contains("timed out")
                || lower.contains("unsupported");
        if (visualError) {
            return new LocalVisionAI.Result(fastSignal,
                    fast.details + "\n\nOPTIONAL SMOLVLM CHECK FAILED; fast hybrid result retained.\n" + visualDetails);
        }
        if (("UP".equals(fastSignal) && "DOWN".equals(visualSignal))
                || ("DOWN".equals(fastSignal) && "UP".equals(visualSignal))) {
            return new LocalVisionAI.Result("WAIT",
                    fast.details + "\n\nHYBRID CHECK: fast analyzer and SmolVLM disagree ("
                            + fastSignal + " vs " + visualSignal + "). Final result downgraded to WAIT.\n"
                            + visualDetails);
        }
        return new LocalVisionAI.Result(fastSignal,
                fast.details + "\n\nOPTIONAL SMOLVLM REVIEW: " + visualSignal
                        + ". The fast hybrid engine remains primary.\n" + visualDetails);
    }

    private String normalizeDirection(String signal) {
        if (signal == null) return "WAIT";
        String value = signal.trim().toUpperCase(Locale.ROOT);
        return ("UP".equals(value) || "DOWN".equals(value)) ? value : "WAIT";
    }

    // SETTINGS

    private void showSettings() {
        String[] options = {
                "Trade duration",
                "Timed Signal scan window",
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
                        showTimedScanDurationSettings();
                    } else if (which == 2) {
                        showChartTimeframeSettings();
                    } else if (which == 3) {
                        showTradingModeSettings();
                    } else if (which == 4) {
                        showTimedAnalysisProfileSettings();
                    } else if (which == 5) {
                        showSensitivitySettings();
                    } else {
                        showSignalTimingSettings();
                    }
                })
                .setNegativeButton("CLOSE", null)
                .show();
    }

    private void showTimedScanDurationSettings() {
        String[] labels = {
                "5 seconds — very fast / experimental",
                "15 seconds — fast",
                "30 seconds — recommended starting point",
                "60 seconds — more confirmation",
                "2 minutes — extended observation",
                "5 minutes — long observation"
        };
        long[] values = {5000L, 15000L, 30000L, 60000L, 120000L, 300000L};
        long current = prefs.getLong("timed_scan_duration_ms", 30000L);
        int selected = 2;
        for (int i = 0; i < values.length; i++) if (values[i] == current) selected = i;
        new AlertDialog.Builder(this)
                .setTitle("Timed Signal scan window")
                .setMessage("This is how long Timed Signal observes the moving screen before combining its chart reads. It is separate from trade duration. Very short scans may have too few samples to support a directional result.")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    prefs.edit().putLong("timed_scan_duration_ms", values[which]).apply();
                    dialog.dismiss();
                    refreshControlSummary();
                    Toast.makeText(this, "Timed scan window saved: " + labels[which], Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("CANCEL", null)
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
                "3 seconds (if broker supports it)", "5 seconds (if broker supports it)",
                "15 seconds", "30 seconds", "1 minute", "2 minutes", "3 minutes",
                "5 minutes", "10 minutes", "15 minutes", "30 minutes",
                "1 hour", "4 hours", "1 day"
        };
        String[] values = {
                "3s", "5s", "15s", "30s", "1m", "2m", "3m", "5m", "10m",
                "15m", "30m", "1h", "4h", "1d"
        };
        String current = prefs.getString("chart_timeframe", "1m");
        int selected = 4;
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

    private void runKronosForecast() {
        prefs.edit().putString("scalping_engine", "KRONOS")
                .putString("kronos_status", "FETCHING DATA")
                .putString("kronos_feed_note", "Fetching completed BigQuote candles on this phone; the independent local Kronos runtime is checked next. No hosted inference service is used.")
                .apply();
        refreshDashboardWebView();
        new Thread(() -> {
            LocalVisionAI.Result result;
            try {
                // Fetch genuine BigQuote candles first so the dashboard can render the actual
                // market data even when the independent Kronos inference runtime returns WAIT.
                JSONObject candleInput = KronosRuntimeClient.fetchLocalCandleInput(getApplicationContext());
                prefs.edit().putString("kronos_chart_candles", candleInput.toString())
                        .putString("kronos_chart_updated_at", java.time.Instant.now().toString())
                        .putString("kronos_candles_json", candleInput.toString()).apply();
                // The phone-only Kronos engine must never silently route inference to a host.
                // BigQuote supplies market data; only the dedicated local runtime may forecast.
                result = KronosLocalRuntime.forecast(getApplicationContext(), candleInput.toString());
            } catch (Exception feedError) {
                result = new LocalVisionAI.Result("WAIT", "KRONOS MARKET/INFERENCE ERROR: "
                        + feedError.getClass().getSimpleName() + " — "
                        + (feedError.getMessage() == null ? "no details" : feedError.getMessage())
                        + ". No fallback engine was used.");
            }
            String signal = result == null || result.signal == null ? "WAIT" : result.signal.trim().toUpperCase(Locale.ROOT);
            if (!("UP".equals(signal) || "DOWN".equals(signal))) signal = "WAIT";
            String details = result == null || result.details == null ? "No Kronos result returned." : result.details;
            final String finalSignal = signal;
            final String finalDetails = details;
            runOnUiThread(() -> {
                prefs.edit()
                        .putString("kronos_latest_signal", finalSignal)
                        .putString("kronos_latest_details", finalDetails)
                        .putString("kronos_status", "UP".equals(finalSignal) || "DOWN".equals(finalSignal) ? "FORECAST READY" : "WAIT / CHECK FEED")
                        .putString("kronos_feed_note", finalDetails)
                        .putString("latest_ai_signal", finalSignal)
                        .putString("latest_ai_details", finalDetails)
                        .putString("scan_status", "KRONOS " + finalSignal)
                        .putString("scan_message", finalDetails)
                        .apply();
                saveHistory(finalSignal, 0, 0, 0, "KRONOS_MARKET_FORECAST");
                refreshLatestResult();
                refreshDashboardWebView();
                Toast.makeText(this, "Kronos forecast finished: " + finalSignal, Toast.LENGTH_LONG).show();
            });
        }).start();
    }
    private void showScalpingEngineSettings() {
        final String[] engineValues = {"SMOLVLM", "KRONOS", "HYBRID"};
        final String[] labels = {
                "SmolVLM — visual chart analysis (on-device)",
                "Kronos-mini — phone-fetches market candles; local model port still required",
                "Hybrid — standalone fast chart-image analysis (independent)"
        };
        String selected = prefs.getString("scalping_engine", "SMOLVLM");
        int checked = "KRONOS".equalsIgnoreCase(selected) ? 1
                : ("HYBRID".equalsIgnoreCase(selected) ? 2 : 0);
        new AlertDialog.Builder(this)
                .setTitle("Scalping analysis engine")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    String engine = engineValues[which];
                    prefs.edit().putString("scalping_engine", engine).apply();
                    dialog.dismiss();
                    if ("KRONOS".equals(engine)) {
                        Toast.makeText(this,
                                "Kronos phone-only mode selected. The phone fetches BiQuote candles; real local inference stays WAIT until the dedicated Kronos model/tokenizer port is bundled and validated.",
                                Toast.LENGTH_LONG).show();
                        showKronosCandleJsonSettings();
                    } else if ("HYBRID".equals(engine)) {
                        Toast.makeText(this,
                                "Hybrid selected: standalone fast chart-image analyzer. No SmolVLM or Kronos dependency.",
                                Toast.LENGTH_LONG).show();
                        refreshDashboardWebView();
                    } else {
                        Toast.makeText(this, "SmolVLM visual engine selected.", Toast.LENGTH_SHORT).show();
                        refreshDashboardWebView();
                    }
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void showKronosServiceUrlSettings() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("https://your-deployed-kronos-api.example");
        input.setText(prefs.getString("kronos_service_url", ""));
        new AlertDialog.Builder(this)
                .setTitle("Kronos API URL")
                .setMessage("Important: this build fetches BiQuote candles directly on the phone, but genuine Kronos-mini inference still runs on a separate HTTPS API. Mobile data alone does not host the model. On-device Kronos inference is not implemented yet.")
                .setView(input)
                .setPositiveButton("SAVE & CHOOSE MARKET", (dialog, which) -> {
                    String url = input.getText().toString().trim().replaceAll("/+$", "");
                    prefs.edit().putString("kronos_service_url", url).apply();
                    showKronosCandleJsonSettings();
                })
                .setNegativeButton("CANCEL", (dialog, which) -> refreshDashboardWebView())
                .show();
    }

    private void showKronosCandleJsonSettings() {
        String[] intervals = {"1m", "5m", "15m", "30m", "1h", "4h", "1d"};
        String symbol = prefs.getString("kronos_symbol", "EURUSD");
        String interval = prefs.getString("kronos_interval", "1m");
        String[] choices = {"Select BigQuote instrument • " + symbol, "Select timeframe • " + interval,
                "Custom instrument…", "Custom timeframe…", "Test BigQuote API"};
        new AlertDialog.Builder(this).setTitle("KRONOS • BIGQUOTE SETTINGS")
                .setItems(choices, (dialog, which) -> {
                    if (which == 0) browseKronosSymbols();
                    else if (which == 1) new AlertDialog.Builder(this).setTitle("Choose timeframe")
                            .setItems(intervals, (d, selected) -> {
                                prefs.edit().putString("kronos_interval", intervals[selected]).remove("kronos_candles_json").apply();
                                refreshDashboardWebView();
                            }).show();
                    else if (which == 2 || which == 3) {
                        EditText input = new EditText(this); input.setSingleLine(true);
                        if (which == 2) {
                            input.setHint("e.g. EURUSD or XAUUSD");
                            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
                            input.setText(symbol);
                        } else {
                            input.setHint("e.g. 5m or 1h"); input.setText(interval);
                            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                        }
                        final boolean isSymbol = which == 2;
                        new AlertDialog.Builder(this).setTitle(isSymbol ? "Custom BigQuote instrument" : "Custom timeframe")
                                .setMessage(isSymbol ? "Enter a BigQuote symbol." : "Documented intervals: 1m, 5m, 15m, 30m, 1h, 4h, 1d. Other intervals may be rejected.")
                                .setView(input).setPositiveButton("SAVE", (d, w) -> {
                                    String value = input.getText().toString().trim();
                                    if (isSymbol) value = value.toUpperCase(Locale.ROOT);
                                    else value = value.toLowerCase(Locale.ROOT);
                                    if (isSymbol && !value.matches("[A-Z0-9._-]{1,40}")) {
                                        Toast.makeText(this, "Invalid instrument symbol.", Toast.LENGTH_LONG).show(); return;
                                    }
                                    if (!isSymbol && !value.matches("[0-9]+[mhd]")) {
                                        Toast.makeText(this, "Enter a timeframe like 5m or 1h.", Toast.LENGTH_LONG).show(); return;
                                    }
                                    prefs.edit().putString(isSymbol ? "kronos_symbol" : "kronos_interval", value)
                                            .remove("kronos_candles_json").apply();
                                    refreshDashboardWebView();
                                }).setNegativeButton("CANCEL", null).show();
                    } else checkBigQuoteConnection();
                }).setNegativeButton("CLOSE", null).show();
    }

    private void browseKronosSymbols() {
        Toast.makeText(this, "Loading BigQuote instruments…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new java.net.URL("https://biquote.io/api/symbols?quotedWithinDays=7").openConnection();
                connection.setRequestMethod("GET"); connection.setConnectTimeout(12000); connection.setReadTimeout(20000);
                connection.setRequestProperty("Accept", "application/json");
                int status = connection.getResponseCode();
                InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
                String body;
                try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096]; int count;
                    while (in != null && (count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                    body = out.toString("UTF-8");
                }
                if (status < 200 || status >= 300) throw new IllegalStateException("BigQuote HTTP " + status);
                JSONArray symbols;
                try { symbols = new JSONArray(body); }
                catch (Exception ignored) {
                    JSONObject json = new JSONObject(body); symbols = json.optJSONArray("symbols");
                    if (symbols == null) symbols = json.optJSONArray("items");
                }
                if (symbols == null || symbols.length() == 0) throw new IllegalStateException("No instruments returned");
                java.util.ArrayList<String> labels = new java.util.ArrayList<>();
                java.util.ArrayList<String> names = new java.util.ArrayList<>();
                for (int i = 0; i < symbols.length(); i++) {
                    JSONObject item = symbols.optJSONObject(i); if (item == null) continue;
                    String name = item.optString("name", item.optString("symbol", "")).trim();
                    if (name.isEmpty()) continue;
                    String desc = item.optString("description", item.optString("type", "")).trim();
                    names.add(name); labels.add(desc.isEmpty() ? name : name + " — " + desc);
                }
                runOnUiThread(() -> {
                    if (labels.isEmpty()) { Toast.makeText(this, "BigQuote returned no readable instruments.", Toast.LENGTH_LONG).show(); return; }
                    new AlertDialog.Builder(this).setTitle("BigQuote instruments (" + labels.size() + ")")
                            .setItems(labels.toArray(new String[0]), (dialog, selected) -> {
                                prefs.edit().putString("kronos_symbol", names.get(selected)).remove("kronos_candles_json").apply();
                                refreshDashboardWebView(); showKronosCandleJsonSettings();
                            }).setNegativeButton("CLOSE", null).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("BigQuote list unavailable")
                        .setMessage("Could not load BigQuote's public catalogue.\n" + error.getMessage()
                                + "\nNo BigQuote API key is required; check your internet connection.")
                        .setPositiveButton("RETRY", (dialog, w) -> browseKronosSymbols())
                        .setNegativeButton("CLOSE", null).show());
            } finally { if (connection != null) connection.disconnect(); }
        }).start();
    }

    private void checkBigQuoteConnection() {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new java.net.URL("https://biquote.io/api/EURUSD").openConnection();
                connection.setConnectTimeout(10000); connection.setReadTimeout(12000);
                int status = connection.getResponseCode();
                InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
                String body;
                try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[2048]; int count;
                    while (in != null && (count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                    body = out.toString("UTF-8");
                }
                if (status < 200 || status >= 300) throw new IllegalStateException("HTTP " + status);
                JSONObject quote = new JSONObject(body);
                String message = "BigQuote connected. EURUSD mid: " + quote.optString("mid", "unavailable")
                        + ". This market-data API is public and requires no key. Kronos inference is a separate engine.";
                runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("BigQuote API test").setMessage(message).setPositiveButton("OK", null).show());
            } catch (Exception error) {
                runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("BigQuote connection failed")
                        .setMessage(error.getMessage()).setPositiveButton("OK", null).show());
            } finally { if (connection != null) connection.disconnect(); }
        }).start();
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
                            // Rebuild Configuration so the button immediately shows the saved duration.
                            openPage("CONFIGURATION");
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
                        // Rebuild Configuration so the custom value is visible immediately.
                        openPage("CONFIGURATION");
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
