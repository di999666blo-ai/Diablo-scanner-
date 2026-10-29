package com.diablo.scanner;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fast on-device chart-image feature extractor. It does not use a bundled
 * language model or claim a verified win probability. It analyses visible
 * candle-like red/green regions and returns WAIT when the image is unclear.
 */
public final class ChartAnalyzer {
    private ChartAnalyzer() {}

    private static final class Candle {
        final int x;
        final int y;
        final int green;
        final int red;
        Candle(int x, int y, int green, int red) {
            this.x=x; this.y=y; this.green=green; this.red=red;
        }
        int direction() {
            int total=green+red;
            if(total==0) return 0;
            double balance=(green-red)/(double)total;
            return balance > 0.12 ? 1 : balance < -0.12 ? -1 : 0;
        }
    }

    public static LocalVisionAI.Result analyze(Context context, Bitmap bitmap) {
        if (context == null || bitmap == null || bitmap.isRecycled()
                || bitmap.getWidth() < 80 || bitmap.getHeight() < 60) {
            return new LocalVisionAI.Result("WAIT",
                    "The image is too small or unavailable. Use a clear chart screenshot.");
        }

        SharedPreferences prefs = context.getSharedPreferences("diablo_scanner", Context.MODE_PRIVATE);
        int threshold = prefs.getInt("threshold", 15);
        String timeframe = prefs.getString("chart_timeframe", "1m");
        String mode = prefs.getString("trading_mode", "TIMED");
        String timing = prefs.getString("signal_timing", "FORMING");
        String profile = prefs.getString("timed_analysis_profile", "BALANCED");
        long duration = prefs.getLong("trade_duration_ms", 60000L);
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        int step = Math.max(1, Math.max(w,h)/600);
        int xStep = Math.max(2, w/180);
        List<Candle> candles = new ArrayList<>();
        long totalGreen=0, totalRed=0;
        int columnsWithColour=0;

        // Scan vertical slices, counting saturated red/green candle pixels.
        for (int x=Math.max(1,w/40); x<w-Math.max(1,w/40); x+=xStep) {
            int greens=0, reds=0, ySum=0, yCount=0;
            for (int y=Math.max(1,h/12); y<h-Math.max(1,h/12); y+=step) {
                int c=bitmap.getPixel(x,y);
                int r=Color.red(c), g=Color.green(c), b=Color.blue(c);
                int max=Math.max(r,Math.max(g,b));
                if(max < 75) continue;
                boolean green=g>85 && g>r*1.20 && g>b*1.08;
                boolean red=r>90 && r>g*1.20 && r>b*1.12;
                if(green) { greens++; totalGreen++; ySum+=y; yCount++; }
                else if(red) { reds++; totalRed++; ySum+=y; yCount++; }
            }
            int count=greens+reds;
            if(count >= Math.max(2, h/(step*100))) {
                candles.add(new Candle(x, yCount==0 ? h/2 : ySum/yCount, greens, reds));
                columnsWithColour++;
            }
        }

        long total=totalGreen+totalRed;
        // Fail closed when the captured screen does not contain a plausible chart.
        // A few red/green UI buttons or indicator labels are not sufficient evidence.
        if(candles.size()<8 || total<70 || !hasPlausibleChartStructure(candles, totalGreen, totalRed, w, h)) {
            return new LocalVisionAI.Result("WAIT",
                    "NO VISIBLE CHART\\nNo readable trading chart/candles were detected on the screen. Open the broker chart so the candles are visible, close menus or pop-ups, then tap D to scan again.\\nNo UP/DOWN signal was generated.");
        }

        // Expiry horizon changes the evidence window independently of chart timeframe.
        // Pixel columns are not guaranteed to map one-to-one to actual broker candles.
        int durationBucket = duration <= 30000L ? 0 : duration <= 60000L ? 1
                : duration <= 120000L ? 2 : duration <= 180000L ? 3
                : duration <= 300000L ? 4 : 5;
        int[] fastWindows = {3, 4, 5, 6, 8, 9};
        int[] balancedWindows = {4, 5, 7, 8, 10, 12};
        int[] precisionWindows = {6, 8, 10, 12, 14, 16};
        int lookback = "PRECISION".equals(profile) ? precisionWindows[durationBucket]
                : "FAST".equals(profile) ? fastWindows[durationBucket]
                : balancedWindows[durationBucket];
        int recentStart=Math.max(0,candles.size()-lookback);
        int recentUp=0,recentDown=0,recentFlat=0;
        for(int i=recentStart;i<candles.size();i++) {
            int d=candles.get(i).direction();
            if(d>0) recentUp++; else if(d<0) recentDown++; else recentFlat++;
        }
        // Give each candle-like column equal influence. Raw pixel totals let one
        // unusually large candle dominate the entire read (often causing UP bias).
        int allUp=0, allDown=0;
        for (Candle candle : candles) {
            int d=candle.direction();
            if (d>0) allUp++;
            else if (d<0) allDown++;
        }
        double overall=(allUp-allDown)/(double)Math.max(1,allUp+allDown);
        double recentBalance=(recentUp-recentDown)/(double)Math.max(1,recentUp+recentDown);
        int firstY=candles.get(Math.max(0,candles.size()-Math.min(lookback,candles.size()))).y;
        int lastY=candles.get(candles.size()-1).y;
        double visualSlope=(firstY-lastY)/(double)h; // positive means later regions trend visually upward
        int slopeStart = Math.max(0, candles.size() - Math.min(lookback, candles.size()));
        double slopeSum = 0.0;
        int slopePairs = 0;
        for (int i = slopeStart + 1; i < candles.size(); i++) {
            slopeSum += candles.get(i - 1).y - candles.get(i).y;
            slopePairs++;
        }
        double recentPriceSlope = slopePairs == 0 ? 0.0 : slopeSum / (slopePairs * (double) h);
        double normalizedSlope = Math.max(-1.0, Math.min(1.0, visualSlope * 3.0));
        double normalizedRecentSlope = Math.max(-1.0, Math.min(1.0, recentPriceSlope * 8.0));
        // Recent price path gets substantial weight; colour-count bias alone cannot force UP.
        double score=(0.15*overall)+(0.35*recentBalance)+(0.20*normalizedSlope)+(0.30*normalizedRecentSlope);
        double strength=Math.abs(score);
        double expiryAdjustment = duration <= 60000L ? 0.06 : duration <= 180000L ? 0.03 : 0.0;
        double profileAdjustment = "PRECISION".equals(profile) ? 0.06 : "FAST".equals(profile) ? 0.02 : 0.0;
        double required=Math.max(0.10, Math.min(0.42, threshold/100.0 + expiryAdjustment + profileAdjustment));
        int confirmationGap = duration <= 60000L ? 3 : 2;
        String signal="WAIT";
        String patternSummary = detectTimedPatterns(candles, h);
        boolean bullishPattern = patternSummary.contains("bullish confirmation");
        boolean bearishPattern = patternSummary.contains("bearish confirmation");
        double patternAdjustment = bullishPattern && !bearishPattern ? 0.04
                : bearishPattern && !bullishPattern ? -0.04 : 0.0;
        score = Math.max(-1.0, Math.min(1.0, score + patternAdjustment));
        strength = Math.abs(score);
        // Mandatory confirmation gate: price path, recent momentum, score and
        // candle-colour majority must all agree. No single pattern/score can override conflict.
        boolean fallingStructure = normalizedRecentSlope < -0.10 && normalizedSlope < -0.04;
        boolean risingStructure = normalizedRecentSlope > 0.10 && normalizedSlope > 0.04;
        boolean bearishMomentumConfirmed = fallingStructure && score < -required
                && recentDown >= recentUp + confirmationGap;
        boolean bullishMomentumConfirmed = risingStructure && score > required
                && recentUp >= recentDown + confirmationGap;
        if (bearishMomentumConfirmed) signal = "DOWN";
        else if (bullishMomentumConfirmed) signal = "UP";

        // In Timed Signal, CLOSE mode means the user wants a confirmed candle-close
        // before an entry; a screenshot alone cannot verify that boundary. Do not apply
        // that entry-timing gate to Guided Scalping: that mode is explicitly analysing
        // the visible structure for a directional setup, and the old unconditional gate
        // forced every Hybrid Fast scalping scan to WAIT even when the chart was readable.
        boolean candleCloseUnverified = !"SCALPING".equalsIgnoreCase(mode)
                && "CLOSE".equalsIgnoreCase(timing);
        if (candleCloseUnverified) signal = "WAIT";

        // This is a screen-only preliminary visual analyzer. It cannot verify
        // OHLC-derived order blocks/FVG/ATR, but those market-data-only checks must
        // not silently suppress every valid visual direction in Timed Signal.
        // Direction is released only when the independent visual momentum gates above
        // agree. Never describe this as a verified OB/FVG or a measured win probability.

        int confidence=(int)Math.round(Math.max(0,Math.min(79, 42+strength*48)));
        if("WAIT".equals(signal)) confidence=Math.min(confidence,49);
        String directionText="UP".equals(signal)?"bullish visual pressure":"DOWN".equals(signal)?"bearish visual pressure":"mixed/unclear candle evidence";
        String timingText="FORMING".equalsIgnoreCase(timing)?"forming-candle mode":"candle-close mode";
        StringBuilder details=new StringBuilder();
        details.append("ON-DEVICE CHART ANALYSIS\n");
        details.append("Mode: ").append("SCALPING".equalsIgnoreCase(mode)?"Scalping":"Timed signal").append("\n");
        details.append("Chart timeframe setting: ").append(timeframe).append("\n");
        details.append("Trade expiry horizon: ").append(formatDuration(duration)).append("\n");
        details.append("Timeframe/expiry handling: chart timeframe setting (").append(timeframe)
                .append(") and trade expiry are independent; visual evidence window adapts to expiry. ")
                .append("Exact candle boundaries are not recoverable from pixels alone.\n");
        details.append("Timing: ").append(timingText).append("\n");
        details.append("Visual candle columns: ").append(columnsWithColour).append("\n");
        details.append("Timed pattern library: ").append(patternSummary).append("\n");
        details.append("Recent coloured columns: ").append(recentUp).append(" bullish, ").append(recentDown).append(" bearish, ").append(recentFlat).append(" mixed\n");
        details.append("Read: ").append(directionText).append(".\n");
        details.append("CONFIDENCE: ").append(confidence).append("%\n");
        details.append("Confidence is an experimental image-strength score, not a measured win probability. No broker feed or OCR price reading is available.");
        details.append("\nMandatory visual checks: candle-like structure, recent direction and price-path agreement.");
        details.append("\nMomentum gate: visual slope and recent candle-colour agreement were calculated and used as preliminary evidence; structured OHLC momentum is NOT VERIFIED.");
        details.append("\nOrder block / FVG / ATR: NOT VERIFIED — this screen-only analyzer cannot reliably extract structured OHLC zones or ATR. Do not treat the visual read as a confirmed institutional setup.");
        if ("UP".equals(signal) || "DOWN".equals(signal)) {
            details.append("\nSignal status: PRELIMINARY VISUAL DIRECTION only; confirm price levels and setup manually before any trade.");
        } else {
            details.append("\nSignal status: WAIT because the visible direction/momentum evidence did not pass the analyzer's confirmation gates.");
        }
        if (candleCloseUnverified) details.append("\nCANDLE CLOSE UNVERIFIED: a static screenshot cannot prove candle closure. Final decision forced to WAIT.");
        details.append("\nSpeed/precision profile changes sampling and confirmation threshold; it does not make this pixel-based analyzer a trained AI model.");
        if("SCALPING".equalsIgnoreCase(mode)) {
            details.append("\nEntry/TP/SL: not shown because reliable numeric price levels cannot be read from this image. Do not invent prices.");
        } else {
            details.append("\nSelected tracking duration: ").append(formatDuration(duration));
        }
        if("CLOSE".equalsIgnoreCase(timing)) details.append("\nNote: a screenshot cannot prove the candle has closed; capture again after the candle closes.");
        return new LocalVisionAI.Result(signal,details.toString());
    }

    /**
     * Reject screens with scattered coloured UI pixels but no plausible candle chart.
     * Conservative by design: uncertain screens return WAIT, never a direction.
     */
    private static boolean hasPlausibleChartStructure(List<Candle> candles,
            long totalGreen, long totalRed, int width, int height) {
        if (candles == null || candles.size() < 8) return false;
        long total = totalGreen + totalRed;
        // A genuine strong trend may show mostly one candle colour. Requiring at
        // least 12 red AND 12 green pixels rejected clean one-direction charts
        // (the exact Meta/Coffee trend case) before direction analysis could run.
        // Keep the total-pixel and spatial-structure checks to reject coloured UI noise.
        if (total < 70 || (totalGreen < 12 && totalRed < 12)) return false;
        int n = candles.size();
        if (candles.get(n - 1).x - candles.get(0).x < width * 0.18) return false;
        int distinctRows = 0, minY = height, maxY = 0, activeRecent = 0;
        int recentStart = Math.max(0, n - Math.min(12, n));
        for (int i = 0; i < n; i++) {
            Candle candle = candles.get(i);
            minY = Math.min(minY, candle.y);
            maxY = Math.max(maxY, candle.y);
            if (i >= recentStart && candle.green + candle.red >= 2) activeRecent++;
            if (i > 0 && Math.abs(candle.y - candles.get(i - 1).y) >= Math.max(2, height / 100))
                distinctRows++;
        }
        return activeRecent >= 5 && distinctRows >= Math.max(2, n / 12)
                && maxY - minY >= height * 0.015;
    }

    /**
     * Candidate pattern library for the lightweight TIMED SIGNAL engine.
     * The screen extractor sees coloured columns, not broker OHLC values, so
     * these are visual candidates and not guaranteed or fully confirmed patterns.
     */
    private static String detectTimedPatterns(List<Candle> candles, int imageHeight) {
        if (candles == null || candles.size() < 6) return "insufficient candles; WAIT";
        int n = candles.size();
        int d1 = candles.get(n - 1).direction();
        int d2 = candles.get(n - 2).direction();
        int d3 = candles.get(n - 3).direction();
        int y1 = candles.get(n - 1).y;
        int y2 = candles.get(n - 2).y;
        int y3 = candles.get(n - 3).y;
        java.util.ArrayList<String> found = new java.util.ArrayList<>();

        if (d2 < 0 && d1 > 0 && y1 < y2) found.add("bullish engulfing-like reversal candidate (bullish confirmation)");
        if (d2 > 0 && d1 < 0 && y1 > y2) found.add("bearish engulfing-like reversal candidate (bearish confirmation)");
        if (d3 < 0 && d2 == 0 && d1 > 0 && y1 < y3) found.add("morning-star-like reversal candidate (bullish confirmation)");
        if (d3 > 0 && d2 == 0 && d1 < 0 && y1 > y3) found.add("evening-star-like reversal candidate (bearish confirmation)");
        if (d3 > 0 && d2 > 0 && d1 > 0 && y1 < y2 && y2 < y3) found.add("three-candle bullish continuation");
        if (d3 < 0 && d2 < 0 && d1 < 0 && y1 > y2 && y2 > y3) found.add("three-candle bearish continuation");
        if (d1 == 0) found.add("doji/indecision-like column");

        int window = Math.min(12, n);
        int half = Math.max(3, window / 2);
        int oldMin = Integer.MAX_VALUE, oldMax = Integer.MIN_VALUE;
        int newMin = Integer.MAX_VALUE, newMax = Integer.MIN_VALUE;
        double oldMean = 0, newMean = 0;
        int split = n - half;
        for (int i = n - window; i < split; i++) {
            int y = candles.get(i).y;
            oldMin = Math.min(oldMin, y); oldMax = Math.max(oldMax, y); oldMean += y;
        }
        int oldCount = Math.max(1, split - (n - window));
        oldMean /= oldCount;
        for (int i = split; i < n; i++) {
            int y = candles.get(i).y;
            newMin = Math.min(newMin, y); newMax = Math.max(newMax, y); newMean += y;
        }
        newMean /= half;
        int rangeTolerance = Math.max(4, (int)(imageHeight * 0.025));
        if (Math.abs(oldMean - newMean) > imageHeight * 0.025) {
            found.add(newMean < oldMean ? "short-term upward price drift" : "short-term downward price drift");
        }
        if (newMax - newMin < oldMax - oldMin && Math.abs(newMean - oldMean) < imageHeight * 0.06) {
            found.add("triangle/wedge compression candidate");
        }
        int first = Math.max(0, n - window);
        int topA = -1, topB = -1, bottomA = -1, bottomB = -1;
        for (int i = first + 1; i < n - 1; i++) {
            int prev = candles.get(i - 1).y, cur = candles.get(i).y, next = candles.get(i + 1).y;
            if (cur < prev && cur < next) { topB = topA; topA = i; }
            if (cur > prev && cur > next) { bottomB = bottomA; bottomA = i; }
        }
        if (topA >= 0 && topB >= 0 && Math.abs(candles.get(topA).y - candles.get(topB).y) <= rangeTolerance)
            found.add("double-top/resistance candidate");
        if (bottomA >= 0 && bottomB >= 0 && Math.abs(candles.get(bottomA).y - candles.get(bottomB).y) <= rangeTolerance)
            found.add("double-bottom/support candidate");

        int previousMin = Integer.MAX_VALUE, previousMax = Integer.MIN_VALUE;
        for (int i = first; i < n - 1; i++) {
            previousMin = Math.min(previousMin, candles.get(i).y);
            previousMax = Math.max(previousMax, candles.get(i).y);
        }
        if (y1 < previousMin - rangeTolerance) found.add("upward range-break candidate (bullish confirmation)");
        if (y1 > previousMax + rangeTolerance) found.add("downward range-break candidate (bearish confirmation)");
        if (found.isEmpty()) return "no clear pattern candidate; WAIT if other evidence conflicts";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < found.size(); i++) {
            if (i > 0) result.append("; ");
            result.append(found.get(i));
        }
        result.append(". Library also tracks trend structure and candidate continuation/reversal context. Head-and-shoulders, flags, exact channels/wedges, wick-ratio patterns (hammer, shooting star, pin bar), liquidity sweeps and FVGs need reliable OHLC/wick extraction and are not claimed as confirmed by this pixel-only pass.");
        return result.toString();
    }

    private static String formatDuration(long ms) {
        long s=Math.max(1,ms/1000L);
        if(s<60) return s+" seconds";
        return (s/60)+"m"+(s%60==0?"":" "+(s%60)+"s");
    }
}
