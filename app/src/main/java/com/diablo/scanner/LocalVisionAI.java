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
    private static final int MAX_IMAGE_DIMENSION = 640;

    private static final long ANALYSIS_TIMEOUT_SECONDS = 90L;

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

        File imageFile = null;
        Process process = null;

        try {

            File[] models = prepareModels(context);

            File model = models[0];
            File projector = models[1];

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

            String prompt =
                    "You are an experimental visual trading-chart assistant. " +
                    "Analyze only the visible chart. Consider recent candle " +
                    "bodies and wicks, short-term momentum, visible support " +
                    "and resistance, and chart clarity. Do not invent prices " +
                    "or indicators. Return these fields on separate lines: " +
                    "DIRECTION: UP or DOWN or WAIT; " +
                    "CONFIDENCE: integer from 0 to 100; " +
                    "REASON: one short sentence. " +
                    "Choose WAIT if evidence is unclear or contradictory. " +
                    "Confidence is not a verified probability of winning. " +
                    "Never promise profit.";

            ProcessBuilder builder = new ProcessBuilder(
                    engine.getAbsolutePath(),
                    "-m", model.getAbsolutePath(),
                    "--mmproj", projector.getAbsolutePath(),
                    "--no-mmproj-offload",
                    "--image", imageFile.getAbsolutePath(),
                    "-p", prompt,
                    "-n", "120",
                    "-c", "2048",
                    "-t", "2",
                    "--temp", "0.1"
            );

            builder.redirectErrorStream(true);

            process = builder.start();

            final Process running = process;

            final ByteArrayOutputStream captured =
                    new ByteArrayOutputStream();

            Thread reader = new Thread(() -> {

                try (InputStream input =
                             running.getInputStream()) {

                    byte[] buffer = new byte[4096];
                    int count;

                    while ((count = input.read(buffer)) != -1) {

                        synchronized (captured) {

                            int remaining =
                                    MAX_OUTPUT_BYTES - captured.size();

                            if (remaining > 0) {

                                captured.write(
                                        buffer,
                                        0,
                                        Math.min(count, remaining)
                                );
                            }
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

    private static String parseDirection(String output) {

        if (output == null || output.trim().isEmpty()) {
            return "WAIT";
        }

        String upper = output.toUpperCase(Locale.ROOT);

        for (String line : upper.split("\\r?\\n")) {

            String cleaned = line.trim();

            if (!cleaned.startsWith("DIRECTION:")) {
                continue;
            }

            String value = cleaned.substring(
                    "DIRECTION:".length()
            ).trim();

            if (value.startsWith("UP")
                    && !value.startsWith("UPDATE")) {
                return "UP";
            }

            if (value.startsWith("DOWN")) {
                return "DOWN";
            }

            return "WAIT";
        }

        return "WAIT";
    }
}
