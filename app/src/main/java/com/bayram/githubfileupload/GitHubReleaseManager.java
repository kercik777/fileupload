package com.bayram.githubfileupload;

import android.os.Handler;
import android.os.Looper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Загрузка и удаление файлов в GitHub Releases.
 */
public class GitHubReleaseManager {

    private static final MediaType JSON_MT = MediaType.get("application/json; charset=utf-8");
    private static final MediaType OCTET_STREAM = MediaType.get("application/octet-stream");

    private final OkHttpClient http;
    private final Handler ui;

    public interface ProgressListener {
        void onProgress(int percent);
    }

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(String error);
    }

    public static class ReleaseUploadResult {
        public String downloadUrl = "";
        public long releaseId;
        public long assetId;
        public String assetName = "";
        public String tag = "";
    }

    public GitHubReleaseManager() {
        http = new OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.MINUTES)
                .readTimeout(5, TimeUnit.MINUTES)
                .writeTimeout(15, TimeUnit.MINUTES)
                .build();
        ui = new Handler(Looper.getMainLooper());
    }

    /** Загрузить файл в релиз и вернуть browser_download_url. */
    public void upload(GitHubConfig cfg, String tag, File file, String fileName, String mime,
                       ProgressListener pl, Callback<String> cb) {
        uploadWithMeta(cfg, tag, file, fileName, mime, pl, new Callback<ReleaseUploadResult>() {
            @Override
            public void onSuccess(ReleaseUploadResult result) {
                cb.onSuccess(result.downloadUrl);
            }

            @Override
            public void onError(String error) {
                cb.onError(error);
            }
        });
    }

    /** Загрузить файл в релиз и вернуть ссылку + метаданные релиза/ассета. */
    public void uploadWithMeta(GitHubConfig cfg, String tag, File file, String fileName, String mime,
                               ProgressListener pl, Callback<ReleaseUploadResult> cb) {
        if (file == null || !file.exists() || !file.isFile()) {
            post(cb, null, "Файл не найден");
            return;
        }
        String cleanTag = (tag == null || tag.trim().isEmpty()) ? "latest" : tag.trim();
        String safeFileName = FileUtils.sanitize(fileName);
        getOrCreateRelease(cfg, cleanTag, safeFileName, file, mime, pl, cb);
    }

    private void getOrCreateRelease(GitHubConfig cfg, String tag, String fileName, File file,
                                    String mime, ProgressListener pl, Callback<ReleaseUploadResult> cb) {
        HttpUrl url = api(cfg, "releases/tags/" + tag);
        Request request = auth(cfg).url(url).get().build();

        http.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onResponse(okhttp3.Call call, Response response) throws IOException {
                try {
                    if (response.isSuccessful()) {
                        String json = readBody(response);
                        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                        long releaseId = obj.get("id").getAsLong();
                        long duplicateAssetId = findAssetIdByName(obj, fileName);
                        if (duplicateAssetId > 0) {
                            deleteAssetSilentlyThenUpload(cfg, releaseId, duplicateAssetId,
                                    tag, fileName, file, mime, pl, cb);
                        } else {
                            uploadAsset(cfg, releaseId, tag, fileName, file, mime, pl, cb);
                        }
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

    private void deleteAssetSilentlyThenUpload(GitHubConfig cfg, long releaseId, long oldAssetId,
                                               String tag, String fileName, File file, String mime,
                                               ProgressListener pl, Callback<ReleaseUploadResult> cb) {
        HttpUrl delUrl = api(cfg, "releases/assets/" + oldAssetId);
        Request req = auth(cfg).url(delUrl).delete().build();
        http.newCall(req).enqueue(new okhttp3.Callback() {
            @Override
            public void onResponse(okhttp3.Call call, Response response) {
                response.close();
                uploadAsset(cfg, releaseId, tag, fileName, file, mime, pl, cb);
            }

            @Override
            public void onFailure(okhttp3.Call call, IOException e) {
                uploadAsset(cfg, releaseId, tag, fileName, file, mime, pl, cb);
            }
        });
    }

    private void createRelease(GitHubConfig cfg, String tag, String fileName, File file,
                               String mime, ProgressListener pl, Callback<ReleaseUploadResult> cb) {
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

        http.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onResponse(okhttp3.Call call, Response response) throws IOException {
                try {
                    if (response.isSuccessful()) {
                        String json = readBody(response);
                        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                        long releaseId = obj.get("id").getAsLong();
                        uploadAsset(cfg, releaseId, tag, fileName, file, mime, pl, cb);
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

    private void uploadAsset(GitHubConfig cfg, long releaseId, String tag, String fileName, File file,
                             String mime, ProgressListener pl, Callback<ReleaseUploadResult> cb) {
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

        http.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onResponse(okhttp3.Call call, Response response) throws IOException {
                try {
                    if (response.isSuccessful()) {
                        String json = readBody(response);
                        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                        ReleaseUploadResult res = new ReleaseUploadResult();
                        res.downloadUrl = obj.get("browser_download_url").getAsString();
                        res.releaseId = releaseId;
                        res.assetId = obj.has("id") ? obj.get("id").getAsLong() : 0L;
                        res.assetName = obj.has("name") ? obj.get("name").getAsString() : fileName;
                        res.tag = tag;
                        post(cb, res, null);
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

    /**
     * Удаляет конкретный файл (asset) из релиза GitHub.
     * Если это был единственный файл в релизе — удаляет и сам пустой релиз вместе с тегом.
     */
    public void deleteItemFromRelease(GitHubConfig cfg, UploadItem item, Callback<Boolean> cb) {
        String tag = (item.tag == null || item.tag.trim().isEmpty())
                ? extractTagFromDownloadUrl(item.downloadUrl)
                : item.tag.trim();
        String expectedAssetName = (item.releaseAssetName != null && !item.releaseAssetName.isEmpty())
                ? item.releaseAssetName
                : extractAssetNameFromDownloadUrl(item.downloadUrl, item.name);

        if (tag.isEmpty()) {
            if (item.releaseAssetId > 0) {
                deleteSingleAssetById(cfg, item.releaseAssetId, cb);
            } else {
                post(cb, true, null);
            }
            return;
        }

        HttpUrl url = api(cfg, "releases/tags/" + tag);
        Request request = auth(cfg).url(url).get().build();

        http.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onResponse(okhttp3.Call call, Response response) throws IOException {
                try {
                    if (response.code() == 404) {
                        post(cb, true, null);
                        return;
                    }
                    if (!response.isSuccessful()) {
                        post(cb, null, "Ошибка получения релиза: HTTP " + response.code());
                        return;
                    }
                    JsonObject obj = JsonParser.parseString(readBody(response)).getAsJsonObject();
                    long releaseId = obj.get("id").getAsLong();
                    JsonArray assets = obj.has("assets") && obj.get("assets").isJsonArray()
                            ? obj.getAsJsonArray("assets") : new JsonArray();

                    long targetAssetId = item.releaseAssetId;
                    if (targetAssetId <= 0) {
                        targetAssetId = matchAssetId(assets, item.downloadUrl, expectedAssetName);
                    }

                    if (assets.size() <= 1 && (assets.size() == 0 || targetAssetId > 0)) {
                        deleteReleaseById(cfg, releaseId, tag, cb);
                    } else if (targetAssetId > 0) {
                        deleteSingleAssetById(cfg, targetAssetId, cb);
                    } else {
                        // Файла уже нет среди ассетов релиза
                        post(cb, true, null);
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

    private void deleteSingleAssetById(GitHubConfig cfg, long assetId, Callback<Boolean> cb) {
        HttpUrl url = api(cfg, "releases/assets/" + assetId);
        Request request = auth(cfg).url(url).delete().build();

        http.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onResponse(okhttp3.Call call, Response response) {
                try {
                    if (response.isSuccessful() || response.code() == 404) {
                        post(cb, true, null);
                    } else {
                        post(cb, null, "Ошибка удаления файла из релиза: HTTP " + response.code());
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

    /** Удалить релиз целиком и его Git-тег. */
    public void deleteRelease(GitHubConfig cfg, String tag, Callback<Boolean> cb) {
        if (tag == null || tag.trim().isEmpty()) {
            post(cb, null, "Укажите тег");
            return;
        }
        HttpUrl url = api(cfg, "releases/tags/" + tag.trim());
        Request request = auth(cfg).url(url).get().build();

        http.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onResponse(okhttp3.Call call, Response response) throws IOException {
                try {
                    if (response.isSuccessful()) {
                        String json = readBody(response);
                        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                        long releaseId = obj.get("id").getAsLong();
                        deleteReleaseById(cfg, releaseId, tag.trim(), cb);
                    } else if (response.code() == 404) {
                        post(cb, true, null);
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

        http.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onResponse(okhttp3.Call call, Response response) throws IOException {
                try {
                    if (response.isSuccessful() || response.code() == 404) {
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

        http.newCall(request).enqueue(new okhttp3.Callback() {
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

    private static long findAssetIdByName(JsonObject releaseObj, String fileName) {
        if (releaseObj == null || !releaseObj.has("assets") || !releaseObj.get("assets").isJsonArray()) {
            return 0L;
        }
        JsonArray assets = releaseObj.getAsJsonArray("assets");
        for (JsonElement el : assets) {
            if (!el.isJsonObject()) continue;
            JsonObject a = el.getAsJsonObject();
            String name = a.has("name") && !a.get("name").isJsonNull() ? a.get("name").getAsString() : "";
            if (name.equalsIgnoreCase(fileName) && a.has("id")) {
                return a.get("id").getAsLong();
            }
        }
        return 0L;
    }

    private static long matchAssetId(JsonArray assets, String downloadUrl, String assetName) {
        for (JsonElement el : assets) {
            if (!el.isJsonObject()) continue;
            JsonObject a = el.getAsJsonObject();
            long id = a.has("id") ? a.get("id").getAsLong() : 0L;
            String dl = a.has("browser_download_url") && !a.get("browser_download_url").isJsonNull()
                    ? a.get("browser_download_url").getAsString() : "";
            String name = a.has("name") && !a.get("name").isJsonNull()
                    ? a.get("name").getAsString() : "";
            if (downloadUrl != null && !downloadUrl.isEmpty() && downloadUrl.equals(dl)) {
                return id;
            }
            if (assetName != null && !assetName.isEmpty() && assetName.equalsIgnoreCase(name)) {
                return id;
            }
        }
        return 0L;
    }

    public static String extractTagFromDownloadUrl(String downloadUrl) {
        if (downloadUrl == null || downloadUrl.isEmpty()) {
            return "";
        }
        String marker = "/releases/download/";
        int idx = downloadUrl.indexOf(marker);
        if (idx < 0) {
            return "";
        }
        String rest = downloadUrl.substring(idx + marker.length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            return "";
        }
        return FileUtils.decodeUrlSegment(rest.substring(0, slash));
    }

    public static String extractAssetNameFromDownloadUrl(String downloadUrl, String fallback) {
        if (downloadUrl != null && !downloadUrl.isEmpty()) {
            int slash = downloadUrl.lastIndexOf('/');
            if (slash >= 0 && slash < downloadUrl.length() - 1) {
                return FileUtils.decodeUrlSegment(downloadUrl.substring(slash + 1));
            }
        }
        return fallback == null ? "" : fallback;
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
                .header("User-Agent", "GitHubUploader/2.1");
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
