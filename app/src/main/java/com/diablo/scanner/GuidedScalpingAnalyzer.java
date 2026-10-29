package com.diablo.scanner;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Guided Scalping orchestration: 4H market bias, 15M setup, 1M Kronos entry
 * forecast. Uses completed BigQuote OHLC candles; never derives prices from pixels.
 */
public final class GuidedScalpingAnalyzer {
    private GuidedScalpingAnalyzer() {}

    public static LocalVisionAI.Result analyze(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences("diablo_scanner", Context.MODE_PRIVATE);
            String symbol = prefs.getString("kronos_symbol", "EURUSD");
            JSONObject h4 = KronosRuntimeClient.fetchLocalCandleInput(context, "4h");
            JSONObject m15 = KronosRuntimeClient.fetchLocalCandleInput(context, "15m");
            JSONObject m1 = KronosRuntimeClient.fetchLocalCandleInput(context, "1m");

            String higher = bias(h4.optJSONArray("candles"), 6);
            String setup = bias(m15.optJSONArray("candles"), 5);
            if ("WAIT".equals(higher) || "WAIT".equals(setup)) {
                return new LocalVisionAI.Result("WAIT",
                        "GUIDED SCALPING • KRONOS\nSymbol: " + symbol
                        + "\n4H bias: " + higher + "\n15M setup: " + setup
                        + "\n1M trigger: not evaluated\nWAIT: candle data is insufficient or the higher-timeframe structure is unclear.");
            }

            // Run the actual Kronos model only on the entry timeframe. The higher
            // timeframes are real completed OHLC context, not three redundant inferences.
            LocalVisionAI.Result entryForecast = KronosLocalRuntime.forecast(context, m1.toString());
            String trigger = entryForecast == null ? "WAIT" : entryForecast.signal;
            if (!"UP".equals(trigger) && !"DOWN".equals(trigger)) {
                return new LocalVisionAI.Result("WAIT",
                        "GUIDED SCALPING • KRONOS\nSymbol: " + symbol
                        + "\n4H bias: " + higher + "\n15M setup: " + setup
                        + "\n1M trigger: WAIT\n" + (entryForecast == null ? "Kronos returned no result." : entryForecast.details));
            }
            if (!higher.equals(setup) || !higher.equals(trigger)) {
                return new LocalVisionAI.Result("WAIT",
                        "GUIDED SCALPING • KRONOS\nSymbol: " + symbol
                        + "\n4H bias: " + higher + "\n15M setup: " + setup
                        + "\n1M trigger: " + trigger
                        + "\nWAIT: timeframes conflict; no entry was issued.\n"
                        + (entryForecast == null ? "" : entryForecast.details));
            }

            JSONArray bars = m1.optJSONArray("candles");
            if (bars == null || bars.length() < 12) {
                return new LocalVisionAI.Result("WAIT", "GUIDED SCALPING • WAIT: not enough 1M candles to calculate structural risk levels.");
            }
            JSONObject last = bars.getJSONObject(bars.length() - 1);
            double entry = last.getDouble("close");
            double swing = trigger.equals("UP") ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
            int start = Math.max(0, bars.length() - 10);
            for (int i = start; i < bars.length(); i++) {
                JSONObject bar = bars.getJSONObject(i);
                swing = trigger.equals("UP")
                        ? Math.min(swing, bar.getDouble("low"))
                        : Math.max(swing, bar.getDouble("high"));
            }
            double risk = trigger.equals("UP") ? entry - swing : swing - entry;
            if (!finite(entry) || !finite(swing) || risk <= 0 || risk / entry > 0.03) {
                return new LocalVisionAI.Result("WAIT",
                        "GUIDED SCALPING • KRONOS\n4H bias: " + higher + "\n15M setup: " + setup
                        + "\n1M trigger: " + trigger + "\nWAIT: structural stop distance is invalid or unusually large.");
            }
            double sl = swing;
            double tp = trigger.equals("UP") ? entry + risk * 2.0 : entry - risk * 2.0;
            String details = "GUIDED SCALPING • KRONOS MULTI-TIMEFRAME\n"
                    + "Symbol: " + symbol + "\n"
                    + "4H bias (completed candles): " + higher + "\n"
                    + "15M setup (completed candles): " + setup + "\n"
                    + "1M Kronos trigger: " + trigger + "\n"
                    + "ENTRY: " + entry + "\nSTOP LOSS: " + sl + "\nTAKE PROFIT: " + tp + "\n"
                    + "Risk/reward: 1:2 (target derived from structural risk)\n"
                    + "Source: BigQuote completed OHLC; Kronos-mini local ONNX forecast on 1M.\n"
                    + "Forecasts are uncertain; verify spread, execution price and setup manually. No trade is executed automatically.\n\n"
                    + (entryForecast == null ? "" : entryForecast.details);
            return new LocalVisionAI.Result(trigger, details);
        } catch (Exception error) {
            return new LocalVisionAI.Result("WAIT",
                    "GUIDED SCALPING • KRONOS DATA/ANALYSIS ERROR: "
                    + error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : " — " + error.getMessage())
                    + ". Check symbol availability and mobile data. No invented signal or price was used.");
        }
    }

    private static String bias(JSONArray bars, int window) throws Exception {
        if (bars == null || bars.length() < Math.max(32, window + 2)) return "WAIT";
        int n = bars.length();
        double first = bars.getJSONObject(n - window - 1).getDouble("close");
        double last = bars.getJSONObject(n - 1).getDouble("close");
        if (!finite(first) || !finite(last) || first <= 0) return "WAIT";
        double change = (last - first) / first;
        if (Math.abs(change) < 0.00015) return "WAIT";
        return change > 0 ? "UP" : "DOWN";
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && value > 0;
    }
}
