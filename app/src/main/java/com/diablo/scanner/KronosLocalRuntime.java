package com.diablo.scanner;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import com.diablo.scanner.kronos.Candle;
import com.diablo.scanner.kronos.ForecastParams;
import com.diablo.scanner.kronos.KronosEngine;
import com.diablo.scanner.kronos.KronosManifest;
import com.diablo.scanner.kronos.KronosModelStore;

import java.io.File;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Genuine on-device Kronos inference via the validated incremental ONNX graph
 * contract. No hosted inference service and no fallback to SmolVLM/Hybrid.
 */
public final class KronosLocalRuntime {
    private static final String ASSET_DIR = "kronos";
    private static volatile KronosEngine engine;
    private static volatile String loadError = "";

    public enum State { READY, MODEL_MISSING, TOKENIZER_MISSING, BACKEND_UNAVAILABLE, INVALID_METADATA, INFERENCE_UNAVAILABLE }

    public static final class Status {
        public final State state;
        public final String message;
        private Status(State state, String message) { this.state = state; this.message = message; }
        public boolean isReady() { return state == State.READY; }
    }

    private KronosLocalRuntime() {}

    public static Status inspect(Context context) {
        if (context == null) return new Status(State.INFERENCE_UNAVAILABLE, "WAIT — Android context is unavailable.");
        if (!assetExists(context, ASSET_DIR + "/kronos_manifest.json")) {
            return new Status(State.INVALID_METADATA, "WAIT — local Kronos manifest is not bundled.");
        }
        if (!assetExists(context, ASSET_DIR + "/tokenizer_encode.onnx")
                || !assetExists(context, ASSET_DIR + "/tokenizer_decode.onnx")) {
            return new Status(State.TOKENIZER_MISSING, "WAIT — matching Kronos tokenizer graphs are missing.");
        }
        if (!assetExists(context, ASSET_DIR + "/kronos_decode.onnx")
                || !assetExists(context, ASSET_DIR + "/kronos_step_s2.onnx")) {
            return new Status(State.MODEL_MISSING, "WAIT — Kronos autoregressive model graphs are missing.");
        }
        try {
            Class.forName("ai.onnxruntime.OrtEnvironment");
        } catch (Throwable ignored) {
            return new Status(State.BACKEND_UNAVAILABLE, "WAIT — ONNX Runtime Android is unavailable.");
        }
        return new Status(State.READY, "GREEN — local Kronos ONNX graphs, tokenizer, manifest, and ONNX Runtime are bundled.");
    }

    /** Runs on the app's background scan thread. Input is structured completed OHLCV candles. */
    public static synchronized LocalVisionAI.Result forecast(Context context, String candleJson) {
        if (context == null || candleJson == null || candleJson.trim().isEmpty()) {
            return new LocalVisionAI.Result("WAIT", "KRONOS LOCAL: valid completed-candle input is required.");
        }
        Status status = inspect(context);
        if (!status.isReady()) return new LocalVisionAI.Result("WAIT", status.message);
        try {
            JSONObject request = new JSONObject(candleJson);
            JSONArray bars = request.optJSONArray("candles");
            if (bars == null || bars.length() < 32) {
                return new LocalVisionAI.Result("WAIT", "KRONOS LOCAL: at least 32 completed OHLCV candles are required.");
            }
            int intervalSeconds = request.optInt("timeframe_seconds", 0);
            int predLen = Math.max(1, Math.min(12, request.optInt("prediction_bars", 3)));
            if (intervalSeconds <= 0 || intervalSeconds > 86400) {
                return new LocalVisionAI.Result("WAIT", "KRONOS LOCAL: selected timeframe is invalid.");
            }
            List<Candle> candles = new ArrayList<>();
            long previous = -1L;
            for (int i = 0; i < bars.length(); i++) {
                JSONObject bar = bars.optJSONObject(i);
                if (bar == null) continue;
                long timestamp = parseTimestamp(bar.get("timestamp"));
                float open = (float) bar.getDouble("open");
                float high = (float) bar.getDouble("high");
                float low = (float) bar.getDouble("low");
                float close = (float) bar.getDouble("close");
                float volume = (float) Math.max(0.0, bar.optDouble("volume", 0.0));
                float amount = (float) Math.max(0.0, bar.optDouble("amount", 0.0));
                if (timestamp <= previous || !positive(open) || !positive(high) || !positive(low) || !positive(close)
                        || high < Math.max(open, Math.max(close, low)) || low > Math.min(open, Math.min(close, high))) {
                    return new LocalVisionAI.Result("WAIT", "KRONOS LOCAL: invalid or out-of-order OHLC candle at index " + i + ".");
                }
                candles.add(new Candle(timestamp, open, high, low, close, volume, amount));
                previous = timestamp;
            }
            if (candles.size() < 32) return new LocalVisionAI.Result("WAIT", "KRONOS LOCAL: fewer than 32 valid candles remain.");

            KronosEngine active = getEngine(context);
            long[] futureTimestamps = new long[predLen];
            long lastTs = candles.get(candles.size() - 1).getTimestamp();
            long stepMs = intervalSeconds * 1000L;
            for (int i = 0; i < predLen; i++) futureTimestamps[i] = lastTs + stepMs * (i + 1L);
            ForecastParams params = new ForecastParams(Math.min(256, candles.size()), predLen, 1, 1.0f, 1, 0.9f, 123L);
            float[][] paths = active.predict(candles, futureTimestamps, params, ZoneId.of("UTC"), null);
            if (paths == null || paths.length == 0 || paths[0].length < 6) {
                return new LocalVisionAI.Result("WAIT", "KRONOS LOCAL: model returned no complete forecast path.");
            }
            float open = paths[0][0], high = paths[0][1], low = paths[0][2], close = paths[0][3];
            float lastClose = candles.get(candles.size() - 1).getClose();
            if (!positive(open) || !positive(high) || !positive(low) || !positive(close)
                    || high < Math.max(open, Math.max(close, low)) || low > Math.min(open, Math.min(close, high))) {
                return new LocalVisionAI.Result("WAIT", "KRONOS LOCAL: model output failed OHLC validation; forecast rejected.");
            }
            float change = (close - lastClose) / lastClose;
            String signal = Math.abs(change) < 0.0001f ? "WAIT" : change > 0 ? "UP" : "DOWN";
            int strength = Math.max(0, Math.min(79, 40 + Math.round(Math.abs(change) * 10000f)));
            if ("WAIT".equals(signal)) strength = Math.min(strength, 49);
            StringBuilder details = new StringBuilder();
            details.append("KRONOS LOCAL ON-DEVICE FORECAST\n");
            details.append("Model: ").append(active.getManifest().getModelId()).append("\n");
            details.append("Tokenizer: ").append(active.getManifest().getTokenizerId()).append("\n");
            details.append("Symbol: ").append(request.optString("symbol", "unspecified")).append("\n");
            details.append("Timeframe: ").append(intervalSeconds).append(" seconds\n");
            details.append("Completed candles used: ").append(candles.size()).append("\n");
            details.append("Inference: ONNX Runtime on this phone; no host used\n");
            details.append("Observed close: ").append(lastClose).append("\n");
            details.append("First forecast OHLC: O ").append(open).append(" / H ").append(high)
                    .append(" / L ").append(low).append(" / C ").append(close).append("\n");
            details.append("Forecast bars returned: ").append(paths[0].length / 6).append("\n");
            details.append("Direction strength: ").append(strength).append("% experimental score, not a measured win probability.\n");
            details.append("Forecast only; no automatic trade execution and no validated entry/TP/SL. Market forecasts are uncertain.");
            return new LocalVisionAI.Result(signal, details.toString());
        } catch (Throwable error) {
            String message = error.getMessage();
            return new LocalVisionAI.Result("WAIT", "KRONOS LOCAL INFERENCE ERROR: "
                    + error.getClass().getSimpleName() + (message == null ? "" : " — " + message)
                    + ". No remote or substitute engine was used.");
        }
    }

    private static KronosEngine getEngine(Context context) throws Exception {
        if (engine != null) return engine;
        synchronized (KronosLocalRuntime.class) {
            if (engine != null) return engine;
            File modelDir = KronosModelStore.INSTANCE.ensureInstalled(context.getApplicationContext(), null);
            KronosEngine loaded = KronosEngine.Companion.create(modelDir);
            engine = loaded;
            return loaded;
        }
    }

    private static long parseTimestamp(Object raw) {
        if (raw instanceof Number) {
            long value = ((Number) raw).longValue();
            return value < 100000000000L ? value * 1000L : value;
        }
        String value = String.valueOf(raw).trim();
        if (value.matches("\\d{10,13}")) {
            long numeric = Long.parseLong(value);
            return numeric < 100000000000L ? numeric * 1000L : numeric;
        }
        return Instant.parse(value).toEpochMilli();
    }

    private static boolean positive(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value) && value > 0f;
    }

    private static boolean assetExists(Context context, String path) {
        try (InputStream in = context.getAssets().open(path)) { return in.read() >= 0; }
        catch (Exception ignored) { return false; }
    }
}
