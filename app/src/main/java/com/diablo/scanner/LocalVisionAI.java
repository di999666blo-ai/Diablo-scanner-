package com.diablo.scanner;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class LocalVisionAI {

    private static final String PREFS = "diablo_scanner_ai";
    private static final String MODEL_KEY = "text_model_path";
    private static final String PROJECTOR_KEY = "image_model_path";

    private static final String ENGINE = "llama-mtmd-cli";

    private static final String MODEL_ASSET =
            "models/diablo-text-model.gguf";

    private static final String PROJECTOR_ASSET =
            "models/diablo-image-projector.gguf";

    private static final String MODEL_FILE =
            "diablo-text-model.gguf";

    private static final String PROJECTOR_FILE =
            "diablo-image-projector.gguf";

    private static final int MAX_OUTPUT_BYTES = 100000;
    private static final int MAX_IMAGE_DIMENSION = 512;

    private static final long ANALYSIS_TIMEOUT_SECONDS = 60L;

    private static void publishProgress(Context context, String message) {
        if (context == null) return;
        context.getSharedPreferences("diablo_scanner", Context.MODE_PRIVATE)
                .edit()
                .putString("scan_status", "AI_PROCESSING")
                .putString("scan_message", message)
                .apply();
    }

    private LocalVisionAI() {
    }

    public static final class Result {

        public final String signal;
        public final String details;

        public Result(String signal, String details) {
            this.signal = signal;
            this.details = details;
        }
    }

    /*
     * Checks whether both packaged models are available.
     * This does not copy hundreds of megabytes on the UI thread.
     */
    public static boolean modelsReady(Context context) {

        if (context == null) {
            return false;
        }

        SharedPreferences prefs = context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
        );

        String modelPath = prefs.getString(MODEL_KEY, "");
        String projectorPath = prefs.getString(PROJECTOR_KEY, "");

        if (exists(modelPath) && exists(projectorPath)) {
            return true;
        }

        try (InputStream model =
                     context.getAssets().open(MODEL_ASSET);
             InputStream projector =
                     context.getAssets().open(PROJECTOR_ASSET)) {

            return true;

        } catch (Exception e) {
            return false;
        }
    }

    private static boolean exists(String path) {

        if (path == null || path.trim().isEmpty()) {
            return false;
        }

        File file = new File(path);

        return file.isFile() && file.canRead();
    }

    /*
     * Copies a packaged model into private app storage.
     * Runs during analysis, not during screen capture setup.
     */
    private static File copyAssetIfNeeded(
            Context context,
            String assetName,
            String destinationName
    ) throws Exception {

        File destination = new File(
                context.getFilesDir(),
                destinationName
        );

        if (destination.isFile() && destination.canRead()
                && destination.length() > 1000000L) {
            return destination;
        }

        if (destination.exists() && !destination.delete()) {
            throw new Exception(
                    "Cannot replace model file: " + destinationName
            );
        }

        try (InputStream input =
                     context.getAssets().open(assetName);
             FileOutputStream output =
                     new FileOutputStream(destination)) {

            byte[] buffer = new byte[65536];
            int count;

            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }

            output.flush();
            output.getFD().sync();
        }

        if (!destination.isFile()
                || !destination.canRead()
                || destination.length() < 1000000L) {

            throw new Exception(
                    "Packaged model is missing or incomplete: "
                            + destinationName
            );
        }

        return destination;
    }

    /*
     * Ensures the packaged models are copied and registered.
     */
    private static File[] prepareModels(Context context)
            throws Exception {

        SharedPreferences prefs = context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
        );

        String savedModel = prefs.getString(MODEL_KEY, "");
        String savedProjector = prefs.getString(PROJECTOR_KEY, "");

        File model;
        File projector;

        if (exists(savedModel) && exists(savedProjector)) {

            model = new File(savedModel);
            projector = new File(savedProjector);

        } else {

            model = copyAssetIfNeeded(
                    context,
                    MODEL_ASSET,
                    MODEL_FILE
            );

            projector = copyAssetIfNeeded(
                    context,
                    PROJECTOR_ASSET,
                    PROJECTOR_FILE
            );

            prefs.edit()
                    .putString(
                            MODEL_KEY,
                            model.getAbsolutePath()
                    )
                    .putString(
                            PROJECTOR_KEY,
                            projector.getAbsolutePath()
                    )
                    .apply();
        }

        return new File[]{model, projector};
    }

    private static String findEngineAsset() {

        for (String abi : Build.SUPPORTED_ABIS) {

            if ("arm64-v8a".equals(abi)
                    || "armeabi-v7a".equals(abi)) {

                return abi + "/" + ENGINE;
            }
        }

        return null;
    }

    private static File prepareEngine(
            Context context,
            String asset
    ) throws Exception {

        File installed = new File(
                context.getApplicationInfo().nativeLibraryDir,
                "libdiablo_llama.so"
        );

        if (installed.isFile() && installed.canRead()) {
            if (!installed.canExecute() && !installed.setExecutable(true, true)) {
                throw new Exception("Android did not allow the packaged AI engine to execute.");
            }
            return installed;
        }

        File destination = new File(
                context.getFilesDir(),
                ENGINE
        );

        if (destination.isFile()
                && destination.length() > 100000L) {

            if (!destination.canExecute()
                    && !destination.setExecutable(true, true)) {

                throw new Exception(
                        "Cannot enable AI engine execution."
                );
            }

            return destination;
        }

        if (destination.exists() && !destination.delete()) {
            throw new Exception(
                    "Cannot replace the existing AI engine."
            );
        }

        try (InputStream input =
                     context.getAssets().open(asset);
             FileOutputStream output =
                     new FileOutputStream(destination)) {

            byte[] buffer = new byte[65536];
            int count;

            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }

            output.flush();
        }

        if (!destination.isFile()
                || destination.length() < 100000L) {

            throw new Exception(
                    "The packaged AI engine is missing or incomplete."
            );
        }

        if (!destination.setExecutable(true, true)) {
            throw new Exception(
                    "Android could not enable AI engine execution."
            );
        }

        return destination;
    }

    /*
     * Run on a background thread only.
     * This method never places or executes trades.
     */
    public static Result analyze(
            Context context,
            Bitmap chart
    ) {

        if (context == null || chart == null) {
            return new Result(
                    "WAIT",
                    "No chart image was supplied."
            );
        }

        if (chart.isRecycled()
                || chart.getWidth() < 2
                || chart.getHeight() < 2) {

            return new Result(
                    "WAIT",
                    "The chart image is invalid or too small."
            );
        }

        // Reject empty/non-chart screens before starting the vision model. This prevents
        // a language model from inventing a direction when no usable chart is visible.
        if (!hasChartLikeCandleColours(chart)) {
            return new Result(
                    "WAIT",
                    "NO CHART DETECTED • I could not verify enough red/green candle pixels in this screen. Open a readable chart, then tap D again."
            );
        }

        File imageFile = null;
        Process process = null;

        try {

            publishProgress(context, "AI step 1/5: Checking the bundled model and vision projector...");
            File[] models = prepareModels(context);

            File model = models[0];
            File projector = models[1];
            publishProgress(context, "AI step 2/5: Model files are ready. Checking the Android inference engine...");

            String engineAsset = findEngineAsset();

            if (engineAsset == null) {
                return new Result(
                        "WAIT",
                        "This Android processor architecture is unsupported."
                );
            }

            File engine = prepareEngine(
                    context,
                    engineAsset
            );
            publishProgress(context, "AI step 3/5: Inference engine ready. Preparing and resizing the chart image...");

            imageFile = new File(
                    context.getCacheDir(),
                    "diablo-current-chart.jpg"
            );

            int width = chart.getWidth();
            int height = chart.getHeight();

            float scale = Math.min(
                    1.0f,
                    (float) MAX_IMAGE_DIMENSION
                            / Math.max(width, height)
            );

            int newWidth = Math.max(
                    1,
                    Math.round(width * scale)
            );

            int newHeight = Math.max(
                    1,
                    Math.round(height * scale)
            );

            Bitmap resized = Bitmap.createScaledBitmap(
                    chart,
                    newWidth,
                    newHeight,
                    true
            );

            try (FileOutputStream output =
                         new FileOutputStream(imageFile)) {

                if (!resized.compress(
                        Bitmap.CompressFormat.JPEG,
                        82,
                        output
                )) {
                    throw new Exception(
                            "Could not prepare the chart image."
                    );
                }

                output.flush();

            } finally {

                if (resized != chart && !resized.isRecycled()) {
                    resized.recycle();
                }
            }

            SharedPreferences scannerPrefs = context.getSharedPreferences(
                    "diablo_scanner",
                    Context.MODE_PRIVATE
            );

            String timeframe = scannerPrefs.getString("chart_timeframe", "1m");
            String mode = scannerPrefs.getString("trading_mode", "TIMED");
            long tradeDurationMs = scannerPrefs.getLong("trade_duration_ms", 60000L);
            String timedProfile = scannerPrefs.getString("timed_analysis_profile", "BALANCED");
            String candleTiming = scannerPrefs.getString("signal_timing", "CLOSE");
            String guidedContext = scannerPrefs.getString("guided_scalping_context", "{}");
            String priorGuidedFindings = "";
            if ("SCALPING".equalsIgnoreCase(mode)) {
                try {
                    org.json.JSONObject saved = new org.json.JSONObject(guidedContext);
                    StringBuilder previous = new StringBuilder();
                    java.util.Iterator<String> keys = saved.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        org.json.JSONObject stage = saved.optJSONObject(key);
                        if (stage != null && !key.equalsIgnoreCase(timeframe)) {
                            previous.append(key).append(" findings: ")
                                    .append(stage.optString("signal", "WAIT")).append("; ")
                                    .append(stage.optString("summary", "No summary saved")).append(". ");
                        }
                    }
                    if (previous.length() > 0) priorGuidedFindings = previous.toString();
                } catch (Exception ignored) { }
            }

            String modeInstructions;
            if ("SCALPING".equalsIgnoreCase(mode)) {
                modeInstructions =
                        "The selected mode is SCALPING SETUP. After DIRECTION, " +
                        "CONFIDENCE, and REASON, also return ACTION: BUY, SELL, or WAIT; " +
                        "ENTRY, TAKE_PROFIT, and STOP_LOSS. Use price values only if they are clearly " +
                        "readable on the screenshot; otherwise write N/A. Never invent prices. " +
                        "Do not treat a visual guess as a guaranteed entry.";
            } else {
                modeInstructions =
                        "The selected mode is TIMED SIGNAL. The configured trade duration is " +
                        formatDuration(tradeDurationMs) + " and the analysis profile is " +
                        timedProfile + ". Use the selected duration to frame the timing discussion, " +
                        "but do not claim the screenshot can predict the future or guarantee expiry outcome. " +
                        "Give only the directional chart read and a brief reason; do not invent entry, " +
                        "take-profit, or stop-loss prices.";
            }

            publishProgress(context, "AI step 4/5: Chart prepared. Running SmolVLM visual inference; this stage may take longer on this phone...");

            String prompt =
                    "You are an experimental visual trading-chart assistant. " +
                    "TIMED SIGNAL only: the selected visible-chart timeframe is " + timeframe
                    + " and the requested trade expiry horizon is " + formatDuration(tradeDurationMs)
                    + ". These are independent settings: do not assume the expiry equals the candle timeframe. "
                    + "Evaluate visible evidence against the selected expiry, but never claim future price certainty. "
                    + "The candle timing preference is " + candleTiming + ". " +
                    ("SCALPING".equalsIgnoreCase(mode)
                            ? "PERSISTENT GUIDED SCALPING CONTEXT from earlier completed stages: "
                                + (priorGuidedFindings.isEmpty() ? "No earlier timeframe findings saved yet. " : priorGuidedFindings)
                                + "Use these saved findings as context, but re-check the current screenshot and state when evidence conflicts. "
                            : "") +
                    "Use these settings as context only; a static screenshot cannot reveal future price movement. " +
                    "First verify that a real trading chart with visible candles is present. If no usable chart is visible, " +
                    "return TREND: unclear; DIRECTION: WAIT; CONFIDENCE: 0; REASON: no chart detected. " +
                    "Never infer a direction from the app interface, status bar, floating bubble, or prompt examples. " +
                    "Analyze only what is visible in the screenshot. Consider recent " +
                    "candle bodies and wicks, short-term momentum, visible support " +
                    "and resistance, volatility, reversals, and chart clarity. " +
                    "Do not invent prices or indicators. Return these fields on separate lines: " +
                    "TREND: bullish, bearish, sideways, or unclear; " +
                    "SUPPORT: visible level or not clear; " +
                    "RESISTANCE: visible level or not clear; " +
                    "BREAKOUT: confirmed, not confirmed, or not visible; " +
                    "MOMENTUM: bullish, bearish, mixed, or unclear; " +
                    "DIRECTION: UP or DOWN or WAIT; " +
                    "CONFIDENCE: integer from 0 to 100; " +
                    "REASON: one short sentence. " +
                    modeInstructions + " " +
                    "Choose WAIT if evidence is unclear or contradictory. " +
                    "Confidence is not a verified probability of winning. " +
                    "Never promise profit and never execute a trade.";

            ProcessBuilder builder = new ProcessBuilder(
                    engine.getAbsolutePath(),
                    "-m", model.getAbsolutePath(),
                    "--mmproj", projector.getAbsolutePath(),
                    "--no-mmproj-offload",
                    "--image", imageFile.getAbsolutePath(),
                    "-p", prompt,
                    "-n", "96",
                    "-c", "2048",
                    "-t", "2",
                    "--temp", "0.1"
            );

            builder.redirectErrorStream(true);

            process = builder.start();
            publishProgress(context, "AI step 5/5: Model process started. Waiting for the chart interpretation and checking the engine output...");

            final Process running = process;

            final ByteArrayOutputStream captured =
                    new ByteArrayOutputStream();

            Thread reader = new Thread(() -> {

                try (InputStream input =
                             running.getInputStream()) {

                    byte[] buffer = new byte[4096];
                    int count;
                    String lastStage = "";

                    while ((count = input.read(buffer)) != -1) {

                        String outputSoFar;
                        synchronized (captured) {
                            int remaining = MAX_OUTPUT_BYTES - captured.size();
                            if (remaining > 0) {
                                captured.write(buffer, 0, Math.min(count, remaining));
                            }
                            outputSoFar = new String(
                                    captured.toByteArray(),
                                    java.nio.charset.StandardCharsets.UTF_8
                            ).toLowerCase(Locale.ROOT);
                        }

                        // Surface meaningful model progress while tokens arrive.
                        // These are progress observations, not proof of a valid trade.
                        String stage = null;
                        if (outputSoFar.contains("direction:") || outputSoFar.contains("reason:")) {
                            stage = "AI has drafted its direction and reason; checking the final result...";
                        } else if (outputSoFar.contains("breakout: not confirmed")) {
                            stage = "AI reports no confirmed breakout yet; weighing whether to WAIT...";
                        } else if (outputSoFar.contains("breakout:")) {
                            stage = "AI is checking whether a breakout is confirmed...";
                        } else if (outputSoFar.contains("resistance:")) {
                            stage = "AI has checked the visible resistance area...";
                        } else if (outputSoFar.contains("support:")) {
                            stage = "AI has checked the visible support area...";
                        } else if (outputSoFar.contains("trend:")) {
                            stage = "AI is reading candle trend and momentum...";
                        }

                        if (stage != null && !stage.equals(lastStage)) {
                            publishProgress(context, stage);
                            lastStage = stage;
                        }
                    }

                } catch (Exception ignored) {
                    // Preserve output already received.
                }

            }, "DiabloAIOutputReader");

            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(
                    ANALYSIS_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );

            if (!finished) {

                publishProgress(context, "AI timed out after " + ANALYSIS_TIMEOUT_SECONDS + " seconds. The engine did not finish; check diagnostics and device memory.");
                process.destroyForcibly();

                return new Result(
                        "WAIT",
                        "AI analysis timed out. The phone may not have enough memory or processing power."
                );
            }

            reader.join(2000L);

            String output;

            synchronized (captured) {
                output = captured.toString("UTF-8").trim();
            }

            if (process.exitValue() != 0) {

                String diagnostic = output;

                if (diagnostic.length() > 700) {
                    diagnostic = diagnostic.substring(
                            diagnostic.length() - 700
                    );
                }

                return new Result(
                        "WAIT",
                        "AI engine failed with exit code "
                                + process.exitValue()
                                + ". Check model and engine compatibility."
                                + (diagnostic.isEmpty()
                                ? ""
                                : "\nEngine output:\n" + diagnostic)
                );
            }

            if (output.isEmpty()) {
                return new Result(
                        "WAIT",
                        "The AI engine returned no readable answer."
                );
            }

            String signal = parseDirection(output);

            if (output.length() > 1800) {
                output = output.substring(
                        output.length() - 1800
                );
            }

            if ("WAIT".equals(signal)) {
                output =
                        "No clear UP or DOWN direction was found. WAIT is used for safety.\n\n"
                                + output;
            }

            if ("SCALPING".equalsIgnoreCase(mode)) {
                saveGuidedStage(scannerPrefs, timeframe, signal, output);
            }
            return new Result(signal, output);

        } catch (Exception error) {

            return new Result(
                    "WAIT",
                    "Local AI error: "
                            + error.getClass().getSimpleName()
                            + ". Check packaged models, engine compatibility, and available storage."
            );

        } finally {

            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }

            if (imageFile != null && imageFile.exists()) {
                imageFile.delete();
            }
        }
    }

    private static void saveGuidedStage(SharedPreferences prefs, String timeframe, String signal, String output) {
        try {
            org.json.JSONObject saved = new org.json.JSONObject(prefs.getString("guided_scalping_context", "{}"));
            org.json.JSONObject stage = new org.json.JSONObject();
            stage.put("timeframe", timeframe);
            stage.put("signal", signal);
            stage.put("summary", output.length() > 900 ? output.substring(0, 900) : output);
            stage.put("updatedAt", System.currentTimeMillis());
            stage.put("engine", prefs.getString("scalping_engine", "SMOLVLM"));
            saved.put(timeframe, stage);
            prefs.edit().putString("guided_scalping_context", saved.toString())
                    .putString("guided_scalping_last_stage", timeframe)
                    .putLong("guided_scalping_last_updated", System.currentTimeMillis()).apply();
        } catch (Exception ignored) { }
    }

    /**
     * Conservative screen guard: a trading chart must contain a meaningful number
     * of saturated red/green pixels distributed across several horizontal columns.
     * This is deliberately only a chart-presence check, not a signal generator.
     */
    private static boolean hasChartLikeCandleColours(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()
                || bitmap.getWidth() < 80 || bitmap.getHeight() < 60) {
            return false;
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int xStride = Math.max(1, width / 160);
        int yStride = Math.max(1, height / 120);
        int coloredPixels = 0;
        int columnsWithCandleColour = 0;
        for (int x = width / 30; x < width - width / 30; x += xStride) {
            int columnCount = 0;
            for (int y = height / 10; y < height - height / 10; y += yStride) {
                int color = bitmap.getPixel(x, y);
                int red = android.graphics.Color.red(color);
                int green = android.graphics.Color.green(color);
                int blue = android.graphics.Color.blue(color);
                boolean greenCandle = green > 90 && green > red * 1.20 && green > blue * 1.08;
                boolean redCandle = red > 95 && red > green * 1.20 && red > blue * 1.12;
                if (greenCandle || redCandle) {
                    coloredPixels++;
                    columnCount++;
                }
            }
            if (columnCount >= 2) columnsWithCandleColour++;
        }
        return coloredPixels >= 24 && columnsWithCandleColour >= 4;
    }

    private static String formatDuration(long durationMs) {
        long seconds = Math.max(1L, durationMs / 1000L);
        if (seconds < 60L) {
            return seconds + (seconds == 1L ? " second" : " seconds");
        }
        long minutes = seconds / 60L;
        long remainingSeconds = seconds % 60L;
        if (remainingSeconds == 0L) {
            return minutes + (minutes == 1L ? " minute" : " minutes");
        }
        return minutes + "m " + remainingSeconds + "s";
    }

    private static String parseDirection(String output) {

        if (output == null || output.trim().isEmpty()) {
            return "WAIT";
        }

        String found = "WAIT";
        String upper = output.toUpperCase(Locale.ROOT);

        // Read all matching lines and keep the last valid direction. This avoids
        // treating prompt examples such as "UP or DOWN or WAIT" as a signal.
        for (String line : upper.split("\\r?\\n")) {
            String cleaned = line.trim();
            if (!cleaned.startsWith("DIRECTION:")) continue;

            String value = cleaned.substring("DIRECTION:".length())
                    .trim()
                    .replaceAll("[;,.]+$", "")
                    .trim();

            if ("UP".equals(value) || "UP BIAS".equals(value)) {
                found = "UP";
            } else if ("DOWN".equals(value) || "DOWN BIAS".equals(value)) {
                found = "DOWN";
            } else if ("WAIT".equals(value)) {
                found = "WAIT";
            }
        }

        return found;
    }}
