package com.diablo.scanner;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Online Kronos runtime adapter. Calls the configured Kronos API with explicit
 * structured OHLCV candle JSON. Never fabricates candle values from screenshots.
 * Call only from a background thread.
 */
public final class KronosRuntimeClient {
    private static final String PREFS = "diablo_scanner";
    private static final int CONNECT_TIMEOUT_MS = 12000;
    private static final int READ_TIMEOUT_MS = 90000;

    private KronosRuntimeClient() {}

    public static LocalVisionAI.Result forecast(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String baseUrl = prefs.getString("kronos_service_url", "").trim();
        String payload = prefs.getString("kronos_candles_json", "").trim();

        if (baseUrl.isEmpty()) {
            return new LocalVisionAI.Result("WAIT",
                    "KRONOS NEEDS AN ONLINE SERVICE URL. Set the HTTPS URL of your deployed Kronos API in Scalping Engine Settings. Mobile data alone does not host the model.");
        }
        if (!baseUrl.startsWith("https://")) {
            return new LocalVisionAI.Result("WAIT",
                    "KRONOS SERVICE URL MUST USE HTTPS. Save the secure base URL of your deployed Kronos API.");
        }
        if (payload.isEmpty()) {
            return new LocalVisionAI.Result("WAIT",
                    "KRONOS NEEDS REAL OHLCV INPUT. Paste a JSON request containing at least 32 completed candles in Scalping Engine Settings. Screenshot pixels are not reliable numeric candle data.");
        }

        HttpURLConnection connection = null;
        try {
            JSONObject request = new JSONObject(payload);
            JSONArray candles = request.optJSONArray("candles");
            if (candles == null || candles.length() < 32) {
                return new LocalVisionAI.Result("WAIT",
                        "KRONOS INPUT REJECTED: provide at least 32 completed candles with timestamps and valid open/high/low/close values.");
            }
            String endpoint = baseUrl.replaceAll("/+$", "") + "/v1/forecast";
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            byte[] bytes = request.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = readFully(stream);
            if (status < 200 || status >= 300) {
                String detail = "Kronos API returned HTTP " + status;
                try {
                    JSONObject error = new JSONObject(body);
                    detail += ": " + error.optString("detail", "service error");
                } catch (Exception ignored) {}
                return new LocalVisionAI.Result("WAIT",
                        "KRONOS FORECAST FAILED. " + detail + ". No substitute engine was used.");
            }
            JSONObject response = new JSONObject(body);
            if (!"KRONOS".equalsIgnoreCase(response.optString("engine"))
                    || !"FORECAST_ONLY".equalsIgnoreCase(response.optString("status"))) {
                return new LocalVisionAI.Result("WAIT",
                        "KRONOS RESPONSE REJECTED: the service did not identify a valid Kronos forecast.");
            }
            JSONArray predictions = response.optJSONArray("prediction_bars");
            if (predictions == null || predictions.length() == 0) {
                return new LocalVisionAI.Result("WAIT", "KRONOS returned no forecast bars.");
            }
            JSONObject first = predictions.getJSONObject(0);
            double open = first.getDouble("open");
            double high = first.getDouble("high");
            double low = first.getDouble("low");
            double close = first.getDouble("close");
            double lastClose = response.getDouble("last_observed_close");
            if (!finitePositive(open) || !finitePositive(high) || !finitePositive(low)
                    || !finitePositive(close) || !finitePositive(lastClose)
                    || high < Math.max(open, Math.max(close, low))
                    || low > Math.min(open, Math.min(close, high))) {
                return new LocalVisionAI.Result("WAIT", "KRONOS returned invalid price values. Signal rejected.");
            }
            double change = (close - lastClose) / lastClose;
            String signal = Math.abs(change) < 0.0001 ? "WAIT" : change > 0 ? "UP" : "DOWN";
            int strength = (int) Math.max(0, Math.min(79, 40 + Math.abs(change) * 10000));
            if ("WAIT".equals(signal)) strength = Math.min(strength, 49);
            StringBuilder details = new StringBuilder();
            details.append("KRONOS-MINI ONLINE FORECAST\n");
            details.append("Model: ").append(response.optString("model", "NeoQuasar/Kronos-mini")).append("\n");
            details.append("Tokenizer: ").append(response.optString("tokenizer", "NeoQuasar/Kronos-Tokenizer-2k")).append("\n");
            details.append("Symbol: ").append(response.optString("symbol", "unspecified")).append("\n");
            details.append("Timeframe: ").append(request.optInt("timeframe_seconds", 0)).append(" seconds\n");
            details.append("Observed close: ").append(lastClose).append("\n");
            details.append("First forecast OHLC: O ").append(open).append(" / H ").append(high)
                    .append(" / L ").append(low).append(" / C ").append(close).append("\n");
            details.append("Forecast bars returned: ").append(predictions.length()).append("\n");
            details.append("Direction is based on first forecast close vs last observed close.\n");
            details.append("CONFIDENCE: ").append(strength).append("% experimental direction-strength score; NOT a measured win probability.\n");
            details.append("No entry/TP/SL generated: the forecast alone is not a validated trade setup.\n");
            details.append("Warning: ").append(response.optString("warning",
                    "Experimental forecast only; not a trading instruction."));
            return new LocalVisionAI.Result(signal, details.toString());
        } catch (Exception error) {
            String message = error.getMessage();
            return new LocalVisionAI.Result("WAIT",
                    "KRONOS CONNECTION/INFERENCE ERROR: "
                            + error.getClass().getSimpleName()
                            + (message == null ? "" : " — " + message)
                            + ". Check data connection, service URL, model availability, and candle JSON. No fallback engine was used.");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static boolean finitePositive(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && value > 0;
    }

    private static String readFully(InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
