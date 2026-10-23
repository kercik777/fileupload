package com.bayram.githubfileupload;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import androidx.annotation.DrawableRes;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Простой асинхронный загрузчик картинок для http(s) и content:// uri.
 */
public final class ImageLoader {

    public interface Listener {
        void onReady();

        void onError();
    }

    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final int DEFAULT_MAX_DIM = 512;
    private static volatile int seq = 0;

    private ImageLoader() {
    }

    public static void load(Context ctx, ImageView view, String uri, @DrawableRes int placeholder) {
        load(ctx, view, uri, placeholder, DEFAULT_MAX_DIM, null);
    }

    public static void load(final Context ctx, final ImageView view, final String uri,
                            @DrawableRes final int placeholder, final int maxDim,
                            final Listener listener) {
        final int token = ++seq;
        view.setTag(R.id.image_loader_token, token);
        if (placeholder != 0) {
            view.setImageResource(placeholder);
        }
        if (uri == null || uri.isEmpty()) {
            if (listener != null) listener.onError();
            return;
        }
        new Thread(() -> {
            try {
                byte[] data = readAll(ctx, uri);
                if (data == null || data.length == 0) {
                    fail(view, token, placeholder, listener);
                    return;
                }
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                int w = bounds.outWidth;
                int h = bounds.outHeight;
                if (w <= 0 || h <= 0) {
                    fail(view, token, placeholder, listener);
                    return;
                }
                int sample = 1;
                while (w / sample > maxDim || h / sample > maxDim) {
                    sample *= 2;
                }
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = sample;
                Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
                if (bmp == null) {
                    fail(view, token, placeholder, listener);
                    return;
                }
                final Bitmap result = bmp;
                UI.post(() -> {
                    if (isCurrent(view, token)) {
                        view.setImageBitmap(result);
                        if (listener != null) listener.onReady();
                    }
                });
            } catch (Exception e) {
                fail(view, token, placeholder, listener);
            }
        }).start();
    }

    private static void fail(ImageView view, int token, int placeholder, Listener listener) {
        UI.post(() -> {
            if (isCurrent(view, token) && placeholder != 0) {
                view.setImageResource(placeholder);
            }
            if (listener != null) {
                listener.onError();
            }
        });
    }

    private static boolean isCurrent(ImageView view, int token) {
        Object tag = view.getTag(R.id.image_loader_token);
        return tag instanceof Integer && ((Integer) tag) == token;
    }

    private static byte[] readAll(Context ctx, String uri) throws Exception {
        InputStream in;
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            Request req = new Request.Builder().url(uri).get().build();
            try (Response resp = HTTP.newCall(req).execute();
                 ResponseBody body = resp.body()) {
                if (!resp.isSuccessful() || body == null) {
                    return null;
                }
                in = body.byteStream();
            }
        } else {
            in = ctx.getContentResolver().openInputStream(Uri.parse(uri));
        }
        if (in == null) {
            return null;
        }
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            long total = 0;
            while ((n = is.read(buf)) != -1) {
                total += n;
                if (total > 64L * 1024 * 1024) {
                    return null;
                }
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }
}
