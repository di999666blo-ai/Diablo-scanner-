package com.diablo.scanner;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
public class SplashActivity extends Activity {
 private boolean dashboardOpened=false;
 @Override protected void onCreate(Bundle savedInstanceState){
  super.onCreate(savedInstanceState);
  getWindow().setStatusBarColor(Color.rgb(2,4,10));
  getWindow().setNavigationBarColor(Color.rgb(2,4,10));
  getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
  WebView launcher=new WebView(this); launcher.setBackgroundColor(Color.BLACK);
  WebSettings settings=launcher.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(false);
  launcher.addJavascriptInterface(new SplashBridge(),"DiabloSplash"); launcher.setWebViewClient(new WebViewClient());
  setContentView(launcher); launcher.loadUrl("file:///android_asset/launcher.html");
 }
 private final class SplashBridge {
  @JavascriptInterface public void openDashboard(){runOnUiThread(()->{
   if(dashboardOpened||isFinishing())return; dashboardOpened=true;
   startActivity(new Intent(SplashActivity.this,MainActivity.class)); finish();
   overridePendingTransition(android.R.anim.fade_in,android.R.anim.fade_out);
  });}
 }
}