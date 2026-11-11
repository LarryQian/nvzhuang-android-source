package com.paipiange.nvzhuang;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 「拍片哥女装」Android 壳工程。
 *
 * 把 uni-app 编译出的 H5 产物（dist/build/h5）放进 assets，
 * 通过虚拟 https 源 https://appassets.local/ 提供给 WebView：
 * 这样页面拥有普通安全源（可用 localStorage / IndexedDB / fetch），
 * 而内容仍然完全离线来自 APK 内的 assets。
 */
public class MainActivity extends Activity {

    private static final String APP_HOST = "appassets.local";
    private static final String APP_ORIGIN = "https://" + APP_HOST + "/";
    private static final String START_URL = APP_ORIGIN + "index.html";
    /** 图片 CDN：构建时把缩略图镜像到 assets 下，命中则离线直供，未命中仍走网络 */
    private static final String PAGES_HOST = "paipiange.github.io";
    private static final String PAGES_ASSETS_PREFIX = "/nvzhuang/assets/";
    private static final String LOCAL_PREFIX = "/.local/";
    private static final int REQ_FILE_CHOOSER = 1001;

    private WebView webView;
    private GrsaiBridge grsaiBridge;
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        webView = new WebView(this);
        webView.setBackgroundColor(0xFFFFFFFF);
        setContentView(webView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true);
        }

        webView.setWebViewClient(new AssetWebViewClient());
        webView.setWebChromeClient(new AppChromeClient());
        webView.setDownloadListener(new AppDownloadListener());

        // GRSAI 直连桥：没有自建后端时，换装生成直接打 GRSAI（gpt-image-2.6）
        grsaiBridge = new GrsaiBridge(this, webView, readBuiltinGrsaiKey(),
                new File(getFilesDir(), "results"));
        webView.addJavascriptInterface(grsaiBridge, "NativeGrsai");

        webView.loadUrl(START_URL);
    }

    private String readBuiltinGrsaiKey() {
        try {
            String key = getString(R.string.grsai_key);
            return key == null ? "" : key.trim();
        }
        catch (Exception e) {
            return "";
        }
    }

    /* ---------------- 资源拦截：把 https://appassets.local/ 映射到 assets ---------------- */

    private final class AssetWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUrl(request == null ? null : request.getUrl());
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUrl(url == null ? null : Uri.parse(url));
        }

        private boolean handleUrl(Uri uri) {
            if (uri == null) {
                return false;
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
            if ("about".equals(scheme) || "data".equals(scheme) || "blob".equals(scheme)
                    || "javascript".equals(scheme) || "file".equals(scheme)) {
                return false;
            }
            if (("https".equals(scheme) || "http".equals(scheme)) && APP_HOST.equals(uri.getHost())) {
                return false;
            }
            // 外链（淘宝图搜、GitHub、哔哩哔哩等）交给系统浏览器
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception ignored) {
                Toast.makeText(MainActivity.this, "无法打开该链接", Toast.LENGTH_SHORT).show();
            }
            return true;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return serve(request == null ? null : request.getUrl());
        }

        @SuppressWarnings("deprecation")
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            return serve(url == null ? null : Uri.parse(url));
        }

        private WebResourceResponse serve(Uri uri) {
            if (uri == null) {
                return null;
            }
            // 图片 CDN 镜像：把 Pages 上的缩略图请求改由 APK 内的 assets 直供（离线可用）
            if (PAGES_HOST.equals(uri.getHost())) {
                String pagesPath = uri.getPath();
                if (pagesPath != null && pagesPath.startsWith(PAGES_ASSETS_PREFIX)) {
                    String tail = pagesPath.substring(PAGES_ASSETS_PREFIX.length());
                    WebResourceResponse mirrored = serveMirror(tail);
                    if (mirrored != null) {
                        return mirrored;
                    }
                }
                return null;
            }
            if (!APP_HOST.equals(uri.getHost())) {
                return null;
            }
            String path = uri.getPath();
            if (path != null && path.startsWith(LOCAL_PREFIX)) {
                // GRSAI 生成结果落盘在 filesDir/results，用 /.local/results/xxx 读取
                return serveLocal(path.substring(LOCAL_PREFIX.length()));
            }
            if (path == null || path.length() == 0 || "/".equals(path)) {
                path = "/index.html";
            }
            String assetPath = path.startsWith("/") ? path.substring(1) : path;
            if (assetPath.contains("..")) {
                return null;
            }
            try {
                InputStream in = getAssets().open(assetPath);
                return withCors(new WebResourceResponse(mimeOf(assetPath), null, in));
            } catch (IOException notFound) {
                // hash 路由下不会出现真实 404，但静态目录兜底回首页
                try {
                    InputStream fallback = getAssets().open("index.html");
                    return withCors(new WebResourceResponse("text/html", "utf-8", fallback));
                } catch (IOException ignored) {
                    return null;
                }
            }
        }

        private WebResourceResponse serveLocal(String relative) {
            if (relative == null || relative.contains("..")) {
                return null;
            }
            File file = new File(getFilesDir(), relative);
            if (!file.isFile()) {
                return null;
            }
            try {
                InputStream in = new FileInputStream(file);
                return withCors(new WebResourceResponse(mimeOf(relative), null, in));
            }
            catch (IOException e) {
                return null;
            }
        }

        private WebResourceResponse withCors(WebResourceResponse response) {
            if (response == null) {
                return null;
            }
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Cache-Control", "no-cache");
            response.setResponseHeaders(headers);
            return response;
        }

        private WebResourceResponse serveMirror(String tail) {
            String used = tail;
            InputStream in = openAsset(tail);
            if (in == null) {
                String alt = thumbFallback(tail);
                if (alt != null) {
                    in = openAsset(alt);
                    used = alt;
                }
            }
            if (in == null) {
                return null;
            }
            return withCors(new WebResourceResponse(mimeOf(used), null, in));
        }

        private InputStream openAsset(String path) {
            try {
                return getAssets().open(path);
            }
            catch (IOException e) {
                return null;
            }
        }

        /** 全尺寸图没有打进 APK 时，退到同名缩略图 */
        private String thumbFallback(String tail) {
            String alt = null;
            if (tail.startsWith("anime/img/")) {
                alt = "anime/thumbs/" + tail.substring("anime/img/".length());
            }
            else if (tail.startsWith("anime/png/")) {
                alt = "anime/thumbs/" + tail.substring("anime/png/".length());
            }
            else if (tail.startsWith("img/")) {
                alt = "thumbs/" + tail.substring("img/".length());
            }
            else if (tail.startsWith("png/")) {
                alt = "thumbs/" + tail.substring("png/".length());
            }
            if (alt == null) {
                return null;
            }
            int dot = alt.lastIndexOf('.');
            if (dot > 0) {
                alt = alt.substring(0, dot) + ".webp";
            }
            return alt;
        }

        private String mimeOf(String path) {
            String lower = path.toLowerCase(Locale.US);
            int dot = lower.lastIndexOf('.');
            String ext = dot < 0 ? "" : lower.substring(dot + 1);
            if ("html".equals(ext) || "htm".equals(ext)) return "text/html";
            if ("js".equals(ext) || "mjs".equals(ext)) return "application/javascript";
            if ("css".equals(ext)) return "text/css";
            if ("json".equals(ext) || "map".equals(ext)) return "application/json";
            if ("png".equals(ext)) return "image/png";
            if ("jpg".equals(ext) || "jpeg".equals(ext)) return "image/jpeg";
            if ("gif".equals(ext)) return "image/gif";
            if ("webp".equals(ext)) return "image/webp";
            if ("svg".equals(ext)) return "image/svg+xml";
            if ("ico".equals(ext)) return "image/x-icon";
            if ("woff".equals(ext)) return "font/woff";
            if ("woff2".equals(ext)) return "font/woff2";
            if ("ttf".equals(ext)) return "font/ttf";
            if ("otf".equals(ext)) return "font/otf";
            if ("wasm".equals(ext)) return "application/wasm";
            if ("mp3".equals(ext)) return "audio/mpeg";
            if ("mp4".equals(ext)) return "video/mp4";
            if ("txt".equals(ext)) return "text/plain";
            return "application/octet-stream";
        }
    }

    /* ---------------- 文件选择 / 上传支持 ---------------- */

    private final class AppChromeClient extends WebChromeClient {

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(null);
            }
            filePathCallback = callback;
            Intent intent = null;
            try {
                if (params != null) {
                    intent = params.createIntent();
                }
            } catch (Exception ignored) {
                intent = null;
            }
            if (intent == null) {
                intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
            }
            try {
                startActivityForResult(intent, REQ_FILE_CHOOSER);
            } catch (Exception e) {
                filePathCallback = null;
                Toast.makeText(MainActivity.this, "没有可用的图片选择器", Toast.LENGTH_SHORT).show();
                return false;
            }
            return true;
        }

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (newProgress >= 100) {
                view.setVisibility(View.VISIBLE);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            Uri[] picked = null;
            try {
                picked = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            }
            catch (Exception ignored) {
                picked = null;
            }
            // 原生侧直接把选中的图片解码成 data URL，页面不必再 fetch(blob:)
            if (picked != null && picked.length > 0 && grsaiBridge != null) {
                String shot = UriImage.decodeToDataUrl(this, picked[0]);
                if (shot != null) {
                    grsaiBridge.setPickedImage(shot);
                }
            }
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(picked);
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /* ---------------- 图片下载（原尺寸 PNG） ---------------- */

    private final class AppDownloadListener implements DownloadListener {
        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                    String mimeType, long contentLength) {
            if (url == null) {
                return;
            }
            String lower = url.toLowerCase(Locale.US);
            // 结果图是壳提供的虚拟地址（/ .local/results）或 data URL，
            // DownloadManager 抓不到，直接交给原生相册写入
            if (lower.startsWith("data:") || lower.contains(APP_HOST + "/.local/")) {
                String saved = grsaiBridge == null ? "err:保存通道未就绪"
                        : grsaiBridge.saveImage(url, URLUtil.guessFileName(url, contentDisposition, mimeType));
                if (saved != null && saved.startsWith("ok:")) {
                    Toast.makeText(MainActivity.this, "已保存到相册", Toast.LENGTH_SHORT).show();
                }
                else {
                    Toast.makeText(MainActivity.this,
                            "保存失败：" + (saved == null ? "未知原因" : saved.replace("err:", "")),
                            Toast.LENGTH_LONG).show();
                }
                return;
            }
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                Toast.makeText(MainActivity.this, "该图片请在页面内长按保存", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.addRequestHeader("User-Agent", userAgent);
                request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                        URLUtil.guessFileName(url, contentDisposition, mimeType));
                DownloadManager manager =
                        (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (manager != null) {
                    manager.enqueue(request);
                    Toast.makeText(MainActivity.this, "已加入下载队列", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, "下载失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    /* ---------------- 生命周期 / 返回键 ---------------- */

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (webView != null && webView.canGoBack()) {
                webView.goBack();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            webView.saveState(outState);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) {
            webView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }
}
