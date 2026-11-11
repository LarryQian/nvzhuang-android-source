package com.paipiange.nvzhuang;

import android.app.Activity;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * GRSAI 直连桥：让 H5 页面在没有自建后端的情况下也能出图。
 *
 * JS 侧调用 window.NativeGrsai.generate(requestId, payloadJson)，
 * Java 侧在后台线程请求 https://grsaiapi.com（失败自动回退 api.grsai.com），
 * 拿到结果图后落盘到 filesDir/results，再回调 window.__onGrsaiResult(id, status, payload)。
 *
 * 之所以走原生而不是浏览器 fetch：
 * 1) WebView 里跨域请求第三方接口会被 CORS 拦；
 * 2) 图片要转 data URL（reference image），浏览器侧没有本地文件读取权限。
 */
public class GrsaiBridge {

    private static final String[] ENDPOINTS = {
            "https://grsaiapi.com",
            "https://api.grsai.com",
            "https://grsai.dakka.com.cn",
    };
    private static final String DEFAULT_MODEL = "gpt-image-2.6";
    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 300000;
    private static final int MAX_IMAGE_BYTES = 16 * 1024 * 1024;

    private final Activity activity;
    private final WebView webView;
    private final String defaultKey;
    private final File resultDir;
    /** 系统图片选择器最近一次选中的人像，已由 MainActivity 解码成 data URL */
    private volatile String pickedImageDataUrl = "";

    public GrsaiBridge(Activity activity, WebView webView, String defaultKey, File resultDir) {
        this.activity = activity;
        this.webView = webView;
        this.defaultKey = defaultKey == null ? "" : defaultKey.trim();
        this.resultDir = resultDir;
        if (!resultDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            resultDir.mkdirs();
        }
    }

    @JavascriptInterface
    public String builtinKey() {
        // 只回一个是否有内置 key 的标记，避免把密钥明文喂给页面脚本
        return defaultKey.isEmpty() ? "" : "builtin";
    }

    /** MainActivity 在图片选择回调里写入 */
    public void setPickedImage(String dataUrl) {
        pickedImageDataUrl = dataUrl == null ? "" : dataUrl;
    }

    /** 页面取用并清空：返回系统选择器选中的那张人像（data URL） */
    @JavascriptInterface
    public String takePickedImage() {
        String value = pickedImageDataUrl;
        pickedImageDataUrl = "";
        return value == null ? "" : value;
    }

    /**
     * 把一张图片存进系统相册。页面直接调它，绕开 <a download> + DownloadManager 那条死路
     * （结果图是壳提供的虚拟地址，DownloadManager 访问不到）。
     *
     * @param src      data: URL / https://appassets.local/.local/... / APK 内镜像地址 / http(s)
     * @param fileName 期望文件名（可空）
     * @return "ok:<位置>" 或 "err:<原因>"
     */
    @JavascriptInterface
    public String saveImage(String src, String fileName) {
        try {
            byte[] bytes = readImageBytes(src);
            if (bytes == null || bytes.length == 0) {
                return "err:读取不到图片内容";
            }
            String name = fileName;
            if (name == null || name.trim().isEmpty()) {
                name = "tryon-" + System.currentTimeMillis() + guessExt(src);
            }
            else if (!name.contains(".")) {
                name = name + guessExt(src);
            }
            return GallerySaver.save(activity, bytes, name);
        }
        catch (Throwable t) {
            return "err:" + describe(t);
        }
    }

    /**
     * 把「你的照片」（相册图 / 自拍）持久化到 App 私有目录，返回稳定的虚拟地址。
     * 页面拿到的是 blob: 地址，App 关掉就失效，历史记录里的「原人像」缩略图会变空白。
     */
    @JavascriptInterface
    public String savePersonImage(String dataUrl) {
        try {
            byte[] bytes = GallerySaver.bytesFromDataUrl(dataUrl);
            if (bytes == null || bytes.length == 0) {
                return "";
            }
            File dir = new File(activity.getFilesDir(), "people");
            if (!dir.exists() && !dir.mkdirs()) {
                return "";
            }
            String name = "p-" + md5Short(bytes) + ".jpg";
            File out = new File(dir, name);
            if (!out.isFile() || out.length() != bytes.length) {
                FileOutputStream fos = new FileOutputStream(out);
                try {
                    fos.write(bytes);
                }
                finally {
                    fos.close();
                }
            }
            return "https://appassets.local/.local/people/" + name;
        }
        catch (Throwable t) {
            return "";
        }
    }

    private static String md5Short(byte[] bytes) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8 && i < digest.length; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        }
        catch (Exception e) {
            return Long.toHexString(System.currentTimeMillis());
        }
    }

    private byte[] readImageBytes(String src) {
        if (src == null || src.isEmpty()) {
            return null;
        }
        if (src.startsWith("data:")) {
            return GallerySaver.bytesFromDataUrl(src);
        }
        String localTail = localPathTail(src);
        if (localTail != null) {
            return readLocalFile(localTail);
        }
        String assetTail = pagesMirrorTail(src);
        if (assetTail != null) {
            byte[] fromAssets = readAsset(assetTail);
            if (fromAssets != null) {
                return fromAssets;
            }
        }
        if (src.startsWith("http://") || src.startsWith("https://")) {
            try {
                return download(src);
            }
            catch (Exception ignored) {
            }
        }
        return null;
    }

    /** 读 App 私有目录下的图片（结果图 results/、人像 people/ 都走这里） */
    private byte[] readLocalFile(String relative) {
        if (relative == null || relative.contains("..")) {
            return null;
        }
        File file = new File(activity.getFilesDir(), relative);
        if (!file.isFile()) {
            return null;
        }
        try {
            return readBytes(new java.io.FileInputStream(file), MAX_IMAGE_BYTES);
        }
        catch (Exception e) {
            return null;
        }
    }

    /** https://appassets.local/.local/results/xxx.jpg → results/xxx.jpg */
    private String localPathTail(String src) {
        String marker = "/.local/";
        int idx = src.indexOf(marker);
        if (idx < 0) {
            return null;
        }
        String tail = src.substring(idx + marker.length());
        int q = tail.indexOf('?');
        if (q > 0) {
            tail = tail.substring(0, q);
        }
        return tail.contains("..") ? null : tail;
    }

    private String guessExt(String src) {
        if (src == null) {
            return ".jpg";
        }
        String lower = src.toLowerCase();
        int q = lower.indexOf('?');
        if (q > 0) {
            lower = lower.substring(0, q);
        }
        if (lower.endsWith(".png")) {
            return ".png";
        }
        if (lower.endsWith(".webp")) {
            return ".webp";
        }
        return ".jpg";
    }

    @JavascriptInterface
    public void generate(final String requestId, final String payloadJson) {
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runGenerate(requestId, payloadJson);
                }
                catch (Throwable t) {
                    callback(requestId, "err", "生成失败: " + describe(t));
                }
            }
        }, "grsai-" + requestId);
        worker.setDaemon(true);
        worker.start();
    }

    /* ------------------------------------------------------------------ */

    private void runGenerate(String requestId, String payloadJson) throws Exception {
        JSONObject payload = new JSONObject(payloadJson == null ? "{}" : payloadJson);
        String key = payload.optString("key", "");
        if (key.isEmpty()) {
            key = defaultKey;
        }
        if (key.isEmpty()) {
            callback(requestId, "err", "缺少 GRSAI 令牌：请在「我的 → 服务与模型设置」里填入 sk- 开头的 Key");
            return;
        }

        String model = payload.optString("model", DEFAULT_MODEL);
        String prompt = payload.optString("prompt", "");
        if (prompt.isEmpty()) {
            callback(requestId, "err", "提示词为空");
            return;
        }
        String aspectRatio = payload.optString("aspectRatio", "1024x1536");

        JSONArray images = payload.optJSONArray("images");
        List<String> dataUrls = new ArrayList<>();
        if (images != null) {
            for (int i = 0; i < images.length(); i++) {
                String raw = images.optString(i, "");
                if (raw.isEmpty()) {
                    continue;
                }
                String dataUrl = toDataUrl(raw);
                if (dataUrl != null) {
                    dataUrls.add(dataUrl);
                }
            }
        }
        if (dataUrls.isEmpty()) {
            callback(requestId, "err", "缺少参考图（需要人像 + 服装两张）");
            return;
        }

        String resultUrl = null;
        String lastError = null;

        // 1) 首选 completions 路由（SSE，细节更好）
        for (String base : ENDPOINTS) {
            try {
                resultUrl = callCompletions(base, key, model, prompt, dataUrls, aspectRatio);
                if (resultUrl != null) {
                    break;
                }
            }
            catch (Exception e) {
                lastError = describe(e);
            }
        }

        // 2) 回退统一路由（纯 JSON）
        if (resultUrl == null) {
            for (String base : ENDPOINTS) {
                try {
                    resultUrl = callUnified(base, key, model, prompt, dataUrls, aspectRatio);
                    if (resultUrl != null) {
                        break;
                    }
                }
                catch (Exception e) {
                    lastError = describe(e);
                }
            }
        }

        if (resultUrl == null) {
            callback(requestId, "err", lastError == null ? "GRSAI 未返回图片地址" : lastError);
            return;
        }

        String localUrl = cacheResult(requestId, resultUrl);
        JSONObject out = new JSONObject();
        out.put("url", localUrl);
        out.put("remoteUrl", resultUrl);
        out.put("model", model);
        callback(requestId, "ok", out.toString());
    }

    /** POST /v1/draw/completions —— SSE，逐行找 status=succeeded 的 results[0].url */
    private String callCompletions(String base, String key, String model, String prompt,
                                   List<String> urls, String aspectRatio) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("urls", new JSONArray(urls));
        body.put("shutProgress", true);
        body.put("aspectRatio", aspectRatio);

        HttpURLConnection conn = open(base + "/v1/draw/completions", key);
        writeBody(conn, body.toString());
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) {
            throw new IOException("HTTP " + code + " 无响应体");
        }

        String result = null;
        String errorLine = null;
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.startsWith("data:")) {
                    trimmed = trimmed.substring(5).trim();
                }
                if (!trimmed.startsWith("{")) {
                    continue;
                }
                try {
                    JSONObject obj = new JSONObject(trimmed);
                    String status = obj.optString("status", "");
                    String url = firstResultUrl(obj);
                    if (url != null) {
                        result = url;
                    }
                    if ("failed".equals(status) || "error".equals(status)) {
                        errorLine = obj.optString("error", obj.optString("msg", trimmed));
                    }
                }
                catch (Exception ignored) {
                    // 进度帧可能是非完整 JSON，忽略
                }
            }
        }
        finally {
            try {
                reader.close();
            }
            catch (IOException ignored) {
            }
            conn.disconnect();
        }

        if (result == null && errorLine != null) {
            throw new IOException(errorLine);
        }
        return result;
    }

    /** POST /v1/api/generate —— 纯 JSON */
    private String callUnified(String base, String key, String model, String prompt,
                               List<String> images, String aspectRatio) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("images", new JSONArray(images));
        body.put("aspectRatio", aspectRatio);
        body.put("replyType", "json");

        HttpURLConnection conn = open(base + "/v1/api/generate", key);
        writeBody(conn, body.toString());
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String text = readAll(in);
        conn.disconnect();

        if (text.isEmpty()) {
            throw new IOException("HTTP " + code + " 空响应");
        }
        JSONObject obj = new JSONObject(text);
        String url = firstResultUrl(obj);
        if (url != null) {
            return url;
        }
        String err = obj.optString("error", obj.optString("msg", ""));
        throw new IOException(err.isEmpty() ? ("HTTP " + code + ": " + trim(text)) : err);
    }

    private String firstResultUrl(JSONObject obj) {
        JSONArray results = obj.optJSONArray("results");
        if (results != null) {
            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.optJSONObject(i);
                if (item != null) {
                    String u = item.optString("url", "");
                    if (!u.isEmpty()) {
                        return u;
                    }
                }
            }
        }
        JSONArray data = obj.optJSONArray("data");
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.optJSONObject(i);
                if (item != null) {
                    String u = item.optString("url", "");
                    if (!u.isEmpty()) {
                        return u;
                    }
                }
            }
        }
        return null;
    }

    /* ---------------------------- 图片处理 ---------------------------- */

    /**
     * 把任意参考图转成 data URL：
     * - data: 直接返回；
     * - https://paipiange.github.io/nvzhuang/assets/... 优先读 APK 内的镜像（离线可用）；
     * - 其它 http(s) 现场下载。
     */
    private String toDataUrl(String raw) {
        if (raw.startsWith("data:")) {
            return raw;
        }
        byte[] bytes = null;
        String mime = null;

        // App 私有目录里的图（任务队列里存的是 https://appassets.local/.local/people/xxx.jpg 这种稳定地址）
        String localTail = localPathTail(raw);
        if (localTail != null) {
            bytes = readLocalFile(localTail);
            mime = mimeOf(localTail);
        }

        String pagesTail = pagesMirrorTail(raw);
        if (bytes == null && pagesTail != null) {
            try {
                bytes = readAsset(pagesTail);
                mime = mimeOf(pagesTail);
                if (bytes == null) {
                    String alt = thumbFallback(pagesTail);
                    if (alt != null) {
                        bytes = readAsset(alt);
                        mime = mimeOf(alt);
                    }
                }
            }
            catch (Exception ignored) {
            }
        }

        if (bytes == null && (raw.startsWith("http://") || raw.startsWith("https://"))) {
            try {
                bytes = download(raw);
                mime = mimeOf(raw);
            }
            catch (Exception ignored) {
            }
        }

        if (bytes == null || bytes.length == 0) {
            return null;
        }
        if (mime == null || "application/octet-stream".equals(mime)) {
            mime = "image/png";
        }
        return "data:" + mime + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    private String pagesMirrorTail(String url) {
        String marker = "/nvzhuang/assets/";
        int idx = url.indexOf(marker);
        if (idx < 0 || url.indexOf("paipiange.github.io") < 0) {
            return null;
        }
        String tail = url.substring(idx + marker.length());
        int q = tail.indexOf('?');
        if (q > 0) {
            tail = tail.substring(0, q);
        }
        return tail;
    }

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

    private byte[] readAsset(String path) {
        try {
            InputStream in = activity.getAssets().open(path);
            try {
                return readBytes(in, MAX_IMAGE_BYTES);
            }
            finally {
                in.close();
            }
        }
        catch (IOException e) {
            return null;
        }
    }

    private byte[] download(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) nvzhuang");
        try {
            InputStream in = conn.getInputStream();
            try {
                return readBytes(in, MAX_IMAGE_BYTES);
            }
            finally {
                in.close();
            }
        }
        finally {
            conn.disconnect();
        }
    }

    /** 结果图落盘，页面用 https://appassets.local/.local/results/xxx 读取 */
    private String cacheResult(String requestId, String remoteUrl) {
        try {
            byte[] bytes = download(remoteUrl);
            if (bytes == null || bytes.length == 0) {
                return remoteUrl;
            }
            String ext = remoteUrl.contains(".png") ? ".png" : ".jpg";
            String name = "r-" + requestId + ext;
            File out = new File(resultDir, name);
            FileOutputStream fos = new FileOutputStream(out);
            try {
                fos.write(bytes);
            }
            finally {
                fos.close();
            }
            return "https://appassets.local/.local/results/" + name;
        }
        catch (Exception e) {
            return remoteUrl;
        }
    }

    /* ------------------------------ 工具 ------------------------------ */

    private HttpURLConnection open(String url, String key) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "text/event-stream, application/json");
        conn.setRequestProperty("Authorization", "Bearer " + key);
        return conn;
    }

    private void writeBody(HttpURLConnection conn, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        OutputStream os = conn.getOutputStream();
        try {
            os.write(bytes);
        }
        finally {
            os.close();
        }
    }

    private static byte[] readBytes(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            if (bos.size() > max) {
                throw new IOException("图片过大");
            }
        }
        return bos.toByteArray();
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private String mimeOf(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        return "image/jpeg";
    }

    private void callback(final String requestId, final String status, final String payload) {
        final String js = "window.__onGrsaiResult(" + JSONObject.quote(requestId) + ","
                + JSONObject.quote(status) + "," + JSONObject.quote(payload) + ");";
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    webView.evaluateJavascript(js, null);
                }
            }
        });
    }

    private static String trim(String text) {
        String t = text.replace('\n', ' ').trim();
        return t.length() > 240 ? t.substring(0, 240) + "..." : t;
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isEmpty()) {
            msg = t.getClass().getSimpleName();
        }
        return msg;
    }
}
