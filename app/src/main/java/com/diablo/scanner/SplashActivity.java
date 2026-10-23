package com.diablo.scanner;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ProgressBar;
import android.widget.TextView;

public class SplashActivity extends Activity {
    private int progress = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView tvLoading;
    private ProgressBar progressBar;

    private final Runnable loading = new Runnable() {
        @Override public void run() {
            if (isFinishing()) return;
            if (progress <= 100) {
                tvLoading.setText("Loading... " + progress + "%");
                progressBar.setProgress(progress);
                long delay = progress < 30 ? 80L : progress < 70 ? 40L : progress < 90 ? 25L : 15L;
                progress++;
                handler.postDelayed(this, delay);
            } else {
                Intent intent = new Intent(SplashActivity.this, MainActivity.class);
                startActivity(intent);
                finish();
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF02040A);
        getWindow().setNavigationBarColor(0xFF02040A);
        setContentView(R.layout.activity_splash);
        tvLoading = findViewById(R.id.tvLoading);
        progressBar = findViewById(R.id.progressBar);
        progressBar.setMax(100);
        handler.post(loading);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(loading);
        super.onDestroy();
    }
}