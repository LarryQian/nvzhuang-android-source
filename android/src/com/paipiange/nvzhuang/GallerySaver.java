package com.paipiange.nvzhuang;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * 把图片写进系统相册（图库）。
 *
 * 为什么需要它：试衣结果图是壳自己提供的虚拟地址
 * https://appassets.local/.local/results/xxx.jpg，系统 DownloadManager 根本访问不到，
 * 于是「保存到相册」只会进入下载队列然后悄无声息地失败。这里改成直接把字节写进
 * MediaStore（Android 10+）或 Pictures 目录 + 媒体扫描（旧系统）。
 */
public final class GallerySaver {

    private static final String ALBUM = "拍片哥女装";

    private GallerySaver() {
    }

    /** data:image/jpeg;base64,... → 字节 */
    public static byte[] bytesFromDataUrl(String dataUrl) {
        if (dataUrl == null) {
            return null;
        }
        int comma = dataUrl.indexOf(',');
        if (comma < 0 || !dataUrl.startsWith("data:")) {
            return null;
        }
        try {
            return Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
        }
        catch (Exception e) {
            return null;
        }
    }

    public static String mimeFromName(String name) {
        String lower = name == null ? "" : name.toLowerCase();
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    /**
     * @return 成功返回 "ok:<保存位置>"，失败返回 "err:<原因>"
     */
    public static String save(Context context, byte[] bytes, String displayName) {
        if (context == null || bytes == null || bytes.length == 0) {
            return "err:图片内容为空";
        }
        String name = displayName == null || displayName.isEmpty()
                ? ("tryon-" + System.currentTimeMillis() + ".jpg")
                : displayName;
        String mime = mimeFromName(name);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return saveViaMediaStore(context, bytes, name, mime);
        }
        return saveLegacy(context, bytes, name);
    }

    private static String saveViaMediaStore(Context context, byte[] bytes, String name, String mime) {
        ContentResolver resolver = context.getContentResolver();
        Uri uri = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, mime);
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM);
            values.put(MediaStore.Images.Media.IS_PENDING, 1);

            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                return "err:无法创建相册记录";
            }
            OutputStream os = resolver.openOutputStream(uri);
            if (os == null) {
                return "err:无法写入相册";
            }
            try {
                os.write(bytes);
                os.flush();
            }
            finally {
                os.close();
            }

            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            return "ok:" + Environment.DIRECTORY_PICTURES + "/" + ALBUM + "/" + name;
        }
        catch (Exception e) {
            if (uri != null) {
                try {
                    resolver.delete(uri, null, null);
                }
                catch (Exception ignored) {
                }
            }
            return "err:" + describe(e);
        }
    }

    @SuppressWarnings("deprecation")
    private static String saveLegacy(Context context, byte[] bytes, String name) {
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES), ALBUM);
            if (!dir.exists() && !dir.mkdirs()) {
                return "err:无法创建目录";
            }
            File out = new File(dir, name);
            FileOutputStream fos = new FileOutputStream(out);
            try {
                fos.write(bytes);
                fos.flush();
            }
            finally {
                fos.close();
            }
            MediaScannerConnection.scanFile(context,
                    new String[]{out.getAbsolutePath()}, new String[]{mimeFromName(name)}, null);
            return "ok:" + out.getAbsolutePath();
        }
        catch (Exception e) {
            return "err:" + describe(e);
        }
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        return msg == null || msg.isEmpty() ? t.getClass().getSimpleName() : msg;
    }
}
