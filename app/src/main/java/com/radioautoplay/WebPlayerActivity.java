package com.radioautoplay;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.view.WindowManager;
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
    private static final long[] PLAY_ATTEMPT_DELAYS_MS =
            new long[]{0L, 500L, 1200L, 2500L, 4500L, 7000L, 10000L, 14000L};

    private final Handler handler = new Handler(Looper.getMainLooper());
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
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

            webView = new WebView(this);
            webView.setFocusable(true);
            webView.setFocusableInTouchMode(true);
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
                public void onPageCommitVisible(WebView view, String pageUrl) {
                    schedulePlaybackAttempts(view);
                }

                @Override
                public void onPageFinished(WebView view, String pageUrl) {
                    schedulePlaybackAttempts(view);
                }
            });
        }

        handler.removeCallbacksAndMessages(null);
        webView.onResume();
        webView.resumeTimers();
        webView.requestFocus();
        webView.requestFocusFromTouch();
        webView.loadUrl(url);
    }

    private void schedulePlaybackAttempts(WebView view) {
        for (long delay : PLAY_ATTEMPT_DELAYS_MS) {
            handler.postDelayed(() -> startPagePlayback(view), delay);
        }
    }

    private void startPagePlayback(WebView view) {
        if (view == null || view != webView) return;
        view.requestFocus();
        view.requestFocusFromTouch();
        String script = "(function(){"
                + "var volume=0.86;"
                + "function q(s){try{return [].slice.call(document.querySelectorAll(s));}catch(e){return [];}}"
                + "function text(e){if(!e)return '';var p=e.parentElement;return ((e.id||'')+' '+(e.className&&e.className.baseVal?e.className.baseVal:e.className||'')+' '+(e.getAttribute('aria-label')||'')+' '+(e.getAttribute('title')||'')+' '+(e.innerText||'')+' '+(p?p.id+' '+p.className+' '+(p.getAttribute('aria-label')||'')+' '+(p.getAttribute('title')||''):'')).toLowerCase();}"
                + "function visible(e){try{var r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden';}catch(x){return true;}}"
                + "function score(e){var t=text(e),n=0;if(!visible(e))n-=5;if(/close|share|next|prev|previous|download|appstore|googleplay/.test(t))n-=20;if(/play|listen|radio|station|live|button-play|set_radio_button|b_top_play/.test(t))n+=10;if(e.id==='play'||e.id==='set_radio_button'||e.id==='b_top_play')n+=20;if((e.tagName||'').toLowerCase()==='svg')n+=3;return n;}"
                + "function best(){var a=q('#play,svg#play,.button-play,svg.button-play,#set_radio_button,#b_top_play,.station_play,.b-play,button,a,[role=button],[id*=play],[class*=play],[aria-label],[title]');a=a.sort(function(x,y){return score(y)-score(x);});return a.length&&score(a[0])>0?a[0]:null;}"
                + "function stream(e){var a=['data-stream','data-src','data-url','stream','src','href'];for(var i=0;e&&i<a.length;i++){var v=e.getAttribute&&e.getAttribute(a[i]);if(v&&/^https?:/.test(v))return v;}var s=e&&e.querySelector&&e.querySelector('source[src],audio[src],video[src]');return s&&(s.src||s.getAttribute('src'))||'';}"
                + "function events(e){if(!e)return false;var list=[e,e.closest&&e.closest('button,a,[role=button],.button-play,.station_play,.b-play'),e.parentElement,e.parentElement&&e.parentElement.parentElement];var done=false;list.forEach(function(x){if(!x||done)return;try{x.focus&&x.focus();x.scrollIntoView&&x.scrollIntoView({block:'center',inline:'center'});var r=x.getBoundingClientRect(),cx=r.left+r.width/2,cy=r.top+r.height/2;if(window.PointerEvent){['pointerover','pointerenter','pointerdown','pointerup'].forEach(function(t){x.dispatchEvent(new PointerEvent(t,{bubbles:true,cancelable:true,view:window,clientX:cx,clientY:cy,pointerId:1,pointerType:'mouse',isPrimary:true}));});}['mouseover','mousemove','mousedown','mouseup','click'].forEach(function(t){x.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,view:window,clientX:cx,clientY:cy}));});x.click&&x.click();done=true;}catch(err){}});return done;}"
                + "function playMedia(){var ok=false;q('audio,video').forEach(function(m){try{m.muted=false;m.autoplay=true;m.controls=true;m.volume=volume;if(m.paused&&m.play){m.play();}ok=true;}catch(e){}});return ok;}"
                + "function owned(src){if(!src)return;var a=document.getElementById('radioautoplay_audio');if(!a){a=document.createElement('audio');a.id='radioautoplay_audio';a.controls=true;a.autoplay=true;a.style.position='fixed';a.style.left='8px';a.style.bottom='8px';a.style.zIndex='2147483647';document.body.appendChild(a);}if(a.src!==src)a.src=src;try{a.muted=false;a.volume=volume;a.play&&a.play();}catch(e){}}"
                + "var e=best(),src=stream(e);events(e);setTimeout(playMedia,200);setTimeout(function(){owned(src);playMedia();},900);setTimeout(function(){if(!playMedia())events(best());},1700);return true;"
                + "})();";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            view.evaluateJavascript(script, null);
        } else {
            view.loadUrl("javascript:" + script);
        }
    }

    private void releaseWebView() {
        handler.removeCallbacksAndMessages(null);
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
