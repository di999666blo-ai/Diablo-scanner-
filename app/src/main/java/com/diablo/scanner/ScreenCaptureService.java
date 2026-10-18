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

    /*
     * AI runs separately from screen capture.
     * This prevents a slow model from freezing the live capture.
     */
    private final ExecutorService aiExecutor =
            Executors.newSingleThreadExecutor();

    private final AtomicBoolean aiRunning =
            new AtomicBoolean(false);

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
                analysisRequested = true;
                getSharedPreferences("diablo_scanner", MODE_PRIVATE)
                        .edit()
                        .putString("scan_status", "SCANNING")
                        .putString("scan_message", "Capturing the current chart for one analysis...")
                        .apply();
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

            // Approximate the visible chart area, excluding most of the app header
            // and bottom controls before sending the frame to the vision model.
            int left = (int) (width * 0.05);
            int right = (int) (width * 0.95);
            int top = (int) (height * 0.15);
            int bottom = (int) (height * 0.85);

            left = Math.max(0, Math.min(left, width - 1));
            right = Math.max(left + 1, Math.min(right, width));
            top = Math.max(0, Math.min(top, height - 1));
            bottom = Math.max(top + 1, Math.min(bottom, height));

            int chartWidth = right - left;
            int chartHeight = bottom - top;

            // Prefer the bundled SmolVLM vision model for the actual chart reading.
            // The colour-only method below is used only when the model pair is unavailable.
            if (LocalVisionAI.modelsReady(this)) {
                Bitmap chartOnly = null;
                try {
                    chartOnly = Bitmap.createBitmap(
                            bitmap,
                            left,
                            top,
                            chartWidth,
                            chartHeight
                    );
                    startAIAnalysis(chartOnly);
                } catch (Exception error) {
                    SharedPreferences state = getSharedPreferences("diablo_scanner", MODE_PRIVATE);
                    state.edit()
                            .putString("scan_status", "WAIT")
                            .putString("scan_message", "Could not crop the chart area. Tap D to retry.")
                            .apply();
                } finally {
                    if (chartOnly != null && chartOnly != bitmap && !chartOnly.isRecycled()) {
                        chartOnly.recycle();
                    }
                }
                return;
            }

            // Fallback only when the bundled/selected vision model is unavailable.

            int step = Math.max(
                    1,
                    Math.max(chartWidth, chartHeight) / 250
            );

            long green = 0;
            long red = 0;

            long upperGreen = 0;
            long upperRed = 0;

            long middleGreen = 0;
            long middleRed = 0;

            long lowerGreen = 0;
            long lowerRed = 0;

            for (int y = top; y < bottom; y += step) {
                for (int x = left; x < right; x += step) {

                    int pixel = bitmap.getPixel(x, y);

                    int r = Color.red(pixel);
                    int g = Color.green(pixel);
                    int b = Color.blue(pixel);

                    boolean isGreen =
                            g > 95 &&
                            g > r * 1.22 &&
                            g > b * 1.08;

                    boolean isRed =
                            r > 105 &&
                            r > g * 1.22 &&
                            r > b * 1.12;

                    if (isGreen) {
                        green++;

                        if (y < top + chartHeight / 3) {
                            upperGreen++;
                        } else if (y < top + 2 * chartHeight / 3) {
                            middleGreen++;
                        } else {
                            lowerGreen++;
                        }

                    } else if (isRed) {
                        red++;

                        if (y < top + chartHeight / 3) {
                            upperRed++;
                        } else if (y < top + 2 * chartHeight / 3) {
                            middleRed++;
                        } else {
                            lowerRed++;
                        }
                    }
                }
            }

            long total = green + red;

            SharedPreferences prefs = getSharedPreferences(
                    "diablo_scanner",
                    MODE_PRIVATE
            );

            int threshold = prefs.getInt("threshold", 15);

            String signal = "WAIT";
            int score = 0;

            /*
             * Existing colour-based heuristic.
             * Its score is not a measured win probability.
             */

            if (total >= 20) {

                double overallDifference =
                        Math.abs(green - red) / (double) total;

                double upperTotal = upperGreen + upperRed;
                double middleTotal = middleGreen + middleRed;
                double lowerTotal = lowerGreen + lowerRed;

                double upperBalance =
                        upperTotal == 0
                                ? 0
                                : (upperGreen - upperRed) / upperTotal;

                double middleBalance =
                        middleTotal == 0
                                ? 0
                                : (middleGreen - middleRed) / middleTotal;

                double lowerBalance =
                        lowerTotal == 0
                                ? 0
                                : (lowerGreen - lowerRed) / lowerTotal;

                double zoneDirection =
                        (upperBalance +
                         middleBalance +
                         lowerBalance) / 3.0;

                double combinedScore =
                        (0.65 * (green - red) / total)
                        + (0.35 * zoneDirection);

                if (overallDifference >= threshold / 100.0
                        && combinedScore > 0.12) {

                    signal = "UP BIAS";

                } else if (overallDifference >= threshold / 100.0
                        && combinedScore < -0.12) {

                    signal = "DOWN BIAS";

                } else {
                    signal = "WAIT";
                }

                score = (int) Math.round(
                        Math.min(
                                95,
                                50 + overallDifference * 45
                        )
                );

                if ("WAIT".equals(signal)) {
                    score = Math.min(score, 50);
                }
            }

            // Preserve the existing chart memory feature.
            String patternMemory =
                    ChartMemory.rememberAndCompare(
                            this,
                            bitmap,
                            signal,
                            score
                    );

            // Save the existing live scanner result.
            saveLiveResult(
                    prefs,
                    signal,
                    score,
                    green,
                    red
            );

            prefs.edit()
                    .putString(
                            "latest_pattern_memory",
                            patternMemory
                    )
                    .putInt(
                            "chart_pattern_count",
                            ChartMemory.getPatternCount(this)
                    )
                    .apply();

            /*
             * NEW AI CONNECTION:
             * Send a safe copy of the captured chart to the AI
             * on a background thread. The live capture continues.
             */
            startAIAnalysis(bitmap);

        } catch (Exception ignored) {
            SharedPreferences state = getSharedPreferences("diablo_scanner", MODE_PRIVATE);
            state.edit()
                    .putString("scan_status", "WAIT")
                    .putString("scan_message", "Chart analysis could not start. Tap D to retry.")
                    .apply();
        }
    }

    // ================================================
    // CONNECT LOCAL VISION AI TO LIVE SCREEN CAPTURE
    // ================================================

    private void startAIAnalysis(Bitmap capturedChart) {

        // Do not run AI until both model files have been selected.
        if (!LocalVisionAI.modelsReady(this)) {
            return;
        }

        // Never start multiple expensive AI jobs simultaneously.
        if (!aiRunning.compareAndSet(false, true)) {
            return;
        }

        final Bitmap aiBitmap;

        try {
            aiBitmap = capturedChart.copy(
                    Bitmap.Config.ARGB_8888,
                    false
            );

            if (aiBitmap == null) {
                aiRunning.set(false);
                return;
            }

        } catch (Exception error) {
            aiRunning.set(false);
            return;
        }

        try {
            aiExecutor.execute(() -> {

                try {
                    LocalVisionAI.Result result =
                            LocalVisionAI.analyze(
                                    getApplicationContext(),
                                    aiBitmap
                            );

                    if (result != null) {
                        saveAIResult(result);
                    }

                } catch (Exception ignored) {
                    // AI failure must not stop live screen capture.
                } finally {

                    if (!aiBitmap.isRecycled()) {
                        aiBitmap.recycle();
                    }

                    aiRunning.set(false);
                }
            });

        } catch (Exception error) {

            if (!aiBitmap.isRecycled()) {
                aiBitmap.recycle();
            }

            aiRunning.set(false);
        }
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

        String displaySignal;

        if ("UP".equals(aiSignal)) {
            displaySignal = "UP BIAS";
        } else if ("DOWN".equals(aiSignal)) {
            displaySignal = "DOWN BIAS";
        } else {
            displaySignal = "WAIT";
        }

        String details = result.details == null
                ? "No AI explanation returned."
                : result.details.trim();

        int score = extractConfidence(details);

        SharedPreferences prefs = getSharedPreferences(
                "diablo_scanner",
                MODE_PRIVATE
        );

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

            item.put("signal", displaySignal);
            item.put("score", score);
            item.put("green", 0);
            item.put("red", 0);
            item.put("source", "LIVE_AI");

            updated.put(item);

            for (int i = 0; i < old.length() && i < 49; i++) {
                updated.put(old.get(i));
            }

            prefs.edit()
                    .putString("history", updated.toString())
                    .putString("latest_ai_details", details)
                    .putString("latest_ai_signal", displaySignal)
                    .putInt("latest_ai_score", score)
                    .putString("latest_ai_time", item.getString("time"))
                    .putString("scan_status", "READY")
                    .putString("scan_message", displaySignal + " • " + details)
                    .apply();

        } catch (Exception ignored) {
            // A history error must not stop the scanner.
        }
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
        getSharedPreferences("diablo_scanner", MODE_PRIVATE)
                .edit()
                .putBoolean("screen_capture_active", false)
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
