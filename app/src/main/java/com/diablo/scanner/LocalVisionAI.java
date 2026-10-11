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

    public static boolean modelsReady(Context context) {
        SharedPreferences prefs =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        String model = prefs.getString(MODEL_KEY, "");
        String projector = prefs.getString(PROJECTOR_KEY, "");

        return exists(model) && exists(projector);
    }

    private static boolean exists(String path) {
        return path != null && !path.isEmpty()
                && new File(path).isFile();
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

    private static File prepareEngine(Context context, String asset)
            throws Exception {

        File destination =
                new File(context.getFilesDir(), ENGINE);

        if (destination.isFile() && destination.length() > 100000) {
            if (!destination.canExecute()) {
                destination.setExecutable(true, true);
            }
            return destination;
        }

        if (destination.exists()) {
            destination.delete();
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

        if (!destination.setExecutable(true, true)) {
            throw new Exception("Cannot enable AI engine execution.");
        }

        return destination;
    }

    /*
     * Call this method on a background thread only.
     * It does not place or execute trades.
     */
    public static Result analyze(Context context, Bitmap chart) {

        if (context == null || chart == null || !modelsReady(context)) {
            return new Result(
                    "WAIT",
                    "Select both the SmolVLM model and its image projector."
            );
        }

        File imageFile = null;
        Process process = null;

        try {
            SharedPreferences prefs =
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

            File model = new File(
                    prefs.getString(MODEL_KEY, ""));

            File projector = new File(
                    prefs.getString(PROJECTOR_KEY, ""));

            String asset = findEngineAsset();

            if (asset == null) {
                return new Result(
                        "WAIT",
                        "This phone's processor architecture is unsupported."
                );
            }

            if (!model.isFile() || !projector.isFile()) {
                return new Result(
                        "WAIT",
                        "The selected model files cannot be found."
                );
            }

            File engine = prepareEngine(context, asset);

            imageFile = new File(
                    context.getCacheDir(),
                    "diablo-current-chart.jpg"
            );

            int width = chart.getWidth();
            int height = chart.getHeight();

            float scale = Math.min(
                    1.0f,
                    640.0f / Math.max(width, height)
            );

            int newWidth = Math.max(1, Math.round(width * scale));
            int newHeight = Math.max(1, Math.round(height * scale));

            Bitmap resized = Bitmap.createScaledBitmap(
                    chart, newWidth, newHeight, true);

            try (FileOutputStream output =
                         new FileOutputStream(imageFile)) {

                if (!resized.compress(
                        Bitmap.CompressFormat.JPEG, 82, output)) {
                    throw new Exception("Could not prepare chart image.");
                }

                output.flush();

            } finally {
                if (resized != chart && !resized.isRecycled()) {
                    resized.recycle();
                }
            }

            String prompt =
                    "Analyze this visible trading chart as an experimental " +
                    "visual assistant. Examine recent candles, wicks, bodies, " +
                    "momentum and visible support or resistance. Do not invent " +
                    "prices or indicators. Reply with these fields on separate " +
                    "lines: DIRECTION: UP or DOWN or WAIT; CONFIDENCE: a number " +
                    "from 0 to 100; REASON: one short sentence. Use WAIT if the " +
                    "chart is unclear or contradictory. Confidence is subjective, " +
                    "not a measured win probability. Never promise profit.";

            ProcessBuilder builder = new ProcessBuilder(
                    engine.getAbsolutePath(),
                    "-m", model.getAbsolutePath(),
                    "--mmproj", projector.getAbsolutePath(),
                    "--image", imageFile.getAbsolutePath(),
                    "-p", prompt,
                    "-n", "120",
                    "--temp", "0.1"
            );

            builder.redirectErrorStream(true);
            process = builder.start();

            final Process running = process;
            final ByteArrayOutputStream captured =
                    new ByteArrayOutputStream();

            Thread reader = new Thread(() -> {
                try (InputStream input = running.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int count;

                    while ((count = input.read(buffer)) != -1) {
                        synchronized (captured) {
                            int remaining = 100000 - captured.size();

                            if (remaining > 0) {
                                captured.write(
                                        buffer, 0, Math.min(count, remaining));
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            });

            reader.setDaemon(true);
            reader.start();

            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                process.destroyForcibly();

                return new Result(
                        "WAIT",
                        "AI analysis timed out. Try again when the chart settles."
                );
            }

            reader.join(1500);

            String output;

            synchronized (captured) {
                output = captured.toString("UTF-8").trim();
            }

            if (process.exitValue() != 0) {
                return new Result(
                        "WAIT",
                        "AI engine error. Check that the model and projector match."
                );
            }

            String signal = "WAIT";
            String upper = output.toUpperCase(Locale.ROOT);

            for (String line : upper.split("\\n")) {
                String cleaned = line.trim();

                if (cleaned.startsWith("DIRECTION:")) {
                    String direction =
                            cleaned.substring("DIRECTION:".length()).trim();

                    if (direction.startsWith("UP")
                            && !direction.startsWith("UPDATE")) {
                        signal = "UP";
                    } else if (direction.startsWith("DOWN")) {
                        signal = "DOWN";
                    }

                    break;
                }
            }

            if (output.length() > 1800) {
                output = output.substring(output.length() - 1800);
            }

            return new Result(signal, output);

        } catch (Exception error) {
            return new Result(
                    "WAIT",
                    "Local AI is not ready: "
                            + error.getClass().getSimpleName()
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
}
