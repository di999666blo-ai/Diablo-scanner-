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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Online Kronos runtime adapter. Calls the configured Kronos API with explicit
 * structured OHLCV candle JSON. Never fabricates candle values from screenshots.
 * Call only from a background thread.
 */
public final class KronosRuntimeClient {
    private static final String PREFS = "diablo_scanner";
    private static final int CONNECT_TIMEOUT_MS = 12000;
    private static final int READ_TIMEOUT_MS = 180000;

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
        HttpURLConnection connection = null;
        boolean marketAutoFetch = payload.isEmpty();
        try {
            JSONObject request;
            String endpoint;
            if (marketAutoFetch) {
                // Fetch structured public candles directly on the phone, then send those
                // exact completed bars to the configured genuine Kronos inference service.
                // This does not claim the PyTorch Kronos model is running locally.
                String symbol = prefs.getString("kronos_symbol", "EURUSD");
                String interval = prefs.getString("kronos_interval", "1m");
                int predictionBars = prefs.getInt("kronos_prediction_bars", 3);
                request = fetchBiQuoteForecastRequest(symbol, interval, predictionBars);
                endpoint = baseUrl.replaceAll("/+$", "") + "/v1/forecast";
            } else {
                request = new JSONObject(payload);
                JSONArray candles = request.optJSONArray("candles");
                if (candles == null || candles.length() < 32) {
                    return new LocalVisionAI.Result("WAIT",
                            "KRONOS INPUT REJECTED: provide at least 32 completed candles with timestamps and valid open/high/low/close values.");
                }
                endpoint = baseUrl.replaceAll("/+$", "") + "/v1/forecast";
            }
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
            int timeframeSeconds = request.optInt("timeframe_seconds", 0);
            if (timeframeSeconds <= 0) {
                String interval = request.optString("interval", "unknown");
                timeframeSeconds = "1m".equals(interval) ? 60 : "5m".equals(interval) ? 300
                        : "15m".equals(interval) ? 900 : "30m".equals(interval) ? 1800
                        : "1h".equals(interval) ? 3600 : "4h".equals(interval) ? 14400
                        : "1d".equals(interval) ? 86400 : 0;
            }
            details.append("Timeframe: ").append(timeframeSeconds).append(" seconds\n");
            details.append("Market data: ").append(response.optString("data_provider",
                    marketAutoFetch ? "biquote.io (candles fetched directly on phone)" : "manual OHLCV input")).append("\n");
            if (response.has("completed_candles_used")) {
                details.append("Completed candles used: ").append(response.optInt("completed_candles_used")).append("\n");
            } else if (marketAutoFetch) {
                details.append("Completed candles used: ").append(request.optJSONArray("candles").length()).append("\n");
            }
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
                            + ". Check data connection, service URL, provider feed, model availability, and market settings. No fallback engine was used.");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Fetch completed public-market candles on this phone for the isolated local Kronos runtime. */
    public static JSONObject fetchLocalCandleInput(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return fetchLocalCandleInput(context, prefs.getString("kronos_interval", "1m"));
    }

    /** Fetch completed candles for an explicitly requested timeframe, independent of the saved chart view. */
    public static JSONObject fetchLocalCandleInput(Context context, String interval) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String symbol = prefs.getString("kronos_symbol", "EURUSD");
        int predictionBars = prefs.getInt("kronos_prediction_bars", 3);
        return fetchBiQuoteForecastRequest(symbol, interval, predictionBars);
    }

    private static JSONObject fetchBiQuoteForecastRequest(String symbol, String interval, int predictionBars) throws Exception {
        String cleanSymbol = symbol == null ? "" : symbol.trim().toUpperCase(java.util.Locale.ROOT);
        if (!cleanSymbol.matches("[A-Z0-9._-]{1,40}")) {
            throw new IllegalArgumentException("Invalid BiQuote instrument symbol.");
        }
        int timeframeSeconds = intervalSeconds(interval);
        String encodedSymbol = URLEncoder.encode(cleanSymbol, "UTF-8");
        URL url = new URL("https://biquote.io/api/" + encodedSymbol
                + "/ohlc?interval=" + URLEncoder.encode(interval, "UTF-8") + "&limit=200");
        HttpURLConnection feed = null;
        try {
            feed = (HttpURLConnection) url.openConnection();
            feed.setRequestMethod("GET");
            feed.setConnectTimeout(CONNECT_TIMEOUT_MS);
            feed.setReadTimeout(20000);
            feed.setRequestProperty("Accept", "application/json");
            feed.setRequestProperty("User-Agent", "DiabloScanner/1.0");
            int status = feed.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? feed.getInputStream() : feed.getErrorStream();
            String body = readFully(stream);
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("BiQuote candle feed returned HTTP " + status);
            }
            JSONObject response = new JSONObject(body);
            JSONArray bars = response.optJSONArray("bars");
            if (bars == null) throw new IllegalStateException("BiQuote returned no OHLC bars.");
            List<MarketCandle> completed = new ArrayList<>();
            for (int i = 0; i < bars.length(); i++) {
                JSONObject bar = bars.optJSONObject(i);
                if (bar == null || bar.optBoolean("isOpen", false)) continue;
                long timestamp = parseMarketTimestamp(bar.opt("openTime"));
                double open = bar.optDouble("open", Double.NaN);
                double high = bar.optDouble("high", Double.NaN);
                double low = bar.optDouble("low", Double.NaN);
                double close = bar.optDouble("close", Double.NaN);
                double volume = bar.optDouble("tickVolume", bar.optDouble("volume", 0));
                if (timestamp <= 0 || !finitePositive(open) || !finitePositive(high)
                        || !finitePositive(low) || !finitePositive(close)
                        || high < Math.max(open, Math.max(close, low))
                        || low > Math.min(open, Math.min(close, high))) continue;
                JSONObject candle = new JSONObject();
                candle.put("timestamp", Instant.ofEpochMilli(timestamp).toString());
                candle.put("open", open);
                candle.put("high", high);
                candle.put("low", low);
                candle.put("close", close);
                candle.put("volume", Math.max(0, volume));
                candle.put("amount", 0);
                completed.add(new MarketCandle(timestamp, candle));
            }
            Collections.sort(completed, Comparator.comparingLong(item -> item.timestamp));
            if (completed.size() < 32) {
                throw new IllegalStateException("BiQuote supplied only " + completed.size()
                        + " valid completed candles; Kronos requires at least 32. Signal held at WAIT.");
            }
            // Reject duplicate or irregular timestamps rather than silently mislabel the timeframe.
            long intervalMs = timeframeSeconds * 1000L;
            List<MarketCandle> selected = completed.subList(Math.max(0, completed.size() - 200), completed.size());
            JSONArray candles = new JSONArray();
            long previous = -1;
            for (MarketCandle item : selected) {
                if (previous >= 0 && (item.timestamp <= previous
                        || (item.timestamp - previous) % intervalMs != 0
                        || item.timestamp - previous < intervalMs)) {
                    throw new IllegalStateException("BiQuote candle timestamps do not align with " + interval
                            + ". Signal held at WAIT.");
                }
                candles.put(item.candle);
                previous = item.timestamp;
            }
            JSONObject request = new JSONObject();
            request.put("symbol", cleanSymbol);
            request.put("timeframe_seconds", timeframeSeconds);
            request.put("prediction_bars", Math.max(1, Math.min(12, predictionBars)));
            request.put("candles", candles);
            return request;
        } finally {
            if (feed != null) feed.disconnect();
        }
    }

    private static int intervalSeconds(String interval) {
        if ("1m".equals(interval)) return 60;
        if ("5m".equals(interval)) return 300;
        if ("15m".equals(interval)) return 900;
        if ("30m".equals(interval)) return 1800;
        if ("1h".equals(interval)) return 3600;
        if ("4h".equals(interval)) return 14400;
        if ("1d".equals(interval)) return 86400;
        throw new IllegalArgumentException("Unsupported BiQuote interval: " + interval);
    }

    private static long parseMarketTimestamp(Object raw) throws Exception {
        if (raw instanceof Number) {
            long value = ((Number) raw).longValue();
            return value < 100000000000L ? value * 1000L : value;
        }
        if (raw == null) throw new IllegalArgumentException("Missing candle timestamp.");
        String value = String.valueOf(raw).trim();
        if (value.matches("\\d{10,13}")) {
            long numeric = Long.parseLong(value);
            return numeric < 100000000000L ? numeric * 1000L : numeric;
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return OffsetDateTime.parse(value).toInstant().toEpochMilli();
        }
    }

    private static final class MarketCandle {
        final long timestamp;
        final JSONObject candle;
        MarketCandle(long timestamp, JSONObject candle) {
            this.timestamp = timestamp;
            this.candle = candle;
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
