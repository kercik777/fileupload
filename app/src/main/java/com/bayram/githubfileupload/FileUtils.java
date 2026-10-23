package com.bayram.githubfileupload;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class FileUtils {

    private FileUtils() {
    }

    /** Имя файла из content:// uri. */
    public static String nameOf(Context ctx, Uri uri) {
        String name = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) {
                        name = c.getString(idx);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (name == null || name.isEmpty()) {
            String path = uri.getPath();
            if (path != null && path.contains("/")) {
                name = path.substring(path.lastIndexOf('/') + 1);
            } else {
                name = uri.getLastPathSegment();
            }
        }
        if (name == null || name.isEmpty()) {
            name = "file";
        }
        return name;
    }

    /** MIME-тип выбранного файла. */
    public static String mimeOf(Context ctx, Uri uri) {
        String mime = null;
        try {
            mime = ctx.getContentResolver().getType(uri);
        } catch (Exception ignored) {
        }
        if (mime == null || mime.isEmpty()) {
            mime = mimeFromName(nameOf(ctx, uri));
        }
        return (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime;
    }

    public static String mimeFromName(String name) {
        if (name == null) {
            return null;
        }
        String ext = extension(name);
        if (ext.isEmpty()) {
            return null;
        }
        try {
            String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (type != null) {
                return type;
            }
        } catch (Exception ignored) {
        }
        switch (ext) {
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "gif":
                return "image/gif";
            case "webp":
                return "image/webp";
            case "bmp":
                return "image/bmp";
            case "svg":
                return "image/svg+xml";
            case "mp4":
                return "video/mp4";
            case "webm":
                return "video/webm";
            case "mov":
                return "video/quicktime";
            case "mkv":
                return "video/x-matroska";
            case "mp3":
                return "audio/mpeg";
            case "wav":
                return "audio/wav";
            case "ogg":
                return "audio/ogg";
            case "pdf":
                return "application/pdf";
            case "json":
                return "application/json";
            case "txt":
                return "text/plain";
            case "csv":
                return "text/csv";
            case "zip":
                return "application/zip";
            case "apk":
                return "application/vnd.android.package-archive";
            default:
                return null;
        }
    }

    public static String extension(String name) {
        if (name == null) {
            return "";
        }
        int i = name.lastIndexOf('.');
        if (i < 0 || i == name.length() - 1) {
            return "";
        }
        return name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    /** Очистка имени для использования в URL и Git. */
    public static String sanitize(String name) {
        if (name == null) {
            return "file";
        }
        String n = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (n.isEmpty()) {
            n = "file";
        }
        String ext = extension(n);
        if (n.length() > 120) {
            String base = ext.isEmpty() ? n : n.substring(0, n.length() - ext.length() - 1);
            n = base.substring(0, Math.min(base.length(), 100)) + (ext.isEmpty() ? "" : "." + ext);
        }
        return n;
    }

    /** Уникальное имя с префиксом-временем. */
    public static String uniqueName(String name) {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
        String ext = extension(name);
        String base = ext.isEmpty() ? name : name.substring(0, name.length() - ext.length() - 1);
        return stamp + "_" + sanitize(base) + (ext.isEmpty() ? "" : "." + ext);
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " Б";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return trim(kb) + " КБ";
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return trim(mb) + " МБ";
        }
        return trim(mb / 1024.0) + " ГБ";
    }

    private static String trim(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /** Копирует выбранный файл в кэш приложения и возвращает File. */
    public static File copyToCache(Context ctx, Uri uri, String displayName) throws IOException {
        File dir = new File(ctx.getCacheDir(), "uploads");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Не удалось создать папку кэша");
        }
        File out = new File(dir, uniqueName(displayName));
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(out)) {
            if (in == null) {
                throw new IOException("Не удалось открыть файл");
            }
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                os.write(buf, 0, n);
            }
            os.flush();
        }
        return out;
    }

    public static boolean isImage(String mime) {
        return mime != null && mime.toLowerCase(Locale.ROOT).startsWith("image/");
    }

    public static boolean isVideo(String mime) {
        return mime != null && mime.toLowerCase(Locale.ROOT).startsWith("video/");
    }

    /** Экранирование пути файла для URL. */
    public static String encodePath(String path) {
        String[] parts = path.split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            try {
                sb.append(java.net.URLEncoder.encode(parts[i], "UTF-8").replace("+", "%20"));
            } catch (Exception e) {
                sb.append(parts[i]);
            }
        }
        return sb.toString();
    }
}
