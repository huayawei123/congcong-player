package com.congcong.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.JavascriptInterface;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import android.util.Base64;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 匆匆影视安卓播放器 —— 原生 WebView 壳。
 *
 * 双模式：
 *  - direct（直连）：加载内置 assets/app.html，通过 JS Bridge 用原生 HTTP 直连
 *    苹果 CMS V10 采集站，播放交给外部播放器，手机完全独立可用，无需电脑。
 *  - server（服务）：加载桌面服务的局域网/公网地址，自动注入密码登录，
 *    复用完整站点功能（聚合搜索、豆瓣推荐、OTT 首页）。
 *
 * 不依赖 Gradle / 第三方库；仅用 Android SDK + JDK 手工构建（见 build_apk.py）。
 */
public class MainActivity extends Activity {

    private static final String PREFS = "congcong_android";
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36";

    /**
     * 内置采集接口（苹果 CMS V10 / api.php/provide/vod）。
     * 2026-11-09 从 .env 的 TVBox 订阅中解析并逐个实测：
     * 均能通过 ?ac=videolist&wd= / &ids= 返回 list 且带可播放的 vod_play_url。
     * 第三方接口可能失效，设置里提供「测活全部接口」与「恢复内置默认源」。
     */
    private static final String DEFAULT_SOURCES =
            "["
            + "{\"name\":\"量子资源\",\"url\":\"https://cj.lziapi.com/api.php/provide/vod/\"},"
            + "{\"name\":\"暴风资源\",\"url\":\"https://bfzyapi.com/api.php/provide/vod/\"},"
            + "{\"name\":\"极速资源\",\"url\":\"https://jszyapi.com/api.php/provide/vod/\"},"
            + "{\"name\":\"360资源\",\"url\":\"https://360zy.com/api.php/provide/vod\"},"
            + "{\"name\":\"百度资源\",\"url\":\"https://api.apibdzy.com/api.php/provide/vod\"},"
            + "{\"name\":\"非凡资源\",\"url\":\"http://ffzy5.tv/api.php/provide/vod\"},"
            + "{\"name\":\"电影天堂\",\"url\":\"http://caiji.dyttzyapi.com/api.php/provide/vod\"},"
            + "{\"name\":\"新浪资源\",\"url\":\"https://api.xinlangapi.com/xinlangapi.php/provide/vod\"}"
            + "]";

    private WebView web;
    private ProgressBar progress;
    private TextView gear;
    private SharedPreferences prefs;
    private ExecutorService pool;
    private SSLContext permissiveSsl;

    private String mode = "direct";
    private String serverUrl = "";
    private String password = "123456";
    private String sourcesJson = DEFAULT_SOURCES;
    private long lastBack = 0L;
    /** 应用内播放器是否打开；由 JS Bridge 更新，返回键优先关闭播放器 */
    private volatile boolean playerOpen = false;
    /* JS 侧是否还有「上一页」可退（详情弹层 / 搜索结果 / 全库分区 / 非首页分类）。
       单页应用没有 history，WebView 的 canGoBack() 退的是文档本身，退完就是白页，
       所以返回键必须先问 JS。 */
    private volatile boolean canBackInApp = false;
    /* 播放器是否处于「强制横屏」。
       用途：横屏播放时按返回键，先退出横屏回到竖屏继续播，再按一次才关播放器。
       这符合国内主流视频 App 的习惯（横屏按返回=退出全屏，不是退出播放）。 */
    private volatile boolean playerLandscape = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#0b101a"));
        getWindow().setNavigationBarColor(Color.parseColor("#0b101a"));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        pool = Executors.newFixedThreadPool(12);
        permissiveSsl = buildPermissiveSsl();

        mode = prefs.getString("mode", "direct");
        serverUrl = prefs.getString("serverUrl", "");
        password = prefs.getString("password", "123456");
        sourcesJson = prefs.getString("sources", DEFAULT_SOURCES);
        // 一次性迁移：建安资源整站返回的是爱奇艺/优酷/腾讯等正版平台网页链接，
        // 不是视频流，在应用内永远播不了。老用户升级后 prefs 里还留着它，
        // 这里精确替换掉那一条，其余自定义源原样保留。
        if (sourcesJson.contains("154.219.117.232")) {
            String migrated = sourcesJson.replaceAll(
                    "\\{\\s*\"name\"\\s*:\\s*\"[^\"]*\"\\s*,\\s*\"url\"\\s*:\\s*\"[^\"]*154\\.219\\.117\\.232[^\"]*\"\\s*\\}",
                    "{\"name\":\"新浪资源\",\"url\":\"https://api.xinlangapi.com/xinlangapi.php/provide/vod\"}");
            if (!migrated.contains("154.219.117.232")) {
                sourcesJson = migrated;
                prefs.edit().putString("sources", sourcesJson).apply();
            }
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0b101a"));

        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 6);
        pp.gravity = Gravity.TOP;
        root.addView(progress, pp);

        gear = new TextView(this);
        gear.setText("\u2699");
        gear.setTextColor(Color.WHITE);
        gear.setTextSize(20);
        gear.setGravity(Gravity.CENTER);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor("#990b101a"));
        gd.setCornerRadius(40);
        gear.setBackground(gd);
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(84, 84);
        gp.gravity = Gravity.TOP | Gravity.RIGHT;
        gp.topMargin = 18;
        gp.rightMargin = 18;
        gear.setOnClickListener(v -> showSettings());
        root.addView(gear, gp);

        setContentView(root);
        configureWebView();
        loadCurrentMode();
        scheduleImmersiveHint();
        // 首屏先让出来，等界面渲染完再在后台跑检测，避免和首页请求抢带宽
        web.postDelayed(this::maybeCheckSources, 4000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从后台回来时补一次到期判断：手机上「开着不动」很常见，只靠 onCreate 会漏
        if (web != null) web.postDelayed(this::maybeCheckSources, 1200);
    }

    // ---------------------------------------------------------------- WebView
    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(UA + " CongcongAndroid/1.0");

        web.addJavascriptInterface(new Bridge(), "Android");

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                progress.setProgress(p);
                progress.setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
            }

            // 页面里的 console.log / 未捕获异常都转到 logcat（adb logcat -s LTVJS），
            // 片库同步这类后台逻辑出问题时，没有它只能靠猜
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i("LTVJS", m.messageLevel() + " " + m.message() + " @" + m.lineNumber());
                return true;
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                String url = req.getUrl().toString();
                // 视频直链不再甩给外部播放器：应用内 <video> 自行解码（HLS / MP4）
                if (!playerOpen && "direct".equals(mode) && (url.startsWith("http://") || url.startsWith("https://"))) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Exception ignored) {
                    }
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                progress.setVisibility(View.GONE);
                if ("server".equals(mode) && url.startsWith("http")) {
                    injectAutoLogin();
                }
            }

            @Override
            public void onReceivedError(WebView v, int errorCode, String description, String failingUrl) {
                if (failingUrl == null || !failingUrl.startsWith("file:")) showErrorPage(description);
            }
        });
    }

    /** 当前屏幕是否横屏。
        以系统实际方向为准，不看 playerLandscape：
        用户可能靠「把手机转过来」（SENSOR 模式）进入横屏，那时 playerLandscape
        仍是 false，若用它判断，按返回会直接关掉播放器而不是先转竖屏。 */
    private boolean isLandscapeNow() {
        return getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
    }

    /** 齿轮按钮的显隐只跟「播放器是否打开」绑定，不跟「是否全屏」绑定。
        原来它跟全屏状态走：竖屏播放时 applyFullscreen(false) 会把齿轮放回来，
        而竖屏播放器仍然占满整屏，于是齿轮正好压在播放器右上角的「投屏」按钮上
        —— 用户截图里那个遮挡就是这么来的。播放器开着的时候，齿轮没有任何意义。 */
    private void syncGearVisibility() {
        if (gear == null) return;
        gear.setVisibility(playerOpen ? View.GONE : View.VISIBLE);
    }

    /** 退出横屏：回竖屏、退出沉浸式。播放器不关。 */
    private void doExitLandscape() {
        /* 用 SENSOR 而不是 UNSPECIFIED / PORTRAIT：
             · PORTRAIT    → 方向钉死，之后怎么转手机都不会再横屏
             · UNSPECIFIED → 跟随「系统自动旋转」开关，用户关掉自动旋转后就回不去横屏
             · SENSOR      → 只听重力传感器，手机横过来就横屏，不受系统开关影响
           这正是「竖屏之后还想再横屏」能成立的关键。 */
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
        /* 竖屏下不再需要沉浸式全屏（状态栏要回来），但播放器仍在前台 */
        applyFullscreen(false);
        syncGearVisibility();      // 播放器还开着 → 齿轮保持隐藏
    }

    private void applyFullscreen(boolean on) {
        View decor = getWindow().getDecorView();
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            if (Build.VERSION.SDK_INT >= 28) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                getWindow().setAttributes(lp);
            }
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN);
            syncGearVisibility();
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            if (Build.VERSION.SDK_INT >= 28) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
                getWindow().setAttributes(lp);
            }
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            syncGearVisibility();
        }
    }

    /**
     * 把 Android 「Viewing full screen / GOT IT」一次性沉浸式引导前置到首页。
     * 该系统提示由 SystemUI 在应用首次隐藏系统栏时弹出，且必须用户点击才消失；
     * 若留到首次播放才触发，会横着挡在播放器画面中央（用户会误以为「没全屏」）。
     * 这里在启动后短暂进入沉浸式全屏并还原，让提示在竖屏首页出现，点一次即终生消失。
     * 仅预热一次，避免已确认过的设备首页反复闪烁全屏。
     */
    private void scheduleImmersiveHint() {
        if (prefs.getBoolean("immersive_hinted", false)) return;
        prefs.edit().putBoolean("immersive_hinted", true).apply();
        web.postDelayed(new Runnable() {
            @Override public void run() {
                applyFullscreen(true);
                web.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (!playerOpen) applyFullscreen(false);
                    }
                }, 2600);
            }
        }, 1000);
    }

    private void loadCurrentMode() {
        if ("direct".equals(mode)) {
            web.loadUrl("file:///android_asset/app.html");
        } else {
            if (serverUrl.trim().isEmpty()) {
                toast("请先填写桌面服务地址");
                showSettings();
                return;
            }
            web.loadUrl(normalizeBase(serverUrl));
        }
    }

    private void injectAutoLogin() {
        // 只尝试一次；失败不循环 reload。登录成功后 sessionStorage 标记保留，reload 后跳过注入。
        String js = "(function(){try{"
                + "if(sessionStorage.getItem('__ltv_auto'))return;"
                + "fetch('/api/status',{credentials:'same-origin'})"
                + ".then(function(r){return r.json()}).then(function(s){"
                + "if(s.verified)return;"
                + "sessionStorage.setItem('__ltv_auto','1');"
                + "return fetch('/api/auth',{method:'POST',"
                + "headers:{'Content-Type':'application/json'},credentials:'same-origin',"
                + "body:JSON.stringify({password:" + JSONObject.quote(password) + "})})"
                + ".then(function(r){if(r.ok){location.reload();}"
                + "else{Android.toast('自动登录失败，请在设置里核对密码');}});"
                + "}).catch(function(){});}catch(e){}})();";
        web.evaluateJavascript(js, null);
    }

    private void showErrorPage(String desc) {
        String html = "<!doctype html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{background:#0b101a;color:#e8ecf4;font-family:sans-serif;"
                + "padding:32px;text-align:center}h2{color:#fff}p{color:#94a0b8;line-height:1.7}"
                + "button{margin-top:24px;background:#7c5cff;color:#fff;border:0;border-radius:24px;"
                + "padding:12px 28px;font-size:15px}</style></head><body>"
                + "<h2>连接失败</h2><p>" + esc(desc) + "</p>"
                + "<p>请确认电脑上的桌面服务已启动，且手机与电脑在同一 WiFi。<br>"
                + "服务地址形如 http://192.168.1.10:8080</p>"
                + "<p style='color:#7c5cff;font-weight:bold'>点右上角 ⚙ 齿轮按钮可修改设置 / 切换直连模式</p>"
                + "</body></html>";
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    // ---------------------------------------------------------------- JS Bridge
    private class Bridge {
        @JavascriptInterface
        public String getConfig() {
            try {
                JSONObject o = new JSONObject();
                o.put("mode", mode);
                o.put("serverUrl", serverUrl);
                o.put("sources", new JSONArray(sourcesJson));
                o.put("version", "1.0");
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void setSources(String json) {
            sourcesJson = json;
            prefs.edit().putString("sources", json).apply();
        }

        @JavascriptInterface
        public void setMode(String m) {
            mode = "server".equals(m) ? "server" : "direct";
            prefs.edit().putString("mode", mode).apply();
            runOnUiThread(() -> loadCurrentMode());
        }

        @JavascriptInterface
        public void openSettings() {
            runOnUiThread(() -> showSettings());
        }

        /** 网页层的「源状态条」直接跳检测弹窗，省掉 设置 → 源检测 两次点击 */
        @JavascriptInterface
        public void openHealth() {
            runOnUiThread(() -> showHealthDialog());
        }

        @JavascriptInterface
        public void toast(String msg) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
        }

        /** 进入/退出应用内全屏播放器时切换横屏（优酷式观看体验） */
        @JavascriptInterface
        public void setLandscape(boolean on) {
            playerLandscape = on;
            runOnUiThread(() -> setRequestedOrientation(on
                    ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED));
        }

        /** 切换横竖屏（播放器里的旋转按钮）。
            不依赖传感器、也不依赖系统自动旋转开关 —— 躺床上不想转手机时也能用。
            必须同步 playerLandscape，否则返回键会走错分支：
            横屏了却记着竖屏 → 按返回直接关播放器；竖屏了却记着横屏 → 按返回又不关。 */
        @JavascriptInterface
        public void toggleOrientation() {
            runOnUiThread(() -> {
                if (isLandscapeNow()) {
                    playerLandscape = false;
                    doExitLandscape();
                } else {
                    playerLandscape = true;
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                    applyFullscreen(true);
                }
            });
        }

        /** 只退横屏、不关播放器：回到竖屏继续播（返回键的第一层）。
            具体动作放在外层 doExitLandscape()，因为 onBackPressed 也要直接调用它，
            而那是外部类的方法，访问不到内部类里的私有实现。 */
        @JavascriptInterface
        public void exitLandscape() {
            playerLandscape = false;
            runOnUiThread(() -> doExitLandscape());
        }

        /** 通知原生层播放器开关状态，使返回键优先关闭播放器而非退出 APP */
        @JavascriptInterface
        public void setPlayerOpen(boolean on) {
            playerOpen = on;
            runOnUiThread(() -> syncGearVisibility());   // 开播放器立刻收起齿轮，关播放器再放回
            /* 播放器关掉时清掉横屏标记：否则下次开播放器前若用户已在竖屏，
               残留的 true 会让第一次返回键误判成「先转竖屏」。 */
            if (!on) playerLandscape = false;
        }

        /** 页面内是否还有上一级（由 JS 在每次切视图时同步过来） */
        @JavascriptInterface
        public void setCanBack(boolean on) {
            canBackInApp = on;
        }

        /** 进入/退出应用内播放器时切换真正的沉浸式全屏 */
        @JavascriptInterface
        public void setFullscreen(boolean on) {
            runOnUiThread(() -> applyFullscreen(on));
        }

        /** 0~100 的系统媒体音量，播放器「上下滑调音量」用 */
        @JavascriptInterface
        public int getVolume() {
            try {
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                return max > 0 ? Math.round(cur * 100f / max) : 0;
            } catch (Exception e) {
                return 0;
            }
        }

        @JavascriptInterface
        public void setVolume(int pct) {
            final int p = Math.max(0, Math.min(100, pct));
            runOnUiThread(() -> {
                try {
                    AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                    int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    int v = Math.round(p * max / 100f);
                    // flags=0：不弹系统音量条，界面里自己画
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
                } catch (Exception ignored) {
                }
            });
        }

        @JavascriptInterface
        public void play(String url, String title) {
            MainActivity.this.play(url, title);
        }

        /** timeoutMs<=0 时用默认 9 秒；搜索 ac=videolist 单页可达几百 KB，会显式传更长超时 */
        @JavascriptInterface
        public void httpGet(String url, String cbId, int timeoutMs) {
            final int tmo = timeoutMs > 0 ? Math.min(timeoutMs, 30000) : 9000;
            pool.execute(() -> {
                boolean ok;
                String result;
                try {
                    result = fetch(url, tmo);
                    ok = true;
                } catch (Exception e) {
                    result = String.valueOf(e.getMessage());
                    ok = false;
                }
                final boolean fok = ok;
                final String fresult = result;
                runOnUiThread(() -> callback(cbId, fok, fresult));
            });
        }

        /**
         * 只读 m3u8 头部用于「路径结构预检」，回调格式为：
         *   第一行 = 跟随重定向后的最终 URL
         *   其余   = 响应体前 maxBytes 字节
         * 目的：识别苹果 CMS 常见的「根相对路径」清单，本机 WebView 原生 HLS 无法解析，
         * 需在播放前改走 MSE，避免白等 12 秒超时。
         */
        /** GET 并解 raw deflate（B 站弹幕接口 list.so 返回的就是这种）。
            放原生做的原因：浏览器端要 DecompressionStream('deflate-raw')，
            那是 Chrome 103+ 才有的，老 WebView 上直接 undefined。
            Java 的 Inflater(true) 从 API 1 就有，稳。 */
        @JavascriptInterface
        public void httpGetD(String url, String cbId, int timeoutMs) {
            final int tmo = timeoutMs > 0 ? Math.min(timeoutMs, 30000) : 10000;
            pool.execute(() -> {
                boolean ok;
                String result;
                try {
                    result = fetchDeflate(url, tmo);
                    ok = true;
                } catch (Exception e) {
                    result = String.valueOf(e.getMessage());
                    ok = false;
                }
                final boolean fok = ok;
                final String fresult = result;
                runOnUiThread(() -> callback(cbId, fok, fresult));
            });
        }

        /** 弹弹play 的请求签名：base64(sha256(AppId + Timestamp + Path + AppSecret))。
            放在原生层做有两个原因：
              ① 页面是 file:// 加载的，不是安全上下文，JS 里 crypto.subtle 是 undefined；
              ② 纯 JS 手写 SHA-256 又多又慢，Java 的 MessageDigest 一行就行。 */
        @JavascriptInterface
        public String ddpSign(String appId, String timestamp, String path, String secret) {
            try {
                String raw = appId + timestamp + path + secret;
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] d = md.digest(raw.getBytes("UTF-8"));
                return Base64.encodeToString(d, Base64.NO_WRAP);
            } catch (Exception e) {
                return "";
            }
        }

        /** 带自定义请求头的 GET，给弹幕 API 用。
            弹弹play 的 v2 接口要求 X-AppId / X-Timestamp / X-Signature 三个头，
            而它不支持 OPTIONS 预检（实测返回 405），所以浏览器端 fetch 带自定义头
            必然被 CORS 拦掉 —— 只能走原生这一层绕过去。 */
        @JavascriptInterface
        public void httpGetH(String url, String cbId, int timeoutMs, String headersJson) {
            final int tmo = timeoutMs > 0 ? Math.min(timeoutMs, 30000) : 9000;
            pool.execute(() -> {
                boolean ok;
                String result;
                try {
                    result = fetchWithHeaders(url, tmo, headersJson);
                    ok = true;
                } catch (Exception e) {
                    result = String.valueOf(e.getMessage());
                    ok = false;
                }
                final boolean fok = ok;
                final String fresult = result;
                runOnUiThread(() -> callback(cbId, fok, fresult));
            });
        }

        @JavascriptInterface
        public void probe(String url, String cbId) {
            pool.execute(() -> {
                boolean ok;
                String result;
                try {
                    result = fetchHead(url, 6000, 4096);
                    ok = true;
                } catch (Exception e) {
                    result = String.valueOf(e.getMessage());
                    ok = false;
                }
                final boolean fok = ok;
                final String fresult = result;
                runOnUiThread(() -> callback(cbId, fok, fresult));
            });
        }

        @JavascriptInterface
        public void syncSources(String subUrl, String cbId) {
            pool.execute(() -> {
                try {
                    String text = fetch(subUrl, 12000);
                    JSONArray out = parseSubscription(text);
                    callback(cbId, true, out.toString());
                } catch (Exception e) {
                    callback(cbId, false, String.valueOf(e.getMessage()));
                }
            });
        }

        @JavascriptInterface
        public void testSource(String url, String cbId) {
            pool.execute(() -> {
                try {
                    String api = normalizeBase(url) + "?ac=videolist&wd="
                            + URLEncoder.encode("测试", "UTF-8") + "&pg=1";
                    String text = fetch(api, 9000);
                    JSONObject j = new JSONObject(text);
                    boolean ok = j.has("list");
                    callback(cbId, ok, ok ? "可用" : "响应中无 list 字段");
                } catch (Exception e) {
                    callback(cbId, false, String.valueOf(e.getMessage()));
                }
            });
        }

        /** 读缓存的检测结果（同步、瞬间返回），网页层用它给源排序、跳过死源 */
        @JavascriptInterface
        public String getSourceHealth() {
            loadHealth();
            return healthJson();
        }

        /** 立即跑一轮全量检测；完成后回调 cbId，payload 与 getSourceHealth 同构 */
        @JavascriptInterface
        public void checkSourcesNow(String cbId) {
            runSourceCheck(cbId);
        }

        /** 保存自动检测配置：{"autoCheck":true,"hours":12} */
        @JavascriptInterface
        public void setHealthConfig(String json) {
            try {
                JSONObject o = new JSONObject(json);
                SharedPreferences.Editor ed = prefs.edit();
                if (o.has("autoCheck")) ed.putBoolean("auto_check", o.optBoolean("autoCheck"));
                if (o.has("hours")) ed.putInt("check_hours", Math.max(1, o.optInt("hours", DEFAULT_CHECK_HOURS)));
                ed.apply();
                runOnUiThread(MainActivity.this::refreshHealthDialog);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 把结果回灌给网页层。
     *
     * 单次 evaluateJavascript 能承载的脚本长度是有上限的（各家 WebView 的实现不同，
     * 鸿蒙 4.0 与部分华为/小米机型的实测阈值明显低于原生 Chromium）。search 走的是
     * ac=videolist，单页 100~500KB，整串塞进一次调用会被截断，网页层 JSON.parse 抛异常，
     * 最终表现为「搜索没有结果，但其它安卓手机正常」。
     *
     * 所以超过 CHUNK 的响应按 48KB 切片，逐片调用 __LTV.chunk 由网页层按序号拼回。
     * 切点避开代理对，避免把 emoji 劈成半个字符。
     */
    private static final int CHUNK = 48 * 1024;

    private void callback(String cbId, boolean ok, String payload) {
        if (payload == null) payload = "";
        int len = payload.length();
        if (len <= CHUNK) {
            String js = "try{window.__LTV&&window.__LTV.callback("
                    + JSONObject.quote(cbId) + "," + (ok ? "true" : "false") + ","
                    + JSONObject.quote(payload) + ")}catch(e){}";
            web.evaluateJavascript(js, null);
            return;
        }
        int total = (len + CHUNK - 1) / CHUNK;
        int from = 0;
        for (int i = 0; i < total; i++) {
            int to = Math.min(len, from + CHUNK);
            if (to < len && Character.isHighSurrogate(payload.charAt(to - 1))) to--;
            String part = payload.substring(from, to);
            from = to;
            String js = "try{window.__LTV&&window.__LTV.chunk("
                    + JSONObject.quote(cbId) + "," + i + "," + total + ","
                    + (ok ? "true" : "false") + "," + JSONObject.quote(part) + ")}catch(e){}";
            web.evaluateJavascript(js, null);
        }
    }

    /** 解析 TVBox 订阅 JSON / 源列表，抽出苹果 CMS V10 接口 */
    private JSONArray parseSubscription(String text) throws Exception {
        JSONObject root = new JSONObject(text);
        JSONArray sites = root.optJSONArray("sites");
        if (sites == null) sites = root.optJSONArray("sources");
        JSONArray out = new JSONArray();
        if (sites == null) return out;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < sites.length(); i++) {
            JSONObject s = sites.optJSONObject(i);
            if (s == null) continue;
            String url = s.optString("api", s.optString("url", ""));
            String name = s.optString("name", s.optString("title", "源" + (i + 1)));
            int type = s.optInt("type", -1);
            if (!url.startsWith("http")) continue;
            if (url.contains(".jar") || url.contains("spider")) continue;
            if (type == 0 || type == 3) continue; // 跳过 Spider / XML 类
            if (!seen.add(url)) continue;
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("url", url);
            out.put(o);
        }
        return out;
    }

    // ------------------------------------------------------- 源检测 / 自动维护
    /**
     * 采集接口定期检测（与桌面端 source-maintenance.ts 同一套口径）：
     *
     *  1) 探活：`?ac=list&pagesize=1`，必须返回 JSON，且同时带 list 与 class/total/page 之一。
     *     只看到 HTTP 200 不算活——不少站点会用 200 返回一个 HTML 首页或「暂不支持」。
     *  2) 可搜索：`?ac=videolist&wd=战&pg=1&pagesize=5`，必须返回 JSON 且含 list。
     *     这一步不能省：实测能连上的源里有相当一部分列表正常、搜索接口是关的，
     *     标成「仅列表」后不再参与搜索，避免每次搜索白等一个超时。
     *
     * 结果按 URL 存 SharedPreferences，进程被杀也不丢；下次启动先读缓存，
     * 到期（默认 12 小时）再在后台重测一轮。
     */
    private static class SourceHealth {
        boolean ok;
        int latencyMs;
        int classes;
        int total;
        boolean searchable;
        String error;
        long checkedAt;

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("ok", ok);
                o.put("latency", latencyMs);
                o.put("classes", classes);
                o.put("total", total);
                o.put("searchable", searchable);
                if (error != null) o.put("error", error);
                o.put("checkedAt", checkedAt);
            } catch (Exception ignored) {
            }
            return o;
        }

        static SourceHealth fromJson(JSONObject o) {
            SourceHealth h = new SourceHealth();
            h.ok = o.optBoolean("ok", false);
            h.latencyMs = o.optInt("latency", 0);
            h.classes = o.optInt("classes", 0);
            h.total = o.optInt("total", 0);
            h.searchable = o.optBoolean("searchable", false);
            h.error = o.has("error") ? o.optString("error") : null;
            h.checkedAt = o.optLong("checkedAt", 0L);
            return h;
        }
    }

    /** 检测用的冒烟关键词：单片名，命中率高，不会把源打疼 */
    private static final String SMOKE_KEYWORD = "战";
    private static final int DEFAULT_CHECK_HOURS = 12;

    private final Map<String, SourceHealth> healthMap = new LinkedHashMap<>();
    private volatile long healthCheckedAt = 0L;
    private volatile boolean healthChecking = false;
    private TextView healthStatusView;
    private LinearLayout healthRowsView;
    private AlertDialog healthDialog;

    private boolean autoCheckEnabled() {
        return prefs.getBoolean("auto_check", true);
    }

    private int checkHours() {
        return Math.max(1, prefs.getInt("check_hours", DEFAULT_CHECK_HOURS));
    }

    private long nextCheckAt() {
        return healthCheckedAt + checkHours() * 3600_000L;
    }

    private void loadHealth() {
        synchronized (healthMap) {
            if (!healthMap.isEmpty()) return;
            try {
                String raw = prefs.getString("health_json", "");
                if (raw == null || raw.isEmpty()) return;
                JSONObject root = new JSONObject(raw);
                healthCheckedAt = root.optLong("at", 0L);
                JSONObject items = root.optJSONObject("items");
                if (items == null) return;
                java.util.Iterator<String> it = items.keys();
                while (it.hasNext()) {
                    String url = it.next();
                    JSONObject o = items.optJSONObject(url);
                    if (o != null) healthMap.put(url, SourceHealth.fromJson(o));
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void saveHealth() {
        try {
            JSONObject items = new JSONObject();
            synchronized (healthMap) {
                for (Map.Entry<String, SourceHealth> e : healthMap.entrySet()) {
                    items.put(e.getKey(), e.getValue().toJson());
                }
            }
            JSONObject root = new JSONObject();
            root.put("at", healthCheckedAt);
            root.put("items", items);
            prefs.edit().putString("health_json", root.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** 单个源两步检测；任何异常都收敛成 ok=false，不向外抛 */
    private SourceHealth probeSource(String url, int timeoutMs) {
        SourceHealth h = new SourceHealth();
        h.checkedAt = System.currentTimeMillis();
        String base = normalizeBase(url);
        long t0 = System.currentTimeMillis();
        try {
            String text = fetch(base + "?ac=list&pagesize=1", timeoutMs);
            h.latencyMs = (int) (System.currentTimeMillis() - t0);
            JSONObject j = new JSONObject(text);
            boolean hasList = j.has("list");
            boolean hasMeta = j.has("class") || j.has("total") || j.has("page");
            if (!hasList || !hasMeta) {
                h.error = "响应缺少 list / class 字段";
                return h;
            }
            h.ok = true;
            JSONArray cls = j.optJSONArray("class");
            h.classes = cls != null ? cls.length() : j.optInt("class_count", 0);
            h.total = j.optInt("total", 0);
            try {
                String s = fetch(base + "?ac=videolist&wd="
                        + URLEncoder.encode(SMOKE_KEYWORD, "UTF-8") + "&pg=1&pagesize=5", timeoutMs);
                String t = s == null ? "" : s.trim();
                if (!t.startsWith("{")) {
                    h.searchable = false;
                } else {
                    h.searchable = new JSONObject(t).has("list");
                }
            } catch (Exception e) {
                h.searchable = false;
            }
            return h;
        } catch (Exception e) {
            h.latencyMs = (int) (System.currentTimeMillis() - t0);
            h.error = String.valueOf(e.getMessage());
            return h;
        }
    }

    /**
     * 后台并发检测全部接口，完成后回调 JS（cbId 可空）并刷新原生弹窗。
     * 并发度固定 4：手机网络下再高只会互相抢带宽，实测反而更慢。
     */
    private void runSourceCheck(final String cbId) {
        if (healthChecking) {
            if (cbId != null) callback(cbId, false, "正在检测中，请稍候");
            return;
        }
        healthChecking = true;
        final List<JSONObject> srcs = parseSourcesJson(sourcesJson);
        final int timeout = 7000;
        final int threads = Math.min(4, Math.max(1, srcs.size()));
        runOnUiThread(this::refreshHealthDialog);
        pool.execute(() -> {
            ExecutorService es = Executors.newFixedThreadPool(threads);
            final CountDownLatch latch = new CountDownLatch(srcs.size());
            final Map<String, SourceHealth> fresh = new LinkedHashMap<>();
            for (final JSONObject s : srcs) {
                final String url = s.optString("url", "");
                if (!url.startsWith("http")) {
                    latch.countDown();
                    continue;
                }
                es.execute(() -> {
                    SourceHealth h = probeSource(url, timeout);
                    synchronized (fresh) {
                        fresh.put(url, h);
                    }
                    latch.countDown();
                    runOnUiThread(MainActivity.this::refreshHealthDialog);
                });
            }
            try {
                latch.await();
            } catch (InterruptedException ignored) {
            }
            es.shutdown();
            synchronized (healthMap) {
                healthMap.clear();
                healthMap.putAll(fresh);
            }
            healthCheckedAt = System.currentTimeMillis();
            saveHealth();
            healthChecking = false;
            final String payload = healthJson();
            runOnUiThread(() -> {
                if (cbId != null) callback(cbId, true, payload);
                refreshHealthDialog();
                notifyHealthChanged(payload);
            });
        });
    }

    /** 启动 / 回到前台时按周期判断，到期才真正跑一轮 */
    private void maybeCheckSources() {
        loadHealth();
        if (!autoCheckEnabled()) return;
        if (System.currentTimeMillis() >= nextCheckAt()) runSourceCheck(null);
    }

    /** 把检测结果推给网页层，让首页提示和搜索排序跟着更新 */
    private void notifyHealthChanged(String payload) {
        if (web == null) return;
        web.evaluateJavascript("try{window.__LTV&&window.__LTV.health("
                + JSONObject.quote(payload) + ")}catch(e){}", null);
    }

    /** 完整状态：配置 + 每个源的结果，字段与桌面端 /api/sources/registry 对齐 */
    private String healthJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("at", healthCheckedAt);
            o.put("checking", healthChecking);
            o.put("autoCheck", autoCheckEnabled());
            o.put("hours", checkHours());
            o.put("nextAt", healthCheckedAt == 0 ? 0 : nextCheckAt());
            JSONArray arr = new JSONArray();
            synchronized (healthMap) {
                for (JSONObject s : parseSourcesJson(sourcesJson)) {
                    String url = s.optString("url", "");
                    JSONObject row = new JSONObject();
                    row.put("name", s.optString("name", url));
                    row.put("url", url);
                    SourceHealth h = healthMap.get(url);
                    if (h == null) {
                        row.put("checked", false);
                    } else {
                        row.put("checked", true);
                        row.put("ok", h.ok);
                        row.put("latency", h.latencyMs);
                        row.put("classes", h.classes);
                        row.put("total", h.total);
                        row.put("searchable", h.searchable);
                        row.put("checkedAt", h.checkedAt);
                        if (h.error != null) row.put("error", h.error);
                    }
                    arr.put(row);
                }
            }
            o.put("items", arr);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private String fmtAgo(long ms) {
        if (ms <= 0) return "尚未检测";
        if (ms < 60_000) return "刚刚";
        long min = ms / 60_000;
        if (min < 60) return min + " 分钟前";
        long hr = min / 60;
        if (hr < 24) return hr + " 小时前";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(healthCheckedAt));
    }

    private String fmtUntil(long target) {
        long d = target - System.currentTimeMillis();
        if (d <= 0) return "即将开始";
        long min = d / 60_000;
        if (min < 60) return min + " 分钟后";
        return (min / 60) + " 小时后";
    }

    /** 刷新已打开的检测弹窗；未打开时直接返回 */
    private void refreshHealthDialog() {
        if (healthStatusView == null || healthRowsView == null) return;
        int total = countSources();
        int ok = 0;
        int searchable = 0;
        synchronized (healthMap) {
            for (SourceHealth h : healthMap.values()) {
                if (!h.ok) continue;
                ok += 1;
                if (h.searchable) searchable += 1;
            }
        }
        String next = autoCheckEnabled()
                ? (healthCheckedAt == 0 ? "启动后自动跑一轮" : fmtUntil(nextCheckAt()))
                : "已关闭";
        healthStatusView.setText((healthChecking ? "⏳ 正在检测…\n" : "")
                + "上次检测：" + fmtAgo(healthCheckedAt) + "\n"
                + "可用 " + ok + " / " + total + " 个接口（其中 " + searchable + " 个能搜索）\n"
                + "下次检测：" + next);

        healthRowsView.removeAllViews();
        List<JSONObject> srcs = parseSourcesJson(sourcesJson);
        for (JSONObject s : srcs) {
            String url = s.optString("url", "");
            String name = s.optString("name", url);
            SourceHealth h;
            synchronized (healthMap) {
                h = healthMap.get(url);
            }
            TextView row = new TextView(this);
            row.setTextSize(12);
            row.setPadding(0, 10, 0, 10);
            String text;
            String color;
            if (h == null) {
                text = name + "\n等待检测";
                color = "#6b7690";
            } else if (!h.ok) {
                text = name + "\n不可用 · " + (h.error == null ? "连接失败" : h.error);
                color = "#f87171";
            } else if (!h.searchable) {
                text = name + "\n仅列表 · " + h.latencyMs + "ms · " + h.classes + " 类";
                color = "#fbbf24";
            } else {
                text = name + "\n可用 · " + h.latencyMs + "ms · " + h.classes + " 类 · "
                        + (h.total > 0 ? h.total + " 条" : "有数据");
                color = "#34d399";
            }
            row.setText(text);
            row.setTextColor(Color.parseColor(color));
            healthRowsView.addView(row);
        }
    }

    private void showHealthDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(36, 20, 36, 12);

        healthStatusView = new TextView(this);
        healthStatusView.setTextColor(Color.parseColor("#aeb8cc"));
        healthStatusView.setTextSize(13);
        healthStatusView.setLineSpacing(6f, 1f);
        box.addView(healthStatusView);

        final CheckBox cb = new CheckBox(this);
        cb.setText("自动定期检测（每 " + checkHours() + " 小时）");
        cb.setTextColor(Color.WHITE);
        cb.setChecked(autoCheckEnabled());
        cb.setOnCheckedChangeListener((v, checked) -> {
            prefs.edit().putBoolean("auto_check", checked).apply();
            refreshHealthDialog();
        });
        box.addView(cb);

        TextView btnNow = actionButton("立即检测全部接口");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 16;
        lp.bottomMargin = 12;
        box.addView(btnNow, lp);
        btnNow.setOnClickListener(v -> {
            if (healthChecking) {
                toast("正在检测中…");
                return;
            }
            toast("已开始检测 " + countSources() + " 个接口");
            runSourceCheck(null);
        });

        healthRowsView = new LinearLayout(this);
        healthRowsView.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(healthRowsView);
        box.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        healthDialog = new AlertDialog.Builder(this)
                .setTitle("源检测与自动维护")
                .setView(box)
                .setNegativeButton("关闭", null)
                .create();
        healthDialog.setOnDismissListener(d -> {
            healthStatusView = null;
            healthRowsView = null;
            healthDialog = null;
        });
        healthDialog.show();
        loadHealth();
        refreshHealthDialog();
    }

    // ---------------------------------------------------------------- 播放
    private void play(String url, String title) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse(url), "video/*");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.putExtra("title", title);
            i.putExtra("return_result", false);
            startActivity(Intent.createChooser(i, "选择播放器：" + title));
        } catch (Exception e) {
            toast("未找到播放器，请安装 MX Player / VLC / 系统视频");
        }
    }

    private static boolean isVideoUrl(String u) {
        String low = u.toLowerCase();
        return low.contains(".m3u8") || low.contains(".mp4") || low.contains(".mkv")
                || low.contains(".flv") || low.endsWith(".ts");
    }

    // ---------------------------------------------------------------- 设置
    private void showSettings() {
        loadHealth();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(36, 24, 36, 12);

        TextView hint = new TextView(this);
        hint.setText("匆匆影视 · 设置\n\n"
                + "直连模式：手机独立看片，需先添加采集接口（或从 TVBox 订阅导入）。\n"
                + "服务模式：连接电脑上的桌面服务，需同一 WiFi，"
                + "地址形如 http://192.168.1.10:8080。");
        hint.setTextColor(Color.parseColor("#aeb8cc"));
        hint.setTextSize(13);
        hint.setPadding(0, 0, 0, 20);
        box.addView(hint);

        TextView modeLabel = new TextView(this);
        modeLabel.setText("运行模式");
        modeLabel.setTextColor(Color.WHITE);
        modeLabel.setTextSize(15);
        box.addView(modeLabel);

        RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rbDirect = new RadioButton(this);
        rbDirect.setText("直连（独立）");
        rbDirect.setTextColor(Color.WHITE);
        rbDirect.setId(1);
        RadioButton rbServer = new RadioButton(this);
        rbServer.setText("连接服务");
        rbServer.setTextColor(Color.WHITE);
        rbServer.setId(2);
        rg.addView(rbDirect);
        rg.addView(rbServer);
        rg.check("server".equals(mode) ? 2 : 1);
        box.addView(rg);

        final EditText etServer = makeEdit(serverUrl, "服务地址 http://192.168.1.10:8080");
        final EditText etPwd = makeEdit(password, "访问密码（默认 123456）");
        etPwd.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(labeled("桌面服务地址（服务模式）", etServer));
        box.addView(labeled("访问密码", etPwd));

        TextView srcHint = new TextView(this);
        srcHint.setText("当前采集接口：" + countSources() + " 个");
        srcHint.setTextColor(Color.parseColor("#aeb8cc"));
        srcHint.setTextSize(13);
        srcHint.setPadding(0, 12, 0, 8);
        box.addView(srcHint);

        TextView btnSources = actionButton("源管理 / 从订阅导入");
        btnSources.setOnClickListener(v -> showSourcesDialog());
        box.addView(btnSources);

        TextView srcStat = new TextView(this);
        srcStat.setText("自动检测：每 " + checkHours() + " 小时一次 · "
                + (healthCheckedAt == 0 ? "尚未检测" : "上次 " + fmtAgo(healthCheckedAt)));
        srcStat.setTextColor(Color.parseColor("#6b7690"));
        srcStat.setTextSize(12);
        srcStat.setPadding(0, 10, 0, 10);
        box.addView(srcStat);

        TextView btnHealth = actionButton("源检测 / 自动维护");
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = 10;
        box.addView(btnHealth, hp);
        btnHealth.setOnClickListener(v -> showHealthDialog());

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("匆匆影视 设置")
                .setView(sv)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存并重载", (d, w) -> {
                    mode = rg.getCheckedRadioButtonId() == 2 ? "server" : "direct";
                    serverUrl = etServer.getText().toString().trim();
                    password = etPwd.getText().toString().trim();
                    if (password.isEmpty()) password = "123456";
                    prefs.edit()
                            .putString("mode", mode)
                            .putString("serverUrl", serverUrl)
                            .putString("password", password)
                            .apply();
                    loadCurrentMode();
                })
                .show();
    }

    private void showSourcesDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(36, 20, 36, 12);

        TextView t1 = new TextView(this);
        t1.setText("每行一个接口：名称|接口地址\n"
                + "例如：量子资源|https://xxx.com/api.php/provide/vod");
        t1.setTextColor(Color.parseColor("#aeb8cc"));
        t1.setTextSize(13);
        box.addView(t1);

        final EditText etList = new EditText(this);
        etList.setText(sourcesToText());
        etList.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etList.setMinLines(6);
        etList.setGravity(Gravity.TOP);
        etList.setTextColor(Color.WHITE);
        etList.setBackgroundColor(Color.parseColor("#141c2c"));
        box.addView(etList);

        final EditText etSub = makeEdit("", "TVBox 配置订阅 URL（可选）");
        box.addView(labeled("从订阅批量导入", etSub));

        TextView btnImport = actionButton("导入订阅");
        btnImport.setOnClickListener(v -> {
            String sub = etSub.getText().toString().trim();
            if (!sub.startsWith("http")) {
                toast("请填写 http(s) 订阅地址");
                return;
            }
            toast("正在解析订阅…");
            pool.execute(() -> {
                try {
                    JSONArray arr = parseSubscription(fetch(sub, 12000));
                    JSONArray merged = mergeSources(parseSourcesJson(sourcesJson), arr);
                    final String json = merged.toString();
                    runOnUiThread(() -> {
                        sourcesJson = json;
                        prefs.edit().putString("sources", json).apply();
                        etList.setText(sourcesToText());
                        toast("导入完成，共 " + merged.length() + " 个接口");
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> toast("导入失败：" + e.getMessage()));
                }
            });
        });
        box.addView(btnImport);

        TextView btnTest = actionButton("测活全部接口");
        btnTest.setOnClickListener(v -> {
            List<JSONObject> list = parseSourcesJson(sourcesJson);
            toast("正在测活 " + list.size() + " 个接口…");
            for (JSONObject o : list) {
                final String name = o.optString("name", "?");
                final String url = o.optString("url", "");
                pool.execute(() -> {
                    boolean ok;
                    try {
                        String api = normalizeBase(url) + "?ac=videolist&wd="
                                + URLEncoder.encode("测试", "UTF-8") + "&pg=1";
                        new JSONObject(fetch(api, 9000)).has("list");
                        ok = true;
                    } catch (Exception e) {
                        ok = false;
                    }
                    final boolean fok = ok;
                    runOnUiThread(() -> toast(name + (fok ? " 可用" : " 不可用")));
                });
            }
        });
        box.addView(btnTest);

        TextView btnReset = actionButton("恢复内置默认源");
        btnReset.setOnClickListener(v -> {
            sourcesJson = DEFAULT_SOURCES;
            prefs.edit().putString("sources", DEFAULT_SOURCES).apply();
            etList.setText(sourcesToText());
            // 内置源可能整批换过，旧的检测记录不再可信，清掉重测
            synchronized (healthMap) {
                healthMap.clear();
            }
            healthCheckedAt = 0L;
            saveHealth();
            runSourceCheck(null);
            toast("已恢复 " + countSources() + " 个内置接口，正在重新检测");
        });
        box.addView(btnReset);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("采集接口管理")
                .setView(sv)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> {
                    sourcesJson = textToSources(etList.getText().toString());
                    prefs.edit().putString("sources", sourcesJson).apply();
                    toast("已保存 " + countSources() + " 个接口");
                    // 新加的接口还没有检测记录，立刻补一轮，否则搜索排序里它们是「未测」垫底的
                    if (countSources() > 0) runSourceCheck(null);
                    if ("direct".equals(mode)) web.reload();
                })
                .show();
    }

    private int countSources() {
        return parseSourcesJson(sourcesJson).size();
    }

    private String sourcesToText() {
        StringBuilder sb = new StringBuilder();
        for (JSONObject o : parseSourcesJson(sourcesJson)) {
            sb.append(o.optString("name", "源")).append('|')
                    .append(o.optString("url", "")).append('\n');
        }
        return sb.toString().trim();
    }

    private static String textToSources(String text) {
        JSONArray arr = new JSONArray();
        for (String line : text.split("\\n")) {
            line = line.trim();
            if (line.isEmpty() || !line.contains("|")) continue;
            int i = line.indexOf('|');
            String name = line.substring(0, i).trim();
            String url = line.substring(i + 1).trim();
            if (!url.startsWith("http")) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("name", name.isEmpty() ? "源" : name);
                o.put("url", url);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        return arr.toString();
    }

    private static List<JSONObject> parseSourcesJson(String json) {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) out.add(o);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static JSONArray mergeSources(List<JSONObject> a, JSONArray b) {
        JSONArray out = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (JSONObject o : a) {
            String u = o.optString("url", "");
            if (u.startsWith("http") && seen.add(u)) out.put(o);
        }
        for (int i = 0; i < b.length(); i++) {
            JSONObject o = b.optJSONObject(i);
            if (o == null) continue;
            String u = o.optString("url", "");
            if (u.startsWith("http") && seen.add(u)) out.put(o);
        }
        return out;
    }

    // ---------------------------------------------------------------- 小工具
    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    private EditText makeEdit(String value, String hint) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.parseColor("#6b7690"));
        e.setSingleLine(true);
        return e;
    }

    private LinearLayout labeled(String label, EditText edit) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.parseColor("#aeb8cc"));
        t.setTextSize(12);
        t.setPadding(0, 14, 0, 0);
        l.addView(t);
        l.addView(edit);
        return l;
    }

    private TextView actionButton(String text) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, 20, 0, 20);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor("#7c5cff"));
        gd.setCornerRadius(24);
        b.setBackground(gd);
        return b;
    }

    private static String normalizeBase(String u) {
        u = u.trim().replaceAll("/+$", "");
        return u.endsWith("/") ? u : u + "/";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------------------------------------------------------------- HTTP
    /**
     * 原生 HTTP GET。仅用于直连模式的 JS Bridge 与订阅解析。
     * 免费采集站常使用自签名 / 域名不匹配证书，这里按连接放宽 TLS 校验；
     * WebView 自身的网络栈不受影响，仍走系统默认严格校验。
     */
    private String fetch(String urlStr, int timeoutMs) throws Exception {
        URL u = new URL(urlStr);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        if (c instanceof HttpsURLConnection && permissiveSsl != null) {
            ((HttpsURLConnection) c).setSSLSocketFactory(permissiveSsl.getSocketFactory());
            ((HttpsURLConnection) c).setHostnameVerifier((hostname, session) -> true);
        }
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "application/json,text/plain,*/*");
        c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        /* 苹果 CMS 的 ac=videolist 单页 80~500KB，全是重复度极高的 JSON 文本，
           gzip 能压到 1/4（实测 93KB→23KB、83KB→23KB）。不带这个头服务端就
           明文吐，移动网下这一条就是搜索慢的主要原因。 */
        c.setRequestProperty("Accept-Encoding", "gzip");
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) throw new Exception("HTTP " + code + "（无响应体）");
        String enc = c.getContentEncoding();
        if (enc != null && enc.toLowerCase().contains("gzip")) {
            in = new GZIPInputStream(in);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > 2 * 1024 * 1024) break; // 限制 2MB，避免 Binder 回调过大
            bos.write(buf, 0, n);
        }
        in.close();
        c.disconnect();
        if (code >= 400) throw new Exception("HTTP " + code);
        return new String(bos.toByteArray(), "UTF-8");
    }

    /**
     * 只读取响应体前 maxBytes 字节，并把「跟随重定向后的最终 URL」放在第一行返回。
     * 用于 m3u8 清单结构预检：相对路径必须基于最终 URL 解析，否则会误判。
     */
    /** 带请求头的 GET。状态码 >=400 时把状态码和响应体前 120 字一起抛出去，
        JS 侧才能区分「没凭据(403)」和「没这个资源(404)」。 */
    private String fetchWithHeaders(String urlStr, int timeoutMs, String headersJson) throws Exception {
        URL u = new URL(urlStr);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        if (c instanceof HttpsURLConnection && permissiveSsl != null) {
            ((HttpsURLConnection) c).setSSLSocketFactory(permissiveSsl.getSocketFactory());
            ((HttpsURLConnection) c).setHostnameVerifier((hostname, session) -> true);
        }
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "application/json,text/plain,*/*");
        c.setRequestProperty("Accept-Encoding", "gzip");
        if (headersJson != null && headersJson.length() > 2) {
            try {
                JSONObject h = new JSONObject(headersJson);
                java.util.Iterator<String> it = h.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    c.setRequestProperty(k, h.optString(k, ""));
                }
            } catch (Exception ignore) { /* header 解析失败就当没传，不影响主流程 */ }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) throw new Exception("HTTP " + code + "（无响应体）");
        String enc = c.getContentEncoding();
        if (enc != null && enc.toLowerCase().contains("gzip")) in = new GZIPInputStream(in);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
            if (bos.size() > 6 * 1024 * 1024) break;   // 弹幕 JSON 可能较大，放到 6MB 上限
        }
        in.close();
        String body = new String(bos.toByteArray(), "UTF-8");
        if (code >= 400) {
            throw new Exception("HTTP " + code + " " + body.substring(0, Math.min(120, body.length())));
        }
        return body;
    }

    /** 取回原始字节并按 raw deflate 解压，返回 UTF-8 文本。
        B 站 list.so 的响应没有 Content-Encoding 标记，需要自己判定：
        先尝试当 deflate 解，解不开再当明文返回（个别情况下服务端会直接吐 XML）。 */
    private String fetchDeflate(String urlStr, int timeoutMs) throws Exception {
        URL u = new URL(urlStr);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        if (c instanceof HttpsURLConnection && permissiveSsl != null) {
            ((HttpsURLConnection) c).setSSLSocketFactory(permissiveSsl.getSocketFactory());
            ((HttpsURLConnection) c).setHostnameVerifier((hostname, session) -> true);
        }
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", "https://www.bilibili.com/");
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Accept-Encoding", "identity");   // 不要 gzip，拿原始 deflate 流
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) throw new Exception("HTTP " + code + "（无响应体）");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
            if (bos.size() > 8 * 1024 * 1024) break;
        }
        in.close();
        byte[] raw = bos.toByteArray();
        if (code >= 400) throw new Exception("HTTP " + code + "（" + raw.length + " 字节）");
        /* 明文以 '<' 开头（XML/HTML）；否则按 raw deflate 解 */
        if (raw.length > 0 && raw[0] == '<') {
            return new String(raw, "UTF-8");
        }
        try {
            Inflater inf = new Inflater(true);           // true = nowrap（raw deflate）
            inf.setInput(raw);
            ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length * 4);
            byte[] tmp = new byte[8192];
            while (!inf.finished()) {
                int k = inf.inflate(tmp);
                if (k == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break;
                }
                out.write(tmp, 0, k);
            }
            inf.end();
            return new String(out.toByteArray(), "UTF-8");
        } catch (Exception e) {
            /* 解压失败就把原文当文本返回，让 JS 侧自己判断（可能是错误提示） */
            return new String(raw, "UTF-8");
        }
    }

    private String fetchHead(String urlStr, int timeoutMs, int maxBytes) throws Exception {
        URL u = new URL(urlStr);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        if (c instanceof HttpsURLConnection && permissiveSsl != null) {
            ((HttpsURLConnection) c).setSSLSocketFactory(permissiveSsl.getSocketFactory());
            ((HttpsURLConnection) c).setHostnameVerifier((hostname, session) -> true);
        }
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        int code = c.getResponseCode();
        // 重定向后取真实地址：相对路径清单要基于它拼绝对地址
        String finalUrl = c.getURL().toString();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) throw new Exception("HTTP " + code + "（无响应体）");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int n;
        int total = 0;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
            total += n;
            if (total >= maxBytes) break;   // 清单头部足够判定结构，不拉完整分片列表
        }
        in.close();
        c.disconnect();
        if (code >= 400) throw new Exception("HTTP " + code);
        /* 这里不能按内容类型判死：同一个 probe 还负责抓采集站的播放页 HTML
           （影视版的 resolvePlayPage 靠它拿真地址），HTML 也是 200。
           「200 但正文是错误 JSON」这种情况交给 JS 侧判（见 liveProbeAll）。 */
        return finalUrl + "\n" + new String(bos.toByteArray(), "UTF-8");
    }

    private static SSLContext buildPermissiveSsl() {
        try {
            TrustManager[] tm = new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, tm, new SecureRandom());
            return sc;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 返回键
    @Override
    public void onBackPressed() {
        /* 1) 播放器打开 + 当前是强制横屏 → 第一下返回先退出横屏回到竖屏继续播。
              再按一次才关闭播放器。这样「横屏看片想回竖屏」不用退出重进。 */
        if (playerOpen && isLandscapeNow()) {
            playerLandscape = false;
            doExitLandscape();
            web.evaluateJavascript("toast('已切回竖屏，返回键再按一次关闭播放器')", null);
            return;
        }
        // 2) 播放器打开（竖屏）→ 关闭播放器，不退出 APP
        if (playerOpen) {
            applyFullscreen(false);   // 兜底：即使 JS 未执行也退出全屏
            web.evaluateJavascript("closePlayer()", null);
            return;
        }
        /* 3) 页面内还有上一级：交给 JS 退一级。
           以前这里是 if (web.canGoBack()) web.goBack()——单页应用没有 history，
           canGoBack() 只在文档级导航时成立，退一步就离开 app.html，用户看到的
           就是「按返回没回上一页，直接退出软件」。 */
        if (canBackInApp) {
            web.evaluateJavascript("__ltvBack()", null);
            return;
        }
        // 4) 连通桌面服务模式下页面里可能真有前进历史，留一条兜底
        if (web.canGoBack()) {
            web.goBack();
            return;
        }
        // 5) 已经在根视图：再按一次退出
        long now = System.currentTimeMillis();
        if (now - lastBack < 2000) {
            super.onBackPressed();
        } else {
            lastBack = now;
            toast("再按一次退出匆匆影视");
        }
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }
}
