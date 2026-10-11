package com.diablo.scanner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int PICK_CHART = 4101;
    private static final String PREFS = "diablo_scanner";
    private static final String HISTORY = "history";
    private static final String THRESHOLD = "threshold";

    private LinearLayout root;
    private TextView signalText;
    private TextView statusText;
    private TextView countText;

    private final int red = Color.rgb(255, 45, 65);
    private final int background = Color.rgb(12, 12, 16);
    private final int panel = Color.rgb(30, 30, 38);
    private final int white = Color.WHITE;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(Color.BLACK);

        buildDashboard();
        refreshCount();
    }

    private void buildDashboard() {

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(background);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 24, 20, 24);
        root.setBackgroundColor(background);

        scroll.addView(root);
        setContentView(scroll);

        TextView logo = new TextView(this);
        logo.setText("D");
        logo.setTextSize(62);
        logo.setTextColor(red);
        logo.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        logo.setGravity(Gravity.CENTER);
        root.addView(logo);

        TextView title = new TextView(this);
        title.setText("DIABLO SCANNER");
        title.setTextSize(25);
        title.setTextColor(white);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("D EDITION • CHART IMAGE SCANNER");
        subtitle.setTextSize(12);
        subtitle.setTextColor(red);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 4, 0, 22);
        root.addView(subtitle);

        addSectionTitle("LATEST VISUAL RESULT");

        signalText = new TextView(this);
        signalText.setText("WAIT");
        signalText.setTextSize(38);
        signalText.setTextColor(Color.YELLOW);
        signalText.setGravity(Gravity.CENTER);
        signalText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        signalText.setPadding(0, 18, 0, 8);
        root.addView(signalText);

        statusText = new TextView(this);
        statusText.setText(
            "Choose a saved chart screenshot to begin.\n" +
            "This is a basic image-analysis prototype."
        );
        statusText.setTextSize(14);
        statusText.setTextColor(white);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(8, 8, 8, 18);
        root.addView(statusText);

        countText = new TextView(this);
        countText.setTextColor(Color.LTGRAY);
        countText.setTextSize(12);
        countText.setGravity(Gravity.CENTER);
        countText.setPadding(0, 0, 0, 16);
        root.addView(countText);

        addButton("ANALYSE SAVED CHART", v -> chooseChart());
        addButton("SIGNAL HISTORY", v -> showHistory());
        addButton("CLEAR HISTORY", v -> clearHistory());
        addButton("SETTINGS / SENSITIVITY", v -> showSettings());

        TextView instructions = new TextView(this);

        instructions.setText(
            "HOW TO USE\n\n" +
            "1. Take a screenshot of your trading chart.\n" +
            "2. Return to DIABLO SCANNER.\n" +
            "3. Tap ANALYSE SAVED CHART.\n" +
            "4. Select your screenshot.\n" +
            "5. Review the experimental result."
        );

        instructions.setTextColor(Color.LTGRAY);
        instructions.setTextSize(13);
        instructions.setPadding(8, 24, 8, 12);
        root.addView(instructions);

        TextView warning = new TextView(this);

        warning.setText(
            "TRADING RISK WARNING\n\n" +
            "This tool counts certain red and green pixels " +
            "in an image. It does not understand market " +
            "structure or predict prices reliably.\n\n" +
            "Results can be wrong. No profits are guaranteed. " +
            "This version does not place trades."
        );

        warning.setTextColor(Color.rgb(255, 190, 120));
        warning.setTextSize(12);
        warning.setGravity(Gravity.CENTER);
        warning.setPadding(8, 20, 8, 8);
        root.addView(warning);
    }

    private void addSectionTitle(String text) {

        TextView heading = new TextView(this);

        heading.setText(text);
        heading.setTextSize(13);
        heading.setTextColor(red);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);
        heading.setPadding(0, 12, 0, 4);

        root.addView(heading);
    }

    private void addButton(
        String text,
        View.OnClickListener listener
    ) {

        Button button = new Button(this);

        button.setText(text);
        button.setTextColor(white);
        button.setTextSize(14);
        button.setAllCaps(false);

        button.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(panel)
        );

        LinearLayout.LayoutParams params =
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );

        params.setMargins(0, 6, 0, 6);

        root.addView(button, params);

        button.setOnClickListener(listener);
    }

    private void chooseChart() {

        Intent intent = new Intent(
            Intent.ACTION_OPEN_DOCUMENT
        );

        intent.addCategory(
            Intent.CATEGORY_OPENABLE
        );

        intent.setType("image/*");

        try {
            startActivityForResult(intent, PICK_CHART);
        } catch (Exception e) {
            Toast.makeText(
                this,
                "Could not open image picker.",
                Toast.LENGTH_LONG
            ).show();
        }
    }

    @Override
    protected void onActivityResult(
        int requestCode,
        int resultCode,
        Intent data
    ) {

        super.onActivityResult(
            requestCode,
            resultCode,
            data
        );

        if (requestCode != PICK_CHART ||
            resultCode != RESULT_OK ||
            data == null ||
            data.getData() == null) {
            return;
        }

        Uri uri = data.getData();

        try (
            InputStream input =
                getContentResolver().openInputStream(uri)
        ) {

            Bitmap bitmap =
                BitmapFactory.decodeStream(input);

            if (bitmap == null) {
                Toast.makeText(
                    this,
                    "Could not read that image.",
                    Toast.LENGTH_LONG
                ).show();

                return;
            }

            analyseBitmap(bitmap);
            bitmap.recycle();

        } catch (Exception e) {

            Toast.makeText(
                this,
                "Unable to read the selected image.",
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void analyseBitmap(Bitmap bitmap) {

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        int step = Math.max(
            1,
            Math.max(width, height) / 350
        );

        long green = 0;
        long redPixels = 0;
        long checked = 0;

        for (int y = 0; y < height; y += step) {

            for (int x = 0; x < width; x += step) {

                int pixel = bitmap.getPixel(x, y);

                int r = Color.red(pixel);
                int g = Color.green(pixel);
                int b = Color.blue(pixel);

                checked++;

                if (g > 95 &&
                    g > r * 1.22 &&
                    g > b * 1.08) {

                    green++;
                }

                if (r > 105 &&
                    r > g * 1.22 &&
                    r > b * 1.12) {

                    redPixels++;
                }
            }
        }

        double total = green + redPixels;

        double difference = total == 0
            ? 0
            : Math.abs(green - redPixels) / total;

        double threshold =
            prefs.getInt(THRESHOLD, 15) / 100.0;

        String signal;
        String detail;
        int colour;

        if (total < 20 || difference < threshold) {

            signal = "WAIT";
            colour = Color.YELLOW;

            detail =
                "There is not enough clear red/green " +
                "dominance in this screenshot.";

        } else if (green > redPixels) {

            signal = "UP BIAS";
            colour = Color.rgb(50, 220, 120);

            detail =
                "The image contains more green-like " +
                "pixels than red-like pixels.";

        } else {

            signal = "DOWN BIAS";
            colour = red;

            detail =
                "The image contains more red-like " +
                "pixels than green-like pixels.";
        }

        int score = (int) Math.round(
            Math.min(95, 50 + difference * 45)
        );

        if (total < 20) {
            score = 0;
        }

        signalText.setText(signal);
        signalText.setTextColor(colour);

        statusText.setText(
            detail +
            "\nVisual balance: " + score +
            "% heuristic score." +
            "\nSampled pixels: " + checked +
            "\nThis score is not a probability of profit."
        );

        saveHistory(
            signal,
            score,
            green,
            redPixels
        );

        refreshCount();
    }

    private void saveHistory(
        String signal,
        int score,
        long green,
        long redPixels
    ) {

        try {

            JSONArray old = new JSONArray(
                prefs.getString(HISTORY, "[]")
            );

            JSONArray updated = new JSONArray();

            JSONObject item = new JSONObject();

            item.put(
                "time",
                new SimpleDateFormat(
                    "yyyy-MM-dd HH:mm",
                    Locale.getDefault()
                ).format(new Date())
            );

            item.put("signal", signal);
            item.put("score", score);
            item.put("green", green);
            item.put("red", redPixels);

            updated.put(item);

            for (
                int i = 0;
                i < old.length() && i < 49;
                i++
            ) {
                updated.put(old.get(i));
            }

            prefs.edit()
                .putString(HISTORY, updated.toString())
                .apply();

        } catch (Exception ignored) {
        }
    }

    private void showHistory() {

        try {

            JSONArray history = new JSONArray(
                prefs.getString(HISTORY, "[]")
            );

            if (history.length() == 0) {

                new AlertDialog.Builder(this)
                    .setTitle("Signal history")
                    .setMessage(
                        "No chart images analysed yet."
                    )
                    .setPositiveButton("OK", null)
                    .show();

                return;
            }

            StringBuilder text = new StringBuilder();

            for (int i = 0; i < history.length(); i++) {

                JSONObject item =
                    history.getJSONObject(i);

                text.append(item.optString("time"))
                    .append(" — ")
                    .append(item.optString("signal"))
                    .append(" (")
                    .append(item.optInt("score"))
                    .append("% heuristic score)\n");
            }

            new AlertDialog.Builder(this)
                .setTitle("Recent visual results")
                .setMessage(text.toString())
                .setPositiveButton("CLOSE", null)
                .show();

        } catch (Exception e) {

            Toast.makeText(
                this,
                "Could not load history.",
                Toast.LENGTH_SHORT
            ).show();
        }
    }

    private void clearHistory() {

        new AlertDialog.Builder(this)
            .setTitle("Clear history?")
            .setMessage(
                "This removes saved results from this phone."
            )
            .setNegativeButton("CANCEL", null)
            .setPositiveButton(
                "CLEAR",
                (dialog, which) -> {

                    prefs.edit()
                        .remove(HISTORY)
                        .apply();

                    refreshCount();

                    Toast.makeText(
                        this,
                        "History cleared.",
                        Toast.LENGTH_SHORT
                    ).show();
                }
            )
            .show();
    }

    private void showSettings() {

        String[] options = {
            "Low sensitivity (10%)",
            "Normal sensitivity (15%)",
            "High sensitivity (25%)"
        };

        int current =
            prefs.getInt(THRESHOLD, 15);

        int selected =
            current == 10 ? 0 :
            current == 25 ? 2 : 1;

        new AlertDialog.Builder(this)
            .setTitle("Visual sensitivity")
            .setSingleChoiceItems(
                options,
                selected,
                (dialog, which) -> {

                    int value =
                        which == 0 ? 10 :
                        which == 2 ? 25 : 15;

                    prefs.edit()
                        .putInt(THRESHOLD, value)
                        .apply();

                    dialog.dismiss();

                    Toast.makeText(
                        this,
                        "Sensitivity saved.",
                        Toast.LENGTH_SHORT
                    ).show();
                }
            )
            .setNegativeButton("CANCEL", null)
            .show();
    }

    private void refreshCount() {

        if (countText == null) {
            return;
        }

        try {

            JSONArray history = new JSONArray(
                prefs.getString(HISTORY, "[]")
            );

            countText.setText(
                "Saved results on this phone: " +
                history.length()
            );

        } catch (Exception e) {

            countText.setText(
                "Saved results on this phone: 0"
            );
        }
    }
}
