package com.diablo.scanner;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

public final class ChartMemory {

    private static final String PREFS = "diablo_chart_memory";
    private static final String MEMORY = "patterns";
    private static final int GRID = 16;
    private static final int MAX_PATTERNS = 100;

    private ChartMemory() {
    }

    private static String fingerprint(Bitmap bitmap) {
        Bitmap small = Bitmap.createScaledBitmap(
                bitmap, GRID, GRID, true
        );

        StringBuilder result = new StringBuilder();

        for (int y = 0; y < GRID; y++) {
            for (int x = 0; x < GRID; x++) {
                int pixel = small.getPixel(x, y);

                int red = (pixel >> 16) & 255;
                int green = (pixel >> 8) & 255;
                int blue = pixel & 255;

                int brightness =
                        (red * 30 + green * 59 + blue * 11) / 100;

                result.append(String.format(
                        Locale.US, "%02x", brightness
                ));
            }
        }

        if (small != bitmap && !small.isRecycled()) {
            small.recycle();
        }

        return result.toString();
    }

    private static double similarity(String first, String second) {
        if (first == null || second == null
                || first.length() != second.length()
                || first.length() == 0) {
            return 0;
        }

        int totalDifference = 0;
        int count = first.length() / 2;

        for (int i = 0; i < first.length(); i += 2) {
            int a = Integer.parseInt(
                    first.substring(i, i + 2), 16
            );

            int b = Integer.parseInt(
                    second.substring(i, i + 2), 16
            );

            totalDifference += Math.abs(a - b);
        }

        return Math.max(
                0,
                100.0 * (1.0 - totalDifference / (255.0 * count))
        );
    }

    public static synchronized String rememberAndCompare(
            Context context,
            Bitmap chart,
            String signal,
            int score
    ) {
        if (chart == null || chart.isRecycled()) {
            return "No chart available for comparison.";
        }

        try {
            SharedPreferences prefs =
                    context.getApplicationContext()
                            .getSharedPreferences(
                                    PREFS, Context.MODE_PRIVATE
                            );

            JSONArray old = new JSONArray(
                    prefs.getString(MEMORY, "[]")
            );

            String current = fingerprint(chart);

            double bestSimilarity = 0;
            JSONObject bestMatch = null;

            for (int i = 0; i < old.length(); i++) {
                JSONObject item = old.optJSONObject(i);

                if (item == null) {
                    continue;
                }

                double value = similarity(
                        current,
                        item.optString("fingerprint", "")
                );

                if (value > bestSimilarity) {
                    bestSimilarity = value;
                    bestMatch = item;
                }
            }

            long now = System.currentTimeMillis();

            boolean duplicate =
                    bestMatch != null && bestSimilarity >= 98.0;

            JSONArray updated = new JSONArray();
            JSONObject saved = null;

            if (duplicate) {
                saved = new JSONObject(bestMatch.toString());
                saved.put("lastSeen", now);
                saved.put("seenCount",
                        saved.optInt("seenCount", 1) + 1);
                saved.put("latestSignal", signal);
                saved.put("latestScore", score);
            } else {
                saved = new JSONObject();
                saved.put("fingerprint", current);
                saved.put("firstSeen", now);
                saved.put("lastSeen", now);
                saved.put("seenCount", 1);
                saved.put("latestSignal", signal);
                saved.put("latestScore", score);
            }

            updated.put(saved);

            for (int i = 0;
                 i < old.length() && updated.length() < MAX_PATTERNS;
                 i++) {

                JSONObject item = old.optJSONObject(i);

                if (item == null) {
                    continue;
                }

                if (duplicate
                        && item.optLong("firstSeen", -1)
                        == saved.optLong("firstSeen", -2)) {
                    continue;
                }

                updated.put(item);
            }

            prefs.edit()
                    .putString(MEMORY, updated.toString())
                    .apply();

            if (bestMatch == null) {
                return "New chart pattern saved. No previous match yet.";
            }

            return String.format(
                    Locale.US,
                    "Closest saved chart pattern: %.1f%% visual similarity. "
                            + "Previous recorded signal: %s. "
                            + "This is not a prediction.",
                    bestSimilarity,
                    bestMatch.optString("latestSignal", "UNKNOWN")
            );

        } catch (Exception e) {
            return "Chart memory could not be updated.";
        }
    }

    public static int getPatternCount(Context context) {
        try {
            SharedPreferences prefs =
                    context.getApplicationContext()
                            .getSharedPreferences(
                                    PREFS, Context.MODE_PRIVATE
                            );

            return new JSONArray(
                    prefs.getString(MEMORY, "[]")
            ).length();

        } catch (Exception e) {
            return 0;
        }
    }

    public static void clear(Context context) {
        context.getApplicationContext()
                .getSharedPreferences(
                        PREFS, Context.MODE_PRIVATE
                )
                .edit()
                .remove(MEMORY)
                .apply();
    }
}
