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
        String timing = prefs.getString("signal_timing", "CLOSE");
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
        if(candles.size()<5 || total<35) {
            return new LocalVisionAI.Result("WAIT",
                    "CHART READABILITY: LOW\nFound too few clear candle-colour regions. Zoom in on the candles and hide menus/indicators covering the chart.\nTimeframe setting: "+timeframe+
                    "\nCONFIDENCE: 0%\nThis is image-based analysis, not a direct broker data feed.");
        }

        int lookback = "PRECISION".equals(profile) ? (duration <= 60000L ? 8 : duration <= 180000L ? 12 : 16)
                : "FAST".equals(profile) ? (duration <= 60000L ? 4 : duration <= 180000L ? 6 : 9)
                : (duration <= 60000L ? 5 : duration <= 180000L ? 8 : 12);
        int recentStart=Math.max(0,candles.size()-lookback);
        int recentUp=0,recentDown=0,recentFlat=0;
        for(int i=recentStart;i<candles.size();i++) {
            int d=candles.get(i).direction();
            if(d>0) recentUp++; else if(d<0) recentDown++; else recentFlat++;
        }
        double overall=(totalGreen-totalRed)/(double)total;
        double recentBalance=(recentUp-recentDown)/(double)Math.max(1,recentUp+recentDown);
        int firstY=candles.get(Math.max(0,candles.size()-Math.min(lookback,candles.size()))).y;
        int lastY=candles.get(candles.size()-1).y;
        double visualSlope=(firstY-lastY)/(double)h; // positive means later regions trend visually upward
        double score=(0.48*overall)+(0.38*recentBalance)+(0.14*Math.max(-1,Math.min(1,visualSlope*3.0)));
        double strength=Math.abs(score);
        double expiryAdjustment = duration <= 60000L ? 0.06 : duration <= 180000L ? 0.03 : 0.0;
        double profileAdjustment = "PRECISION".equals(profile) ? 0.06 : "FAST".equals(profile) ? 0.02 : 0.0;
        double required=Math.max(0.10, Math.min(0.42, threshold/100.0 + expiryAdjustment + profileAdjustment));
        int confirmationGap = duration <= 60000L ? 3 : 2;
        String signal="WAIT";
        if(score>required && recentUp>=recentDown+confirmationGap) signal="UP";
        else if(score < -required && recentDown>=recentUp+confirmationGap) signal="DOWN";

        int confidence=(int)Math.round(Math.max(0,Math.min(79, 42+strength*48)));
        if("WAIT".equals(signal)) confidence=Math.min(confidence,49);
        String directionText="UP".equals(signal)?"bullish visual pressure":"DOWN".equals(signal)?"bearish visual pressure":"mixed/unclear candle evidence";
        String timingText="FORMING".equalsIgnoreCase(timing)?"forming-candle mode":"candle-close mode";
        StringBuilder details=new StringBuilder();
        details.append("ON-DEVICE CHART ANALYSIS\n");
        details.append("Mode: ").append("SCALPING".equalsIgnoreCase(mode)?"Scalping":"Timed signal").append("\n");
        details.append("Chart timeframe setting: ").append(timeframe).append("\n");
        details.append("Timed strategy: ").append(duration <= 60000L ? "1-minute fast confirmation" : duration <= 180000L ? "3-minute balanced confirmation" : "5-minute broader-lookback confirmation").append("\n");
        details.append("Timing: ").append(timingText).append("\n");
        details.append("Visual candle columns: ").append(columnsWithColour).append("\n");
        details.append("Recent coloured columns: ").append(recentUp).append(" bullish, ").append(recentDown).append(" bearish, ").append(recentFlat).append(" mixed\n");
        details.append("Read: ").append(directionText).append(".\n");
        details.append("CONFIDENCE: ").append(confidence).append("%\n");
        details.append("Confidence is an experimental image-strength score, not a measured win probability. No broker feed or OCR price reading is available.");
        details.append("\nSpeed/precision profile changes sampling and confirmation threshold; it does not make this pixel-based analyzer a trained AI model.");
        if("SCALPING".equalsIgnoreCase(mode)) {
            details.append("\nEntry/TP/SL: not shown because reliable numeric price levels cannot be read from this image. Do not invent prices.");
        } else {
            details.append("\nSelected tracking duration: ").append(formatDuration(duration));
        }
        if("CLOSE".equalsIgnoreCase(timing)) details.append("\nNote: a screenshot cannot prove the candle has closed; capture again after the candle closes.");
        return new LocalVisionAI.Result(signal,details.toString());
    }

    private static String formatDuration(long ms) {
        long s=Math.max(1,ms/1000L);
        if(s<60) return s+" seconds";
        return (s/60)+"m"+(s%60==0?"":" "+(s%60)+"s");
    }
}
