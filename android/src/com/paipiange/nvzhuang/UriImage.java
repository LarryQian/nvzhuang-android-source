package com.paipiange.nvzhuang;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * 把系统图片选择器返回的 Uri 直接解码成 data URL。
 *
 * 为什么不让页面自己 fetch(blob:)：
 * uni-app H5 的 chooseImage 给到页面的是一段 blob: 地址，在自建 https 源
 * （appassets.local + shouldInterceptRequest）的 WebView 里并不总能取到内容，
 * 失败时页面只能报「参考图读取失败」。这里回到原生侧读文件，稳。
 */
public final class UriImage {

    private static final int MAX_EDGE = 1280;
    private static final int JPEG_QUALITY = 85;

    private UriImage() {
    }

    /** @return data:image/jpeg;base64,... 或 null */
    public static String decodeToDataUrl(Context context, Uri uri) {
        if (context == null || uri == null) {
            return null;
        }
        try {
            Bitmap bitmap = loadScaledBitmap(context, uri);
            if (bitmap == null) {
                return null;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos);
            bitmap.recycle();
            byte[] bytes = bos.toByteArray();
            if (bytes.length == 0) {
                return null;
            }
            return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
        }
        catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap loadScaledBitmap(Context context, Uri uri) throws Exception {
        ContentResolver resolver = context.getContentResolver();

        // 1) 先读边界，按最长边缩放，避免把 5000px 原图整个读进内存
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream probe = open(context, resolver, uri);
        if (probe != null) {
            try {
                BitmapFactory.decodeStream(probe, null, bounds);
            }
            finally {
                probe.close();
            }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }

        int sample = 1;
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        while (longest / sample > MAX_EDGE) {
            sample *= 2;
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        InputStream in = open(context, resolver, uri);
        if (in == null) {
            return null;
        }
        Bitmap decoded;
        try {
            decoded = BitmapFactory.decodeStream(in, null, opts);
        }
        finally {
            in.close();
        }
        if (decoded == null) {
            return null;
        }

        int w = decoded.getWidth();
        int h = decoded.getHeight();
        int side = Math.max(w, h);
        if (side <= MAX_EDGE) {
            return decoded;
        }
        float scale = (float) MAX_EDGE / (float) side;
        Bitmap scaled = Bitmap.createScaledBitmap(decoded, Math.round(w * scale), Math.round(h * scale), true);
        if (scaled != decoded) {
            decoded.recycle();
        }
        return scaled;
    }

    private static InputStream open(Context context, ContentResolver resolver, Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme();
        try {
            if ("file".equals(scheme)) {
                return new FileInputStream(new File(uri.getPath()));
            }
            if ("content".equals(scheme) || "android.resource".equals(scheme)) {
                return resolver.openInputStream(uri);
            }
            // 没有 scheme 的裸路径，当作本地文件
            if (scheme.isEmpty()) {
                return new FileInputStream(new File(uri.toString()));
            }
        }
        catch (Exception ignored) {
        }
        return null;
    }
}
