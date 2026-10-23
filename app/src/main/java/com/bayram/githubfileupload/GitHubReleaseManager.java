package com.bayram.githubfileupload;

import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Загрузка файлов в GitHub Releases и получение прямой ссылки для скачивания
 * (browser_download_url). Эта часть — то, что было в проекте изначально.
 */
public class GitHubReleaseManager {

    private static final MediaType JSON_MT = MediaType.get("application/json; charset=utf-8");
    private static final MediaType OCTET_STREAM = MediaType.get("application/octet-stream");

    private final OkHttpClient http;
    private final Gson gson;
    private final Handler ui;

    public interface ProgressListener {
        void onProgress(int percent);
    }

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(String error);
    }

    public GitHubReleaseManager() {
        http = new OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.MINUTES)
                .readTimeout(5, TimeUnit.MINUTES)
                .writeTimeout(15, TimeUnit.MINUTES)
                .build();
        gson = new Gson();
        ui = new Handler(Looper.getMainLooper());
    }

    /** Загрузить файл в релиз и вернуть browser_download_url. */
    public void upload(GitHubConfig cfg, String tag, File file, String fileName, String mime,
                       ProgressListener pl, Callback<String> cb) {
        if (file == null || !file.exists() || !file.isFile()) {
            post(cb, null, "Файл не найден");
            return;
        }
        if (tag == null || tag.trim().isEmpty()) {
            tag = "latest";
        }
        getOrCreateRelease(cfg, tag.trim(), fileName, file, mime, pl, cb);
    }

    private void getOrCreateRelease(GitHubConfig cfg, String tag, String fileName, File file,
                                    String mime, ProgressListener pl, Callback<String> cb) {
        HttpUrl url = api(cfg, "releases/tags/" + tag);
        Request request = auth(cfg).url(url).get().build();

        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onResponse(okhttp3.Call call, Response response) throws IOException {
                try {
                    if (response.isSuccessful()) {
                        String json = readBody(response);
                        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                        long releaseId = obj.get("id").getAsLong();
                        uploadAsset(cfg, releaseId, fileName, file, mime, pl, cb);
                    } else if (response.code() == 404) {
                        createRelease(cfg, tag, fileName, file, mime, pl, cb);
                    } else {
                        post(cb, null, "Ошибка получения релиза: HTTP " + response.code());
                    }
                } finally {
                    response.close();
                }
            }

            @Override
            public void onFailure(okhttp3.Call call, IOException e) {
                post(cb, null, e.getMessage());
            }
        });
    }

    private void createRelease(GitHubConfig cfg, String tag, String fileName, File file,
                               String mime, ProgressListener pl, Callback<String> cb) {
        HttpUrl url = api(cfg, "releases");
        JsonObject body = new JsonObject();
        body.addProperty("tag_name", tag);
        body.addProperty("name", tag);
        body.addProperty("body", "Uploaded via GitHub Uploader");
        body.addProperty("draft", false);
        body.addProperty("prerelease", false);
        body.addProperty("target_commitish", cfg.branch);

        Request request = auth(cfg)
                .url(url)
                .post(RequestBody.create(JSON_MT, body.toString()))
                .build();

        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onResponse(okhttp3.Call call, Response response) throws IOException {
                try {
                    if (response.isSuccessful()) {
                        String json = readBody(response);
                        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                        long releaseId = obj.get("id").getAsLong();
                        uploadAsset(cfg, releaseId, fileName, file, mime, pl, cb);
                    } else {
                        post(cb, null, "Ошибка создания релиза: HTTP " + response.code()
                                + " - " + readBody(response));
                    }
                } finally {
                    response.close();
                }
            }

            @Override
            public void onFailure(okhttp3.Call call, IOException e) {
                post(cb, null, e.getMessage());
            }
        });
    }

    private void uploadAsset(GitHubConfig cfg, long releaseId, String fileName, File file,
                             String mime, ProgressListener pl, Callback<String> cb) {
        HttpUrl url = new HttpUrl.Builder()
                .scheme("https")
                .host("uploads.github.com")
                .addPathSegments("repos/" + cfg.owner + "/" + cfg.repo
                        + "/releases/" + releaseId + "/assets")
                .addQueryParameter("name", fileName)
                .build();

        RequestBody fileBody = RequestBody.create(OCTET_STREAM, file);
        if (pl != null) {
            fileBody = new ProgressRequestBody(fileBody, (written, total) -> {
                int percent = total > 0 ? (int) (written * 100L / total) : 0;
                pl.onProgress(Math.min(percent, 100));
            });
        }

        String contentType = (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime;
        Request request = auth(cfg)
                .url(url)
                .post(fileBody)
                .header("Content-Type", contentType)
                .build();

        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onResponse(okhttp3.Call call, Response response) throws IOException {
                try {
                    if (response.isSuccessful()) {
                        String json = readBody(response);
                        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                        String downloadUrl = obj.get("browser_download_url").getAsString();
                        post(cb, downloadUrl, null);
                    } else {
                        post(cb, null, "Ошибка загрузки файла: HTTP " + response.code()
                                + " - " + readBody(response));
                    }
                } finally {
                    response.close();
                }
            }

            @Override
            public void onFailure(okhttp3.Call call, IOException e) {
                post(cb, null, e.getMessage());
            }
        });
    }

    /** Удалить релиз и его Git-тег. */
    public void deleteRelease(GitHubConfig cfg, String tag, Callback<Boolean> cb) {
        if (tag == null || tag.trim().isEmpty()) {
            post(cb, null, "Укажите тег");
            return;
        }
        HttpUrl url = api(cfg, "releases/tags/" + tag.trim());
        Request request = auth(cfg).url(url).get().build();

        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onResponse(okhttp3.Call call, Response response) throws IOException {
                try {
                    if (response.isSuccessful()) {
                        String json = readBody(response);
                        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                        long releaseId = obj.get("id").getAsLong();
                        deleteReleaseById(cfg, releaseId, tag.trim(), cb);
                    } else {
                        post(cb, null, "Релиз не найден: " + tag);
                    }
                } finally {
                    response.close();
                }
            }

            @Override
            public void onFailure(okhttp3.Call call, IOException e) {
                post(cb, null, e.getMessage());
            }
        });
    }

    private void deleteReleaseById(GitHubConfig cfg, long releaseId, String tag, Callback<Boolean> cb) {
        HttpUrl url = api(cfg, "releases/" + releaseId);
        Request request = auth(cfg).url(url).delete().build();

        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onResponse(okhttp3.Call call, Response response) throws IOException {
                try {
                    if (response.isSuccessful()) {
                        deleteGitTag(cfg, tag, cb);
                    } else {
                        post(cb, null, "Ошибка удаления релиза: HTTP " + response.code());
                    }
                } finally {
                    response.close();
                }
            }

            @Override
            public void onFailure(okhttp3.Call call, IOException e) {
                post(cb, null, e.getMessage());
            }
        });
    }

    private void deleteGitTag(GitHubConfig cfg, String tag, Callback<Boolean> cb) {
        HttpUrl url = api(cfg, "git/refs/tags/" + tag);
        Request request = auth(cfg).url(url).delete().build();

        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onResponse(okhttp3.Call call, Response response) throws IOException {
                try {
                    boolean success = response.code() == 204 || response.code() == 404;
                    String error = success ? null : "Ошибка удаления тега: HTTP " + response.code();
                    post(cb, success, error);
                } finally {
                    response.close();
                }
            }

            @Override
            public void onFailure(okhttp3.Call call, IOException e) {
                post(cb, null, e.getMessage());
            }
        });
    }

    private HttpUrl api(GitHubConfig cfg, String segments) {
        return new HttpUrl.Builder()
                .scheme("https")
                .host("api.github.com")
                .addPathSegments("repos/" + cfg.owner + "/" + cfg.repo + "/" + segments)
                .build();
    }

    private Request.Builder auth(GitHubConfig cfg) {
        return new Request.Builder()
                .header("Authorization", "token " + cfg.token)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "GitHubUploader/2.0");
    }

    private static String readBody(Response response) {
        try {
            ResponseBody b = response.body();
            return b == null ? "" : b.string();
        } catch (Exception e) {
            return "";
        }
    }

    private <T> void post(Callback<T> cb, T result, String error) {
        ui.post(() -> {
            if (error != null) {
                cb.onError(error);
            } else {
                cb.onSuccess(result);
            }
        });
    }
}
