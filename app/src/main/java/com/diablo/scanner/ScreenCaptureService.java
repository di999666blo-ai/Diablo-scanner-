package com.diablo.scanner;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONArray;
import org.json.JSONObject;

public class ScreenCaptureService extends Service {

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    public static final String ACTION_STOP_CAPTURE =
            "com.diablo.scanner.STOP_CAPTURE";

    public static final String ACTION_ANALYZE_ONCE =
            "com.diablo.scanner.ANALYZE_ONCE";

    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "diablo_screen_capture";
    private static final long ANALYSIS_INTERVAL_MS = 5000L;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;

    private HandlerThread workerThread;
    private Handler workerHandler;

    private volatile boolean captureStarted = false;
    private boolean stopping = false;

    // The scanner analyses only after the user explicitly taps the D bubble.
    private volatile boolean analysisRequested = false;
    private volatile boolean timedScanActive = false;
    private volatile long timedScanStartedAt = 0L;
    private volatile long timedScanDurationMs = 30000L;
    private volatile Bitmap latestTimedChart;

    /*
     * AI runs separately from screen capture.
     * This prevents a slow model from freezing the live capture.
     */
    private final ExecutorService aiExecutor =
            Executors.newSingleThreadExecutor();

    private final AtomicBoolean aiRunning =
            new AtomicBoolean(false);

    private final Runnable timedScanRunnable = new Runnable() {
        @Override public void run() {
            if (!timedScanActive || workerHandler == null || !captureStarted) return;
            SharedPreferences state = getSharedPreferences("diablo_scanner", MODE_PRIVATE);
            long elapsed = Math.max(0L, System.currentTimeMillis() - timedScanStartedAt);
            long remaining = Math.max(0L, timedScanDurationMs - elapsed);
            if (remaining <= 0L) {
                if (aiRunning.get()) {
                    workerHandler.postDelayed(this, 1000L);
                    return;
                }
                finishTimedScan();
                return;
            }
            if (!aiRunning.get()) {
                analysisRequested = true;
                state.edit().putString("scan_status", "SCANNING")
                        .putString("scan_message", "TIMED SIGNAL • observing live chart: "
                                + (elapsed / 1000L) + "/" + (timedScanDurationMs / 1000L)
                                + " seconds • sampling about every 5 seconds.")
                        .apply();
            }
            workerHandler.postDelayed(this, 5000L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "DIABLO Screen Capture",
                    NotificationManager.IMPORTANCE_LOW
            );

            channel.setDescription(
                    "DIABLO live chart screen analysis"
            );

            NotificationManager manager =
                    getSystemService(NotificationManager.class);

            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setContentTitle("DIABLO SCANNER")
                .setContentText("Live screen analysis is running")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
    }

    private void startCaptureNotification() {
        Notification notification = createNotification();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_STOP_CAPTURE.equals(intent.getAction())) {
            stopCapture();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_ANALYZE_ONCE.equals(intent.getAction())) {
            if (captureStarted) {
                SharedPreferences state = getSharedPreferences("diablo_scanner", MODE_PRIVATE);
                boolean timedMode = "TIMED".equalsIgnoreCase(state.getString("trading_mode", "TIMED"));
                if (timedMode && timedScanActive) return START_NOT_STICKY;
                if (timedMode && workerHandler != null) {
                    String timedEngine = state.getString("timed_engine", "HYBRID");
                    boolean fastObservation = !"SMOLVLM".equalsIgnoreCase(timedEngine)
                            && !"COMBINED".equalsIgnoreCase(timedEngine);
                    timedScanDurationMs = Math.max(5000L, Math.min(300000L,
                            state.getLong("timed_scan_duration_ms", 30000L)));
                    timedScanStartedAt = System.currentTimeMillis();
                    timedScanActive = fastObservation;
                    workerHandler.removeCallbacks(timedScanRunnable);
                    state.edit().putBoolean("timed_scan_active", fastObservation)
                            .putLong("timed_scan_started_at", timedScanStartedAt)
                            .putLong("timed_scan_duration_ms", timedScanDurationMs)
                            .putInt("timed_samples", 0).putInt("timed_up_votes", 0)
                            .putInt("timed_down_votes", 0).putInt("timed_wait_votes", 0)
                            .putString("timed_last_details", "")
                            .putString("scan_status", "SCANNING")
                            .putString("scan_message", fastObservation
                                    ? "TIMED SIGNAL • starting fast live observation for " + (timedScanDurationMs / 1000L) + " seconds..."
                                    : "TIMED SIGNAL • single analysis using " + timedEngine + "; slow results will be rejected if stale.")
                            .apply();
                    analysisRequested = true;
                    if (fastObservation) workerHandler.postDelayed(timedScanRunnable, 5000L);
                } else {
                    analysisRequested = true;
                    state.edit().putBoolean("timed_scan_active", false)
                            .putString("scan_status", "SCANNING")
                            .putString("scan_message", "Capturing the current chart for one analysis...")
                            .apply();
                }
            }
            return START_NOT_STICKY;
        }

        if (captureStarted) {
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra(
                EXTRA_RESULT_CODE,
                0
        );

        Intent resultData;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            resultData = intent.getParcelableExtra(
                    EXTRA_RESULT_DATA,
                    Intent.class
            );
        } else {
            resultData = intent.getParcelableExtra(
                    EXTRA_RESULT_DATA
            );
        }

        if (resultCode == 0 || resultData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            startCaptureNotification();

            MediaProjectionManager manager =
                    (MediaProjectionManager)
                            getSystemService(MEDIA_PROJECTION_SERVICE);

            if (manager == null) {
                stopSelf();
                return START_NOT_STICKY;
            }

            projection = manager.getMediaProjection(
                    resultCode,
                    resultData
            );

            if (projection == null) {
                stopSelf();
                return START_NOT_STICKY;
            }

            startCapture();

        } catch (Exception e) {
            stopCapture();
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void startCapture() {

        if (projection == null || captureStarted) {
            return;
        }

        android.util.DisplayMetrics metrics =
                getResources().getDisplayMetrics();

        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int density = metrics.densityDpi;

        if (width <= 0 || height <= 0) {
            stopSelf();
            return;
        }

        workerThread = new HandlerThread("DiabloCaptureWorker");
        workerThread.start();

        workerHandler = new Handler(workerThread.getLooper());

        imageReader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2
        );

        projection.registerCallback(
                new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        stopCapture();
                        stopSelf();
                    }
                },
                workerHandler
        );

        imageReader.setOnImageAvailableListener(
                this::processCapturedFrame,
                workerHandler
        );

        virtualDisplay = projection.createVirtualDisplay(
                "DIABLO_SCREEN",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                workerHandler
        );

        if (virtualDisplay == null) {
            stopCapture();
            stopSelf();
            return;
        }

        captureStarted = true;
        stopping = false;
        getSharedPreferences("diablo_scanner", MODE_PRIVATE)
                .edit()
                .putBoolean("screen_capture_active", true)
                .putString("scan_status", "WAIT")
                .putString("scan_message", "Ready. Tap the D bubble to analyse one chart.")
                .apply();
    }

    private void processCapturedFrame(ImageReader reader) {

        Image image = null;
        Bitmap fullBitmap = null;
        Bitmap croppedBitmap = null;

        try {
            image = reader.acquireLatestImage();

            if (image == null) {
                return;
            }
            // Do not analyse continuously. A tap requests exactly one frame.
            if (!analysisRequested) {
                return;
            }
            analysisRequested = false;

            Image.Plane[] planes = image.getPlanes();

            if (planes == null || planes.length == 0) {
                return;
            }

            Image.Plane plane = planes[0];
            ByteBuffer buffer = plane.getBuffer();

            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();

            int imageWidth = image.getWidth();
            int imageHeight = image.getHeight();

            if (pixelStride <= 0 ||
                    imageWidth <= 0 ||
                    imageHeight <= 0) {
                return;
            }

            int rowPadding =
                    rowStride - pixelStride * imageWidth;

            int paddedWidth =
                    imageWidth +
                    Math.max(0, rowPadding / pixelStride);

            fullBitmap = Bitmap.createBitmap(
                    paddedWidth,
                    imageHeight,
                    Bitmap.Config.ARGB_8888
            );

            buffer.rewind();
            fullBitmap.copyPixelsFromBuffer(buffer);

            croppedBitmap = Bitmap.createBitmap(
                    fullBitmap,
                    0,
                    0,
                    imageWidth,
                    imageHeight
            );

            analyseCapturedBitmap(croppedBitmap);

        } catch (Exception ignored) {
            // A failed requested frame must return to WAIT so the user can retry.
            SharedPreferences state = getSharedPreferences("diablo_scanner", MODE_PRIVATE);
            if ("SCANNING".equals(state.getString("scan_status", "WAIT"))) {
                state.edit()
                        .putString("scan_status", "WAIT")
                        .putString("scan_message", "Could not read this frame. Keep the chart visible and tap D to retry.")
                        .apply();
            }
        } finally {

            if (image != null) {
                image.close();
            }

            if (croppedBitmap != null &&
                    !croppedBitmap.isRecycled()) {
                croppedBitmap.recycle();
            }

            if (fullBitmap != null &&
                    !fullBitmap.isRecycled()) {
                fullBitmap.recycle();
            }
        }
    }

    // ================================================
    // LIVE CHART ANALYSIS
    // ================================================

    private void analyseCapturedBitmap(Bitmap bitmap) {

        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();

            if (width < 50 || height < 50) {
                return;
            }

            // Crop the central chart area before passing it to the bundled local vision model.
            int left = Math.max(0, (int)(width * 0.05));
            int right = Math.min(width, (int)(width * 0.95));
            int top = Math.max(0, (int)(height * 0.12));
            int bottom = Math.min(height, (int)(height * 0.88));
            if (right - left < 50 || bottom - top < 50) {
                SharedPreferences state = getSharedPreferences("diablo_scanner", MODE_PRIVATE);
                state.edit().putString("scan_status", "WAIT")
                        .putString("scan_message", "Chart area is too small. Open the chart full-screen and tap D again.")
                        .apply();
                return;
            }
            Bitmap chartOnly = null;
            try {
                chartOnly = Bitmap.createBitmap(bitmap, left, top, right-left, bottom-top);
                startAIAnalysis(chartOnly);
            } finally {
                if (chartOnly != null && !chartOnly.isRecycled()) chartOnly.recycle();
            }

        } catch (Exception ignored) {
            SharedPreferences state = getSharedPreferences("diablo_scanner", MODE_PRIVATE);
            state.edit()
                    .putString("scan_status", "WAIT")
                    .putString("scan_message", "Chart analysis could not start. Tap D to retry.")
                    .apply();
        }
    }

    // ================================================
    // RUN THE SHARED ON-DEVICE CHART ANALYZER
    // ================================================
    private void startAIAnalysis(Bitmap capturedChart) {
        if (capturedChart == null || capturedChart.isRecycled()) return;
        if (!aiRunning.compareAndSet(false, true)) return;
        final long analysisStartedAt = System.currentTimeMillis();
        final Bitmap analysisBitmap;
        try {
            analysisBitmap = capturedChart.copy(Bitmap.Config.ARGB_8888, false);
            if (analysisBitmap == null) {
                aiRunning.set(false);
                getSharedPreferences("diablo_scanner", MODE_PRIVATE).edit()
                        .putString("scan_status", "AI_ERROR")
                        .putString("scan_message", "Could not prepare chart image for AI analysis.")
                        .apply();
                return;
            }
        } catch (Exception error) {
            aiRunning.set(false);
            getSharedPreferences("diablo_scanner", MODE_PRIVATE).edit()
                    .putString("scan_status", "AI_ERROR")
                    .putString("scan_message", "Could not prepare chart image: " + error.getClass().getSimpleName())
                    .apply();
            return;
        }
        SharedPreferences scanPrefs = getSharedPreferences("diablo_scanner", MODE_PRIVATE);
        String selectedMode = scanPrefs.getString("trading_mode", "TIMED");
        boolean scalpingMode = "SCALPING".equalsIgnoreCase(selectedMode);
        final boolean timedObservation = scanPrefs.getBoolean("timed_scan_active", false);
        final String selectedTimedEngine = scanPrefs.getString("timed_engine", "HYBRID");
        // MainActivity keeps legacy timed_smolvlm_enabled synchronized for older UI clients.
        final boolean optionalSmolAssist = "COMBINED".equalsIgnoreCase(selectedTimedEngine);
        final String selectedScalpingEngine = scanPrefs.getString("scalping_engine", "SMOLVLM");
        if (!scalpingMode && timedObservation && optionalSmolAssist) {
            Bitmap cached = analysisBitmap.copy(Bitmap.Config.ARGB_8888, false);
            Bitmap previous = latestTimedChart;
            latestTimedChart = cached;
            if (previous != null && !previous.isRecycled()) previous.recycle();
        }
        scanPrefs.edit()
                .putString("scan_status", "AI_PROCESSING")
                .putString("scan_message", scalpingMode
                        ? ("KRONOS".equalsIgnoreCase(selectedScalpingEngine)
                            ? "Fetching BigQuote candles on-device; checking the local Kronos runtime..."
                            : ("HYBRID".equalsIgnoreCase(selectedScalpingEngine)
                                ? "Running standalone Hybrid fast candle analysis (no SmolVLM or Kronos dependency)..."
                                : "Preparing SmolVLM AI for Scalping Setup..."))
                        : "Running fast Timed Signal chart strategy...")
                .apply();
        try {
            aiExecutor.execute(() -> {
                try {
                    // Timed Signal always uses the fast chart analyzer; optional SmolVLM
                    // is a secondary visual check. Scalping engine selection is explicit.
                    LocalVisionAI.Result result;
                    if (scalpingMode) {
                        String selectedEngine = selectedScalpingEngine;
                        if ("KRONOS".equalsIgnoreCase(selectedEngine)) {
                            // Phone-only contract: fetch structured BigQuote candles locally,
                            // then invoke only the dedicated local Kronos runtime. Never route to a host.
                            try {
                                JSONObject candleInput = KronosRuntimeClient.fetchLocalCandleInput(getApplicationContext());
                                result = KronosLocalRuntime.forecast(getApplicationContext(), candleInput.toString());
                            } catch (Exception feedError) {
                                result = new LocalVisionAI.Result("WAIT", "KRONOS MARKET/INFERENCE ERROR: "
                                        + feedError.getClass().getSimpleName() + " — "
                                        + (feedError.getMessage() == null ? "no details" : feedError.getMessage())
                                        + ". No fallback engine was used.");
                            }
                        } else if ("HYBRID".equalsIgnoreCase(selectedEngine)) {
                            // HYBRID_STANDALONE: this engine owns its fast chart-image
                            // feature extraction and decision logic. It must never call
                            // SmolVLM or Kronos as a hidden dependency.
                            result = ChartAnalyzer.analyze(
                                    getApplicationContext(), analysisBitmap);
                        } else {
                            result = LocalVisionAI.analyze(getApplicationContext(), analysisBitmap);
                        }
                    } else if ("SMOLVLM".equalsIgnoreCase(selectedTimedEngine)) {
                        result = LocalVisionAI.analyze(getApplicationContext(), analysisBitmap);
                    } else {
                        result = ChartAnalyzer.analyze(getApplicationContext(), analysisBitmap);
                        if (optionalSmolAssist && !timedObservation && result != null) {
                            LocalVisionAI.Result visualResult =
                                    LocalVisionAI.analyze(getApplicationContext(), analysisBitmap);
                            result = combineTimedHybrid(result, visualResult);
                        }
                    }
                    if (result != null && !scalpingMode) {
                        long elapsedInferenceMs = Math.max(0L, System.currentTimeMillis() - analysisStartedAt);
                        long expiryMs = scanPrefs.getLong("trade_duration_ms", 60000L);
                        long staleLimitMs = Math.min(20000L, Math.max(5000L, expiryMs / 4L));
                        if (elapsedInferenceMs > staleLimitMs) {
                            result = new LocalVisionAI.Result("WAIT",
                                    "STALE TIMED SIGNAL REJECTED: analysis took " + (elapsedInferenceMs / 1000L)
                                            + " seconds, exceeding the freshness limit of " + (staleLimitMs / 1000L)
                                            + " seconds for the selected " + (expiryMs / 1000L)
                                            + "-second trade duration. No late UP/DOWN entry was issued. Retry with Hybrid Fast or a clearer chart.");
                        }
                    }
                    if (result != null) {
                        SharedPreferences latestState = getSharedPreferences("diablo_scanner", MODE_PRIVATE);
                        long latestScanStartedAt = latestState.getLong("scan_started_at", analysisStartedAt);
                        String latestStatus = latestState.getString("scan_status", "WAIT");
                        String latestMessage = latestState.getString("scan_message", "");
                        boolean supersededByNewScan = latestScanStartedAt > analysisStartedAt;
                        boolean timedOutAtOverlay = "AI_ERROR".equals(latestStatus)
                                && latestMessage.toUpperCase(Locale.ROOT).contains("HYBRID FAST TIMEOUT");
                        // A slow/stale worker must not overwrite a timeout or a newer scan.
                        if (!supersededByNewScan && !timedOutAtOverlay) {
                            saveAIResult(result);
                        }
                    }
                } catch (Exception error) {
                    SharedPreferences state = getSharedPreferences("diablo_scanner", MODE_PRIVATE);
                    state.edit().putString("scan_status", "AI_ERROR")
                            .putString("scan_message", "Chart analysis failed: " + error.getClass().getSimpleName() +
                                    (error.getMessage() == null ? "" : " — " + error.getMessage()) + ". Tap D to retry.")
                            .apply();
                } finally {
                    if (!analysisBitmap.isRecycled()) analysisBitmap.recycle();
                    aiRunning.set(false);
                }
            });
        } catch (Exception error) {
            if (!analysisBitmap.isRecycled()) analysisBitmap.recycle();
            aiRunning.set(false);
        }
    }

    /**
     * Keeps the fast pixel/candle analyzer primary. Optional SmolVLM may
     * corroborate it, but an inference error does not erase a valid fast read.
     * Opposite directional outputs are downgraded to WAIT.
     */
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
                    fast.details + "\n\nOPTIONAL SMOLVLM CHECK FAILED; fast hybrid result retained.\n"
                            + visualDetails);
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

    // ================================================
    // SAVE AI RESULT TO HISTORY
    // ================================================

    private void saveAIResult(LocalVisionAI.Result result) {

        if (result == null) {
            return;
        }

        String aiSignal = result.signal == null
                ? "WAIT"
                : result.signal.trim().toUpperCase(Locale.ROOT);

        if (!"UP".equals(aiSignal)
                && !"DOWN".equals(aiSignal)) {
            aiSignal = "WAIT";
        }

        String mode = getSharedPreferences("diablo_scanner", MODE_PRIVATE)
                .getString("trading_mode", "TIMED");

        String displaySignal;
        if ("SCALPING".equalsIgnoreCase(mode)) {
            if ("UP".equals(aiSignal)) {
                displaySignal = "BUY";
            } else if ("DOWN".equals(aiSignal)) {
                displaySignal = "SELL";
            } else {
                displaySignal = "WAIT";
            }
        } else {
            if ("UP".equals(aiSignal)) {
                displaySignal = "UP BIAS";
            } else if ("DOWN".equals(aiSignal)) {
                displaySignal = "DOWN BIAS";
            } else {
                displaySignal = "WAIT";
            }
        }

        String details = result.details == null
                ? "No AI explanation returned."
                : result.details.trim();

        int score = extractConfidence(details);

        SharedPreferences prefs = getSharedPreferences(
                "diablo_scanner",
                MODE_PRIVATE
        );

        if (!"SCALPING".equalsIgnoreCase(mode) && prefs.getBoolean("timed_scan_active", false)) {
            int samples = prefs.getInt("timed_samples", 0) + 1;
            int ups = prefs.getInt("timed_up_votes", 0) + ("UP".equals(aiSignal) ? 1 : 0);
            int downs = prefs.getInt("timed_down_votes", 0) + ("DOWN".equals(aiSignal) ? 1 : 0);
            int waits = prefs.getInt("timed_wait_votes", 0) + ("WAIT".equals(aiSignal) ? 1 : 0);
            long elapsed = Math.max(0L, System.currentTimeMillis() - prefs.getLong("timed_scan_started_at", System.currentTimeMillis()));
            long duration = prefs.getLong("timed_scan_duration_ms", 30000L);
            prefs.edit().putInt("timed_samples", samples).putInt("timed_up_votes", ups)
                    .putInt("timed_down_votes", downs).putInt("timed_wait_votes", waits)
                    .putString("timed_last_details", details).apply();
            if (elapsed < duration) {
                prefs.edit().putString("scan_status", "SCANNING")
                        .putString("scan_message", "TIMED SIGNAL • live observation "
                                + (elapsed / 1000L) + "/" + (duration / 1000L) + " sec • samples: " + samples
                                + " • UP " + ups + " / DOWN " + downs + " / WAIT " + waits
                                + "\n" + details).apply();
                return;
            }
            timedScanActive = false;
            prefs.edit().putBoolean("timed_scan_active", false).apply();
            aiSignal = chooseTimedVote(samples, ups, downs, duration);
            displaySignal = "UP".equals(aiSignal) ? "UP BIAS" : "DOWN".equals(aiSignal) ? "DOWN BIAS" : "WAIT";
            details = details + "\n\nTIMED OBSERVATION SUMMARY: " + samples + " live samples; UP " + ups
                    + ", DOWN " + downs + ", WAIT " + waits + ". Final result uses the vote balance and agreement; this is not a verified win probability.";
            score = extractConfidence(details);
        }

        try {
            JSONArray old = new JSONArray(
                    prefs.getString("history", "[]")
            );

            JSONArray updated = new JSONArray();

            JSONObject item = new JSONObject();

            item.put(
                    "time",
                    new SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss",
                            Locale.getDefault()
                    ).format(new Date())
            );

            String selectedEngine = prefs.getString("scalping_engine", "SMOLVLM");
            String modelUsed;
            if ("SCALPING".equalsIgnoreCase(mode)) {
                modelUsed = "KRONOS".equalsIgnoreCase(selectedEngine) ? "Kronos"
                        : "HYBRID".equalsIgnoreCase(selectedEngine) ? "Hybrid Fast"
                        : "SmolVLM";
            } else {
                String timedEngine = prefs.getString("timed_engine", "HYBRID");
                modelUsed = "SMOLVLM".equalsIgnoreCase(timedEngine) ? "SmolVLM"
                        : "COMBINED".equalsIgnoreCase(timedEngine) ? "Hybrid Fast + SmolVLM"
                        : "Hybrid Fast";
            }
            item.put("signal", displaySignal);
            item.put("model", modelUsed);
            item.put("engine", selectedEngine);
            item.put("mode", mode);
            item.put("timeframe", prefs.getString("chart_timeframe", "1m"));
            item.put("tradeDuration", prefs.getLong("trade_duration_ms", 60000L));
            item.put("score", score);
            item.put("green", 0);
            item.put("red", 0);
            item.put("source", "SCALPING".equalsIgnoreCase(mode) ? "LIVE_AI" : "LIVE_TIMED");
            item.put("details", details);

            updated.put(item);

            for (int i = 0; i < old.length() && i < 49; i++) {
                updated.put(old.get(i));
            }

            String lowerDetails = details.toLowerCase(Locale.ROOT);
            boolean engineError = lowerDetails.contains("timed out")
                    || lowerDetails.contains("local ai error")
                    || lowerDetails.contains("engine failed")
                    || lowerDetails.contains("engine is missing")
                    || lowerDetails.contains("unsupported")
                    || lowerDetails.contains("could not")
                    || lowerDetails.contains("missing or incomplete");
            String finalStatus = engineError ? "AI_ERROR"
                    : "WAIT".equals(aiSignal) ? "ANALYSIS_COMPLETE_WAIT"
                    : "SCALPING".equalsIgnoreCase(mode) ? "AI_READY" : "SIGNAL_READY";
            String statusMessage = engineError
                    ? "AI ERROR • " + details
                    : "WAIT".equals(aiSignal)
                    ? "Analysis complete. No reliable direction was returned. " + details
                    : ("SCALPING".equalsIgnoreCase(mode) ? "AI READY • " : "TIMED SIGNAL READY • ") + displaySignal + " • " + details;

            prefs.edit()
                    .putString("history", updated.toString())
                    .putString("latest_ai_details", details)
                    .putString("latest_ai_signal", displaySignal)
                    .putInt("latest_ai_score", score)
                    .putString("latest_ai_time", item.getString("time"))
                    .putString("scan_status", finalStatus)
                    .putString("scan_message", statusMessage)
                    .apply();

        } catch (Exception ignored) {
            // A history error must not stop the scanner.
        }
    }

    private String chooseTimedVote(int samples, int ups, int downs, long duration) {
        int minimumSamples = duration <= 10000L ? 1 : 2;
        if (samples < minimumSamples || ups == downs) return "WAIT";
        int threshold = Math.max(1, (int)Math.ceil(samples * 0.40));
        if (ups > downs && ups >= threshold) return "UP";
        if (downs > ups && downs >= threshold) return "DOWN";
        return "WAIT";
    }

    private void finishTimedScan() {
        SharedPreferences prefs = getSharedPreferences("diablo_scanner", MODE_PRIVATE);
        int samples = prefs.getInt("timed_samples", 0);
        int ups = prefs.getInt("timed_up_votes", 0);
        int downs = prefs.getInt("timed_down_votes", 0);
        int waits = prefs.getInt("timed_wait_votes", 0);
        long duration = prefs.getLong("timed_scan_duration_ms", 30000L);
        String lastDetails = prefs.getString("timed_last_details", "No readable chart sample was returned.");
        timedScanActive = false;
        prefs.edit().putBoolean("timed_scan_active", false).apply();
        if (samples <= 0) {
            releaseLatestTimedChart();
            prefs.edit().putString("scan_status", "ANALYSIS_COMPLETE_WAIT")
                    .putString("scan_message", "TIMED SIGNAL • WAIT • no readable live samples were collected. Keep the chart visible and try again.")
                    .apply();
            return;
        }
        String signal = chooseTimedVote(samples, ups, downs, duration);
        String summary = lastDetails + "\n\nTIMED OBSERVATION SUMMARY: " + samples
                + " live samples across " + (duration / 1000L) + " seconds; UP " + ups
                + ", DOWN " + downs + ", WAIT " + waits
                + ". " + ("WAIT".equals(signal) ? "Evidence did not agree strongly enough; final signal is WAIT." : "Final direction is the majority supported direction.")
                + " This is experimental screen-image analysis, not a verified win probability.";

        Bitmap assistBitmap = latestTimedChart;
        latestTimedChart = null;
        boolean optionalAssist = prefs.getBoolean("timed_smolvlm_enabled", false);
        if (optionalAssist && assistBitmap != null && !assistBitmap.isRecycled()
                && aiRunning.compareAndSet(false, true)) {
            prefs.edit().putString("scan_status", "AI_PROCESSING")
                    .putString("scan_message", "Timed observation finished. Running optional SmolVLM review once; fast sampling is complete.")
                    .apply();
            try {
                aiExecutor.execute(() -> {
                    try {
                        LocalVisionAI.Result fastResult = new LocalVisionAI.Result(signal, summary);
                        LocalVisionAI.Result visualResult =
                                LocalVisionAI.analyze(getApplicationContext(), assistBitmap);
                        saveAIResult(combineTimedHybrid(fastResult, visualResult));
                    } catch (Exception error) {
                        saveAIResult(new LocalVisionAI.Result(signal,
                                summary + "\n\nOptional SmolVLM review failed; fast hybrid result retained."));
                    } finally {
                        if (!assistBitmap.isRecycled()) assistBitmap.recycle();
                        aiRunning.set(false);
                    }
                });
            } catch (Exception error) {
                if (!assistBitmap.isRecycled()) assistBitmap.recycle();
                aiRunning.set(false);
                saveAIResult(new LocalVisionAI.Result(signal,
                        summary + "\n\nOptional SmolVLM review could not start; fast hybrid result retained."));
            }
            return;
        }
        if (assistBitmap != null && !assistBitmap.isRecycled()) assistBitmap.recycle();
        saveAIResult(new LocalVisionAI.Result(signal, summary));
    }

    private void releaseLatestTimedChart() {
        Bitmap previous = latestTimedChart;
        latestTimedChart = null;
        if (previous != null && !previous.isRecycled()) previous.recycle();
    }

    private int extractConfidence(String details) {

        if (details == null) {
            return 0;
        }

        String[] lines = details.toUpperCase(Locale.ROOT)
                .split("\\n");

        for (String line : lines) {

            String cleaned = line.trim();

            if (cleaned.startsWith("CONFIDENCE:")) {

                String value = cleaned.substring(
                        "CONFIDENCE:".length()
                ).trim();

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
                    int parsed = Integer.parseInt(digits.toString());

                    return Math.max(0, Math.min(100, parsed));

                } catch (Exception ignored) {
                    return 0;
                }
            }
        }

        return 0;
    }

    // ================================================
    // SAVE NORMAL LIVE SCAN RESULT
    // ================================================

    private void saveLiveResult(
            SharedPreferences prefs,
            String signal,
            int score,
            long green,
            long redPixels
    ) throws Exception {

        JSONArray old = new JSONArray(
                prefs.getString("history", "[]")
        );

        JSONArray updated = new JSONArray();

        JSONObject item = new JSONObject();

        item.put(
                "time",
                new SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss",
                        Locale.getDefault()
                ).format(new Date())
        );

        item.put("signal", signal);
        item.put("score", score);
        item.put("green", green);
        item.put("red", redPixels);
        item.put("source", "LIVE_SCREEN");

        updated.put(item);

        for (int i = 0; i < old.length() && i < 49; i++) {
            updated.put(old.get(i));
        }

        prefs.edit()
                .putString("history", updated.toString())
                .putString("scan_status", "READY")
                .putString("scan_message", signal + " • Visual fallback used; not AI.")
                .apply();
    }

    // ================================================
    // STOP SCREEN CAPTURE
    // ================================================

    private void stopCapture() {

        if (stopping) {
            return;
        }

        stopping = true;
        captureStarted = false;
        analysisRequested = false;
        timedScanActive = false;
        releaseLatestTimedChart();
        if (workerHandler != null) workerHandler.removeCallbacks(timedScanRunnable);
        getSharedPreferences("diablo_scanner", MODE_PRIVATE)
                .edit()
                .putBoolean("screen_capture_active", false)
                .putBoolean("timed_scan_active", false)
                .putString("scan_status", "WAIT")
                .putString("scan_message", "Screen capture stopped. Start it again to scan.")
                .apply();

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if (imageReader != null) {
            imageReader.setOnImageAvailableListener(null, null);
            imageReader.close();
            imageReader = null;
        }

        MediaProjection oldProjection = projection;
        projection = null;

        if (oldProjection != null) {
            try {
                oldProjection.stop();
            } catch (Exception ignored) {
            }
        }

        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
            workerHandler = null;
        }

        stopping = false;
    }

    @Override
    public void onDestroy() {
        stopCapture();

        // Stop any remaining AI work when the service is destroyed.
        aiExecutor.shutdownNow();

        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
