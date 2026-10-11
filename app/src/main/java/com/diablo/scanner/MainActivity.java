package com.diablo.scanner;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private LinearLayout root;
    private TextView signalText;
    private TextView statusText;

    private int red = Color.rgb(255, 35, 55);
    private int background = Color.rgb(12, 12, 15);
    private int panel = Color.rgb(28, 28, 34);
    private int white = Color.WHITE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(Color.BLACK);

        buildDashboard();
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
        logo.setTextSize(64);
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
        subtitle.setText("D EDITION  •  LOCAL CHART ANALYSIS");
        subtitle.setTextSize(12);
        subtitle.setTextColor(red);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 4, 0, 24);
        root.addView(subtitle);

        addSectionTitle("CURRENT SIGNAL");

        signalText = new TextView(this);
        signalText.setText("WAIT");
        signalText.setTextSize(38);
        signalText.setTextColor(Color.YELLOW);
        signalText.setGravity(Gravity.CENTER);
        signalText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        signalText.setPadding(0, 20, 0, 8);
        root.addView(signalText);

        statusText = new TextView(this);
        statusText.setText(
            "No chart analysed yet.\\nCapture a chart to begin."
        );
        statusText.setTextSize(14);
        statusText.setTextColor(white);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(8, 8, 8, 20);
        root.addView(statusText);

        addButton("ANALYSE CHART", v ->
            Toast.makeText(this,
                "Chart analysis will be connected next.",
                Toast.LENGTH_SHORT).show()
        );

        addButton("SET UP FLOATING D", v ->
            Toast.makeText(this,
                "Floating bubble setup is coming next.",
                Toast.LENGTH_SHORT).show()
        );

        addButton("SIGNAL HISTORY", v ->
            Toast.makeText(this,
                "Signal history will be implemented next.",
                Toast.LENGTH_SHORT).show()
        );

        addButton("SETTINGS", v ->
            Toast.makeText(this,
                "DIABLO settings will be added next.",
                Toast.LENGTH_SHORT).show()
        );

        TextView warning = new TextView(this);
        warning.setText(
            "TRADING RISK WARNING\\n" +
            "Signals are experimental and may be wrong. " +
            "Trading involves financial risk. No profits are guaranteed."
        );
        warning.setTextColor(Color.LTGRAY);
        warning.setTextSize(12);
        warning.setGravity(Gravity.CENTER);
        warning.setPadding(8, 28, 8, 8);
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

    private void addButton(String text, View.OnClickListener listener) {
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
                58
            );

        params.setMargins(0, 8, 0, 8);
        root.addView(button, params);
        button.setOnClickListener(listener);
    }
}
