package com.diablo.scanner;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared chart-presence and chart-context stage for every visual scanner path.
 *
 * OpenCV checks image structure and repeated candle-like components; ML Kit OCR
 * (when requested) reads visible labels/timeframe text. Neither is allowed to
 * manufacture market prices or a trade direction. Direction remains the job of
 * the selected analyzer/model and uncertain images fail closed to WAIT.
 */
public final class ChartVisionEngine {
    private static volatile Boolean openCvAvailable;

    private ChartVisionEngine() { }

    public static final class Report {
        public final boolean isChart;
        public final boolean hasColoredCandles;
        public final int candleCandidates;
        public final int coloredColumns;
        public final int verticalSegments;
        public final int horizontalSegments;
        public final String timeframe;
        public final String ocrText;
        public final String summary;
        public final Rect chartBounds;

        Report(boolean chart, boolean colored, int candidates, int columns,
               int vertical, int horizontal, String tf, String ocr,
               String summary, Rect bounds) {
            this.isChart = chart;
            this.hasColoredCandles = colored;
            this.candleCandidates = candidates;
            this.coloredColumns = columns;
            this.verticalSegments = vertical;
            this.horizontalSegments = horizontal;
            this.timeframe = tf;
            this.ocrText = ocr;
            this.summary = summary;
            this.chartBounds = bounds;
        }
    }

    public static Report inspect(Context context, Bitmap bitmap, boolean readText) {
        if (bitmap == null || bitmap.isRecycled() || bitmap.getWidth() < 100 || bitmap.getHeight() < 80) {
            return empty("Image is missing or too small to inspect.");
        }

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        Rect bounds = new Rect(
                Math.max(0, Math.round(width * 0.025f)),
                Math.max(0, Math.round(height * 0.08f)),
                Math.min(width, Math.round(width * 0.975f)),
                Math.min(height, Math.round(height * 0.91f)));

        int candidates = 0;
        int columns = 0;
        int vertical = 0;
        int horizontal = 0;
        double edgeDensity = 0.0;
        boolean nativeReady = ensureOpenCv();

        if (nativeReady) {
            Mat rgba = new Mat();
            Mat rgb = new Mat();
            Mat hsv = new Mat();
            Mat redA = new Mat();
            Mat redB = new Mat();
            Mat red = new Mat();
            Mat green = new Mat();
            Mat candleMask = new Mat();
            Mat gray = new Mat();
            Mat edges = new Mat();
            Mat lines = new Mat();
            Mat hierarchy = new Mat();
            try {
                Utils.bitmapToMat(bitmap, rgba);
                Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB);
                Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV);

                Core.inRange(hsv, new Scalar(0, 65, 55), new Scalar(13, 255, 255), redA);
                Core.inRange(hsv, new Scalar(165, 65, 55), new Scalar(180, 255, 255), redB);
                Core.bitwise_or(redA, redB, red);
                Core.inRange(hsv, new Scalar(32, 45, 45), new Scalar(105, 255, 255), green);
                Core.bitwise_or(red, green, candleMask);

                Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(2, 3));
                Imgproc.morphologyEx(candleMask, candleMask, Imgproc.MORPH_CLOSE, kernel);
                kernel.release();

                List<MatOfPoint> contours = new ArrayList<>();
                Mat contourInput = candleMask.clone();
                Imgproc.findContours(contourInput, contours, hierarchy,
                        Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
                contourInput.release();
                Set<Integer> xBins = new HashSet<>();
                int minCandX = width, minCandY = height, maxCandX = 0, maxCandY = 0;
                int minWidth = Math.max(2, width / 500);
                int maxWidth = Math.max(12, width / 22);
                int minHeight = Math.max(2, height / 300);
                int maxHeight = Math.max(12, height / 3);
                for (MatOfPoint contour : contours) {
                    org.opencv.core.Rect r = Imgproc.boundingRect(contour);
                    double area = Imgproc.contourArea(contour);
                    if (r.width >= minWidth && r.width <= maxWidth
                            && r.height >= minHeight && r.height <= maxHeight
                            && area >= 3.0
                            && r.y > height * 0.08 && r.y + r.height < height * 0.92) {
                        candidates++;
                        xBins.add(Math.max(0, r.x / Math.max(2, width / 90)));
                        minCandX = Math.min(minCandX, r.x);
                        minCandY = Math.min(minCandY, r.y);
                        maxCandX = Math.max(maxCandX, r.x + r.width);
                        maxCandY = Math.max(maxCandY, r.y + r.height);
                    }
                    contour.release();
                }
                columns = xBins.size();

                Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY);
                Imgproc.GaussianBlur(gray, gray, new Size(3, 3), 0);
                Imgproc.Canny(gray, edges, 55, 145);
                edgeDensity = Core.countNonZero(edges) / (double) Math.max(1, width * height);
                Imgproc.HoughLinesP(edges, lines, 1, Math.PI / 180.0,
                        Math.max(12, width / 45), Math.max(7, height / 65),
                        Math.max(3, height / 110));
                for (int i = 0; i < lines.rows(); i++) {
                    double[] line = lines.get(i, 0);
                    if (line == null || line.length < 4) continue;
                    double dx = Math.abs(line[2] - line[0]);
                    double dy = Math.abs(line[3] - line[1]);
                    if (dx <= Math.max(3.0, width * 0.006) && dy >= height * 0.018) vertical++;
                    if (dy <= Math.max(3.0, height * 0.006) && dx >= width * 0.08) horizontal++;
                }
                boolean coloredPixels = Core.countNonZero(candleMask) >= Math.max(35, width * height / 18000);
                boolean coloredChart = coloredPixels && candidates >= 5 && columns >= 5
                        && maxCandX - minCandX >= width * 0.12
                        && maxCandY - minCandY >= height * 0.008;
                if (!coloredChart) {
                    // Some broker themes render thin/hollow candles that split into
                    // several tiny OpenCV contours. Retain a second independent
                    // sampled-pixel check before declaring that no coloured chart exists.
                    int[] fallback = fallbackColorScan(bitmap);
                    if (fallback[0] >= 8 && fallback[1] >= 70) {
                        coloredChart = true;
                        columns = Math.max(columns, fallback[0]);
                    }
                }
                boolean structuredChart = vertical >= 7 && horizontal >= 2
                        && edgeDensity >= 0.002 && edgeDensity <= 0.28;
                if (coloredChart && maxCandX > minCandX && maxCandY > minCandY) {
                    // Locate the actual candle plotting region, then retain margins
                    // for nearby chart structure. OCR still runs on the full screenshot
                    // so price-axis labels outside the candle area are not lost.
                    int marginX = Math.max(width / 14, 12);
                    int marginY = Math.max(height / 12, 12);
                    bounds = new Rect(Math.max(0, minCandX - marginX),
                            Math.max(0, minCandY - marginY),
                            Math.min(width, maxCandX + marginX),
                            Math.min(height, maxCandY + marginY));
                }
                String ocr = readText ? readOcr(context, bitmap) : "";
                String tf = extractTimeframe(ocr);
                boolean chart = coloredChart || structuredChart;
                String summary = buildSummary(chart, nativeReady, candidates, columns,
                        vertical, horizontal, edgeDensity, tf, ocr);
                return new Report(chart, coloredChart, candidates, columns, vertical,
                        horizontal, tf, ocr, summary, bounds);
            } catch (Throwable failure) {
                // Continue to the conservative pure-Bitmap fallback below.
            } finally {
                rgba.release(); rgb.release(); hsv.release(); redA.release(); redB.release();
                red.release(); green.release(); candleMask.release(); gray.release();
                edges.release(); lines.release(); hierarchy.release();
            }
        }

        int[] fallback = fallbackColorScan(bitmap);
        boolean chart = fallback[0] >= 8 && fallback[1] >= 70;
        String ocr = readText ? readOcr(context, bitmap) : "";
        String tf = extractTimeframe(ocr);
        String summary = "OpenCV unavailable; basic colour/shape fallback only. "
                + "Detected " + fallback[0] + " separated candle-colour columns and "
                + fallback[1] + " coloured samples. "
                + (chart ? "Chart-like content detected." : "Chart could not be verified.");
        if (!tf.isEmpty()) summary += " OCR timeframe candidate: " + tf + ".";
        if (!ocr.isEmpty()) summary += " Visible text: " + shorten(ocr, 450);
        return new Report(chart, chart, 0, fallback[0], 0, 0, tf, ocr, summary, bounds);
    }

    public static Bitmap crop(Bitmap source, Rect bounds) {
        if (source == null || source.isRecycled()) return source;
        if (bounds == null) return source;
        int left = Math.max(0, Math.min(source.getWidth() - 1, bounds.left));
        int top = Math.max(0, Math.min(source.getHeight() - 1, bounds.top));
        int right = Math.max(left + 1, Math.min(source.getWidth(), bounds.right));
        int bottom = Math.max(top + 1, Math.min(source.getHeight(), bounds.bottom));
        if (left == 0 && top == 0 && right == source.getWidth() && bottom == source.getHeight()) return source;
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top);
    }

    private static boolean ensureOpenCv() {
        Boolean ready = openCvAvailable;
        if (ready != null) return ready;
        synchronized (ChartVisionEngine.class) {
            if (openCvAvailable == null) {
                boolean loaded = false;
                try { loaded = OpenCVLoader.initLocal(); } catch (Throwable ignored) { }
                openCvAvailable = loaded;
            }
            return openCvAvailable;
        }
    }

    private static int[] fallbackColorScan(Bitmap bitmap) {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        int xStep = Math.max(2, width / 140), yStep = Math.max(2, height / 100);
        int columns = 0, pixels = 0;
        for (int x = width / 30; x < width - width / 30; x += xStep) {
            int inColumn = 0;
            for (int y = height / 10; y < height - height / 10; y += yStep) {
                int c = bitmap.getPixel(x, y);
                int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                if ((r > 90 && r > g * 1.18 && r > b * 1.10)
                        || (g > 80 && g > r * 1.18 && g > b * 1.06)) {
                    inColumn++; pixels++;
                }
            }
            if (inColumn >= 2) columns++;
        }
        return new int[]{columns, pixels};
    }

    private static String readOcr(Context context, Bitmap bitmap) {
        if (context == null || bitmap == null || bitmap.isRecycled()) return "";
        TextRecognizer recognizer = null;
        try {
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            Text result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                    5, TimeUnit.SECONDS);
            StringBuilder text = new StringBuilder();
            for (Text.TextBlock block : result.getTextBlocks()) {
                String value = block.getText();
                if (value != null && !value.trim().isEmpty()) {
                    if (text.length() > 0) text.append(" | ");
                    text.append(value.trim());
                    if (text.length() >= 1200) break;
                }
            }
            return text.toString();
        } catch (Exception ignored) {
            return "";
        } finally {
            if (recognizer != null) {
                try { recognizer.close(); } catch (Exception ignored) { }
            }
        }
    }

    private static String extractTimeframe(String text) {
        if (text == null || text.isEmpty()) return "";
        Matcher matcher = Pattern.compile("(?i)(?<![A-Z0-9])(1|2|3|5|10|15|30|45|60|120|240)\\s?(m|min|mins|h|hr|hrs)(?![A-Z])")
                .matcher(text);
        if (!matcher.find()) return "";
        return matcher.group(1) + matcher.group(2).toLowerCase(Locale.ROOT);
    }

    private static String buildSummary(boolean chart, boolean cv, int candidates, int columns,
                                       int vertical, int horizontal, double edgeDensity,
                                       String timeframe, String ocr) {
        StringBuilder out = new StringBuilder();
        out.append(cv ? "OpenCV structural scan" : "Fallback image scan")
                .append(": ").append(chart ? "chart-like plotting area detected" : "chart not verified")
                .append("; candle-like components=").append(candidates)
                .append(", colour columns=").append(columns)
                .append(", vertical structures=").append(vertical)
                .append(", horizontal grid candidates=").append(horizontal)
                .append(", edge density=").append(String.format(Locale.US, "%.3f", edgeDensity)).append(".");
        if (!timeframe.isEmpty()) out.append(" OCR timeframe candidate=").append(timeframe).append(".");
        if (!ocr.isEmpty()) out.append(" OCR labels: ").append(shorten(ocr, 450));
        out.append(" OCR/shape output is supporting evidence, not verified OHLC data.");
        return out.toString();
    }

    private static String shorten(String value, int max) {
        if (value == null) return "";
        String clean = value.replaceAll("\\s+", " ").trim();
        return clean.length() > max ? clean.substring(0, max) + "…" : clean;
    }

    private static Report empty(String message) {
        return new Report(false, false, 0, 0, 0, 0, "", "", message,
                new Rect(0, 0, 1, 1));
    }
}
