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
import android.provider.Settings;
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

    private SharedPreferences prefs;

    private final int red = Color.rgb(255, 45, 65);
    private final int background = Color.rgb(12, 12, 16);
    private final int panelColor = Color.rgb(30, 30, 38);
    private final int white = Color.WHITE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(Color.BLACK);

        buildDashboard();
        refreshCount();
        refreshLatestResult();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (prefs != null) {
            refreshCount();
            refreshLatestResult();
        }
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
        subtitle.setText(
            "D EDITION • CHART IMAGE SCANNER"
        );
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
            "This is an experimental image-analysis tool."
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

        addButton(
            "ANALYSE SAVED CHART",
            v -> chooseChart()
        );

        addButton(
            "START FLOATING BUBBLE",
            v -> startFloatingBubble()
        );

        addButton(
            "SIGNAL HISTORY",
            v -> showHistory()
        );

        addButton(
            "CLEAR HISTORY",
            v -> clearHistory()
        );

        addButton(
            "SETTINGS / SENSITIVITY",
            v -> showSettings()
        );

        TextView instructions = new TextView(this);

        instructions.setText(
            "HOW TO USE\n\n" +
            "1. Take a screenshot of your trading chart.\n" +
            "2. Open DIABLO SCANNER.\n" +
            "3. Tap ANALYSE SAVED CHART.\n" +
            "4. Select your screenshot.\n" +
            "5. Review the experimental visual result.\n\n" +
            "FLOATING BUBBLE\n\n" +
            "1. Tap START FLOATING BUBBLE.\n" +
            "2. Allow display-over-other-apps permission.\n" +
            "3. Return to DIABLO SCANNER.\n" +
            "4. Tap START FLOATING BUBBLE again.\n" +
            "5. Tap the D bubble to open its compact panel."
        );

        instructions.setTextColor(Color.LTGRAY);
        instructions.setTextSize(13);
        instructions.setPadding(8, 24, 8, 12);
        root.addView(instructions);

        TextView warning = new TextView(this);

        warning.setText(
            "TRADING RISK WARNING\n\n" +
            "This prototype counts certain red and green " +
            "pixels in a selected image. It does not " +
            "reliably predict market direction.\n\n" +
            "The floating bubble currently displays the " +
            "latest saved result. It does not scan a live " +
            "chart automatically.\n\n" +
            "Results may be wrong. No profits are guaranteed. " +
            "This app does not place trades."
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
            android.content.res.ColorStateList.valueOf(
                panelColor
            )
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

    // START THE FLOATING BUBBLE

    private void startFloatingBubble() {

        if (!Settings.canDrawOverlays(this)) {

            try {

                Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
                );

                startActivity(intent);

                Toast.makeText(
                    this,
                    "Allow DIABLO SCANNER to display over other apps. Then return and tap START FLOATING BUBBLE again.",
                    Toast.LENGTH_LONG
                ).show();

            } catch (Exception e) {

                Toast.makeText(
                    this,
                    "Could not open overlay permission settings. Find DIABLO SCANNER in your phone settings and allow display over other apps.",
                    Toast.LENGTH_LONG
                ).show();
            }

            return;
        }

        try {

            Intent serviceIntent = new Intent(
                this,
                FloatingBubbleService.class
            );

            startService(serviceIntent);

            Toast.makeText(
                this,
                "DIABLO floating bubble started.",
                Toast.LENGTH_SHORT
            ).show();

        } catch (Exception e) {

            Toast.makeText(
                this,
                "Could not start the bubble. Check the build and manifest.",
                Toast.LENGTH_LONG
            ).show();
        }
    }

    // SELECT A SAVED CHART IMAGE

    private void chooseChart() {

        Intent intent = new Intent(
            Intent.ACTION_OPEN_DOCUMENT
        );

        intent.addCategory(
            Intent.CATEGORY_OPENABLE
        );

        intent.setType("image/*");

        try {

            startActivityForResult(
                intent,
                PICK_CHART
            );

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

        if (
            requestCode != PICK_CHART ||
            resultCode != RESULT_OK ||
            data == null ||
            data.getData() == null
        ) {
            return;
        }

        Uri uri = data.getData();

        try (
            InputStream input =
                getContentResolver().openInputStream(uri)
        ) {

            if (input == null) {
                showImageError();
                return;
            }

            Bitmap bitmap =
                BitmapFactory.decodeStream(input);

            if (bitmap == null) {

                showImageError();
                return;
            }

            try {
                analyseBitmap(bitmap);
            } finally {
                bitmap.recycle();
            }

        } catch (Exception e) {

            showImageError();
        }
    }

    private void showImageError() {

        Toast.makeText(
            this,
            "Unable to read that image. Please choose another screenshot.",
            Toast.LENGTH_LONG
        ).show();
    }

    // BASIC RED/GREEN IMAGE ANALYSIS

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

                if (
                    g > 95 &&
                    g > r * 1.22 &&
                    g > b * 1.08
                ) {
                    green++;
                }

                if (
                    r > 105 &&
                    r > g * 1.22 &&
                    r > b * 1.12
                ) {
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

    // SAVE THE LATEST RESULT AND HISTORY

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

        } catch (Exception e) {

            Toast.makeText(
                this,
                "Could not save the result.",
                Toast.LENGTH_SHORT
            ).show();
        }
    }

    // SHOW THE LATEST SAVED RESULT

    private void refreshLatestResult() {

        if (signalText == null || statusText == null) {
            return;
        }

        try {

            JSONArray history = new JSONArray(
                prefs.getString(HISTORY, "[]")
            );

            if (history.length() == 0) {

                signalText.setText("WAIT");
                signalText.setTextColor(Color.YELLOW);

                statusText.setText(
                    "No saved chart results yet.\n" +
                    "Choose a screenshot to begin."
                );

                return;
            }

            JSONObject latest =
                history.getJSONObject(0);

            String signal =
                latest.optString("signal", "WAIT");

            String time =
                latest.optString("time", "Unknown time");

            int score =
                latest.optInt("score", 0);

            signalText.setText(signal);

            if (signal.contains("UP")) {

                signalText.setTextColor(
                    Color.rgb(50, 220, 120)
                );

            } else if (signal.contains("DOWN")) {

                signalText.setTextColor(red);

            } else {

                signalText.setTextColor(Color.YELLOW);
            }

            statusText.setText(
                "Latest saved visual result\n" +
                "Time: " + time + "\n" +
                "Heuristic score: " + score + "%\n\n" +
                "This is not a live market signal."
            );

        } catch (Exception e) {

            signalText.setText("WAIT");
            signalText.setTextColor(Color.YELLOW);

            statusText.setText(
                "Could not load the latest saved result."
            );
        }
    }

    // DISPLAY SAVED HISTORY

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

            for (
                int i = 0;
                i < history.length();
                i++
            ) {

                JSONObject item =
                    history.getJSONObject(i);

                text.append(
                    item.optString("time", "Unknown time")
                )
                .append(" — ")
                .append(
                    item.optString("signal", "WAIT")
                )
                .append(" (")
                .append(item.optInt("score", 0))
                .append("% heuristic score)\n\n");
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

    // CLEAR HISTORY

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
                    refreshLatestResult();

                    Toast.makeText(
                        this,
                        "History cleared.",
                        Toast.LENGTH_SHORT
                    ).show();
                }
            )
            .show();
    }

    // CHANGE SENSITIVITY

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

    // COUNT SAVED RESULTS

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
