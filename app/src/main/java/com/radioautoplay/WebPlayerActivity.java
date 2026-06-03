package com.radioautoplay;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class WebPlayerActivity extends Activity {

    public static final String ACTION_PLAY_PAGE = "com.radioautoplay.WEB_PLAY_PAGE";
    public static final String ACTION_STOP_PAGE = "com.radioautoplay.WEB_STOP_PAGE";
    public static final String EXTRA_URL = "web_url";
    private static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        releaseWebView();
        super.onDestroy();
    }

    private void handleIntent(Intent intent) {
        if (intent == null || ACTION_STOP_PAGE.equals(intent.getAction())) {
            finish();
            return;
        }
        String url = intent.getStringExtra(EXTRA_URL);
        if (url == null || url.trim().isEmpty()) {
            finish();
            return;
        }
        openPage(url.trim());
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void openPage(String url) {
        if (webView == null) {
            webView = new WebView(getApplicationContext());
            setContentView(webView, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.setAcceptCookie(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cookieManager.setAcceptThirdPartyCookies(webView, true);
            }

            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setDatabaseEnabled(true);
            settings.setLoadsImagesAutomatically(true);
            settings.setJavaScriptCanOpenWindowsAutomatically(true);
            settings.setUseWideViewPort(true);
            settings.setLoadWithOverviewMode(true);
            settings.setMediaPlaybackRequiresUserGesture(false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            }
            settings.setUserAgentString(DESKTOP_USER_AGENT);

            webView.setWebChromeClient(new WebChromeClient());
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String pageUrl) {
                    startPagePlayback(view);
                }
            });
        }

        webView.onResume();
        webView.resumeTimers();
        webView.loadUrl(url);
    }

    private void startPagePlayback(WebView view) {
        String script = "(function(){"
                + "function q(s){return [].slice.call(document.querySelectorAll(s));}"
                + "function playMedia(){q('audio,video').forEach(function(m){try{m.muted=false;m.autoplay=true;m.controls=true;m.play&&m.play();}catch(e){}});}"
                + "function clickOne(e){if(!e)return;try{e.click();}catch(x){}try{e.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));}catch(x){}var p=e.closest&&e.closest('button,a,[role=button],.station_play,.b-play,.button-play');if(p&&p!==e){try{p.click();}catch(x){}}}"
                + "function clickPlay(){clickOne(document.querySelector('#set_radio_button,#b_top_play,#play,.station_play,.b-play,.button-play,[aria-label*=Listen],[title*=Listen],[id*=play],[class*=play]'));}"
                + "clickPlay();"
                + "setTimeout(playMedia,300);"
                + "setTimeout(clickPlay,1200);"
                + "setTimeout(playMedia,1800);"
                + "setTimeout(clickPlay,3500);"
                + "setTimeout(playMedia,4200);"
                + "})();";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            view.evaluateJavascript(script, null);
        } else {
            view.loadUrl("javascript:" + script);
        }
    }

    private void releaseWebView() {
        if (webView == null) return;
        WebView oldView = webView;
        webView = null;
        try {
            oldView.stopLoading();
            oldView.loadUrl("about:blank");
            oldView.onPause();
            oldView.pauseTimers();
            oldView.removeAllViews();
            oldView.destroy();
        } catch (Exception ignored) {
        }
    }
}
