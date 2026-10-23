package com.bayram.githubfileupload;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

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
 * Запись и удаление файлов в ветке репозитория для прямого просмотра в браузере
 * (jsDelivr CDN и raw.githubusercontent.com).
 */
public class GitHubRepoManager {

    private static final MediaType JSON_MT = MediaType.get("application/json; charset=utf-8");
    /** Лимит репозитория для одного файла. */
    private static final long MAX_FILE_BYTES = 90L * 1024 * 1024;

    private final OkHttpClient http;
    private final Handler ui;

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(String error);
    }

    public static class PreviewResult {
        public String rawUrl = "";
        public String cdnUrl = "";
        public String pageUrl = "";
        public String commitUrl = "";
        public String path = "";
        public String sha = "";
        public String owner = "";
        public String repo = "";
        public String branch = "";
    }

    public static class RepoInfo {
        public String name = "";
        public String fullName = "";
        public String defaultBranch = "";
        public boolean privateRepo;
        public String description = "";
    }

    public GitHubRepoManager() {
        http = new OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.MINUTES)
                .readTimeout(5, TimeUnit.MINUTES)
                .writeTimeout(15, TimeUnit.MINUTES)
                .build();
        ui = new Handler(Looper.getMainLooper());
    }

    /** Проверить соединение и получить информацию о репозитории. */
    public void check(GitHubConfig cfg, Callback<RepoInfo> cb) {
        HttpUrl url = repoUrl(cfg);
        Request request = auth(cfg).url(url).get().build();

        http.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onResponse(okhttp3.Call call, Response response) throws IOException {
                try {
                    if (response.isSuccessful()) {
                        JsonObject obj = JsonParser.parseString(readBody(response)).getAsJsonObject();
                        RepoInfo info = new RepoInfo();
                        info.name = str(obj, "name");
                        info.fullName = str(obj, "full_name");
                        info.defaultBranch = str(obj, "default_branch");
                        info.privateRepo = obj.has("private") && obj.get("private").getAsBoolean();
                        info.description = str(obj, "description");
                        post(cb, info, null);
                    } else if (response.code() == 404) {
                        post(cb, null, "Репозиторий не найден или токен не подходит");
                    } else if (response.code() == 401) {
                        post(cb, null, "Неверный токен или нет прав (нужен scope repo)");
                    } else {
                        post(cb, null, "HTTP " + response.code() + " — " + readBody(response));
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
     * Записать файл в репозиторий (на бранч для предпросмотра) и вернуть ссылки.
     */
    public void upload(GitHubConfig cfg, File file, String relativePath, String message,
                       Callback<PreviewResult> cb) {
        if (file == null || !file.exists() || !file.isFile()) {
            post(cb, null, "Файл не найден");
            return;
        }
        if (file.length() > MAX_FILE_BYTES) {
            post(cb, null, "Файл слишком большой для предпросмотра (лимит репозитория 100 МБ)");
            return;
        }

        HttpUrl repo = repoUrl(cfg);
        Request metaReq = auth(cfg).url(repo).get().build();

        http.newCall(metaReq).enqueue(new okhttp3.Callback() {
            @Override
            public void onResponse(okhttp3.Call call, Response response) throws IOException {
                try {
                    if (!response.isSuccessful()) {
                        post(cb, null, "Не удалось получить репозиторий: HTTP " + response.code()
                                + " — " + readBody(response));
                        return;
                    }
                    JsonObject meta = JsonParser.parseString(readBody(response)).getAsJsonObject();
                    String defaultBranch = str(meta, "default_branch");
                    if (defaultBranch.isEmpty()) {
                        defaultBranch = cfg.branch;
                    }
                    ensureBranch(cfg, defaultBranch, relativePath, file, message, cb);
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
     * Удалить файл из ветки репозитория (для любых фото, видео и прочих файлов предпросмотра).
     */
    public void deleteFile(GitHubConfig cfg, String branch, String relativePath, String knownSha,
                           Callback<Boolean> cb) {
        String cleanPath = relativePath == null ? "" : relativePath.trim();
        while (cleanPath.startsWith("/")) {
            cleanPath = cleanPath.substring(1);
        }
        if (cleanPath.isEmpty()) {
            post(cb, true, null);
            return;
        }
        final String targetPath = cleanPath;
        final String targetBranch = (branch == null || branch.trim().isEmpty())
                ? ((cfg.previewBranch == null || cfg.previewBranch.trim().isEmpty())
                ? "files" : cfg.previewBranch.trim())
                : branch.trim();

        if (knownSha != null && !knownSha.trim().isEmpty()) {
            deleteFileWithSha(cfg, targetBranch, targetPath, knownSha.trim(), true, cb);
        } else {
            fetchShaAndDelete(cfg, targetBranch, targetPath, cb);
        }
    }

    private void fetchShaAndDelete(GitHubConfig cfg, String branch, String path, Callback<Boolean> cb) {
        HttpUrl url = api(cfg, "contents/" + path).newBuilder()
                .addQueryParameter("ref", branch)
                .build();
        Request req = auth(cfg).url(url).get().build();

        http.newCall(req).enqueue(new okhttp3.Callback() {
            @Override
            public void onResponse(okhttp3.Call call, Response response) throws IOException {
                try {
                    if (response.code() == 404) {
                        post(cb, true, null);
                        return;
                    }
                    if (!response.isSuccessful()) {
                        post(cb, null, "Ошибка чтения файла в репозитории: HTTP " + response.code());
                        return;
                    }
                    JsonElement parsed = JsonParser.parseString(readBody(response));
                    if (!parsed.isJsonObject()) {
                        post(cb, null, "Указанный путь не является файлом");
                        return;
                    }
                    String sha = str(parsed.getAsJsonObject(), "sha");
                    if (sha.isEmpty()) {
                        post(cb, true, null);
                        return;
                    }
                    deleteFileWithSha(cfg, branch, path, sha, false, cb);
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

    private void deleteFileWithSha(GitHubConfig cfg, String branch, String path, String sha,
                                   boolean retryOnConflict, Callback<Boolean> cb) {
        HttpUrl url = api(cfg, "contents/" + path);
        JsonObject body = new JsonObject();
        body.addProperty("message", "Delete: " + path);
        body.addProperty("sha", sha);
        body.addProperty("branch", branch);

        Request req = auth(cfg)
                .url(url)
                .delete(RequestBody.create(JSON_MT, body.toString()))
                .build();

        http.newCall(req).enqueue(new okhttp3.Callback() {
            @Override
            public void onResponse(okhttp3.Call call, Response response) throws IOException {
                try {
                    if (response.isSuccessful() || response.code() == 404) {
                        post(cb, true, null);
                    } else if ((response.code() == 409 || response.code() == 422) && retryOnConflict) {
                        fetchShaAndDelete(cfg, branch, path, cb);
                    } else {
                        post(cb, null, "Ошибка удаления файла из репозитория: HTTP " + response.code());
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

    /** Извлекает относительный путь файла в репозитории из CDN / raw ссылки, если previewPath пуст. */
    public static String extractRepoPathFromItem(UploadItem item) {
        if (item == null) {
            return "";
        }
        if (item.previewPath != null && !item.previewPath.trim().isEmpty()) {
            return item.previewPath.trim();
        }
        String cdn = item.previewUrl == null ? "" : item.previewUrl;
        if (!cdn.isEmpty()) {
            // https://cdn.jsdelivr.net/gh/<owner>/<repo>@<branch>/<path>
            int at = cdn.indexOf('@');
            if (at > 0) {
                int slash = cdn.indexOf('/', at);
                if (slash > 0 && slash < cdn.length() - 1) {
                    return decodePath(cdn.substring(slash + 1));
                }
            }
        }
        String raw = item.previewRawUrl == null ? "" : item.previewRawUrl;
        if (!raw.isEmpty()) {
            // https://raw.githubusercontent.com/<owner>/<repo>/<branch>/<path>
            String prefix = "https://raw.githubusercontent.com/";
            if (raw.startsWith(prefix)) {
                String rest = raw.substring(prefix.length());
                String[] parts = rest.split("/", 4);
                if (parts.length == 4) {
                    return decodePath(parts[3]);
                }
            }
        }
        return "";
    }

    private static String decodePath(String encodedPath) {
        String[] segs = encodedPath.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segs.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(FileUtils.decodeUrlSegment(segs[i]));
        }
        return sb.toString();
    }

    private void ensureBranch(GitHubConfig cfg, String defaultBranch, String relativePath,
                              File file, String message, Callback<PreviewResult> cb) {
        final String preview = ((cfg.previewBranch == null || cfg.previewBranch.trim().isEmpty())
                ? "files" : cfg.previewBranch).trim();

        if (preview.equalsIgnoreCase(defaultBranch)) {
            commit(cfg, preview, relativePath, file, message, cb);
            return;
        }

        HttpUrl refUrl = api(cfg, "git/refs/heads/" + preview);
        Request refReq = auth(cfg).url(refUrl).get().build();

        http.newCall(refReq).enqueue(new okhttp3.Callback() {
            @Override
            public void onResponse(okhttp3.Call call, Response response) throws IOException {
                try {
                    if (response.isSuccessful()) {
                        commit(cfg, preview, relativePath, file, message, cb);
                    } else if (response.code() == 404) {
                        createBranch(cfg, defaultBranch, preview, relativePath, file, message, cb);
                    } else {
                        post(cb, null, "Не удалось получить бранч: HTTP " + response.code()
                                + " — " + readBody(response));
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

    private void createBranch(GitHubConfig cfg, String defaultBranch, String preview,
                              String relativePath, File file, String message,
                              Callback<PreviewResult> cb) {
        HttpUrl baseRef = api(cfg, "git/refs/heads/" + defaultBranch);
        Request req = auth(cfg).url(baseRef).get().build();

        http.newCall(req).enqueue(new okhttp3.Callback() {
            @Override
            public void onResponse(okhttp3.Call call, Response response) throws IOException {
                try {
                    if (!response.isSuccessful()) {
                        post(cb, null, "Не удалось получить базовый бранч: HTTP " + response.code());
                        return;
                    }
                    String sha = JsonParser.parseString(readBody(response)).getAsJsonObject()
                            .get("object").getAsJsonObject().get("sha").getAsString();

                    HttpUrl createUrl = api(cfg, "git/refs");
                    JsonObject body = new JsonObject();
                    body.addProperty("ref", "refs/heads/" + preview);
                    body.addProperty("sha", sha);

                    Request createReq = auth(cfg)
                            .url(createUrl)
                            .post(RequestBody.create(JSON_MT, body.toString()))
                            .build();

                    http.newCall(createReq).enqueue(new okhttp3.Callback() {
                        @Override
                        public void onResponse(okhttp3.Call c, Response r) throws IOException {
                            try {
                                if (r.isSuccessful()) {
                                    commit(cfg, preview, relativePath, file, message, cb);
                                } else {
                                    post(cb, null, "Не удалось создать бранч: HTTP " + r.code()
                                            + " — " + readBody(r));
                                }
                            } finally {
                                r.close();
                            }
                        }

                        @Override
                        public void onFailure(okhttp3.Call c, IOException e) {
                            post(cb, null, e.getMessage());
                        }
                    });
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

    private void commit(GitHubConfig cfg, String branch, String relativePath, File file,
                        String message, Callback<PreviewResult> cb) {
        String content;
        try {
            content = Base64.encodeToString(readBytes(file), Base64.NO_WRAP);
        } catch (Exception e) {
            post(cb, null, "Не удалось прочитать файл: " + e.getMessage());
            return;
        }

        HttpUrl url = api(cfg, "contents/" + relativePath);
        JsonObject body = new JsonObject();
        body.addProperty("message", message);
        body.addProperty("content", content);
        body.addProperty("branch", branch);

        Request request = auth(cfg)
                .url(url)
                .put(RequestBody.create(JSON_MT, body.toString()))
                .build();

        http.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onResponse(okhttp3.Call call, Response response) throws IOException {
                try {
                    if (response.isSuccessful()) {
                        String commitUrl = "";
                        String fileSha = "";
                        try {
                            JsonObject obj = JsonParser.parseString(readBody(response)).getAsJsonObject();
                            JsonObject commit = obj.has("commit") ? obj.getAsJsonObject("commit") : null;
                            if (commit != null && commit.has("html_url")) {
                                commitUrl = commit.get("html_url").getAsString();
                            }
                            JsonObject contentObj = obj.has("content") && obj.get("content").isJsonObject()
                                    ? obj.getAsJsonObject("content") : null;
                            if (contentObj != null && contentObj.has("sha")) {
                                fileSha = str(contentObj, "sha");
                            }
                        } catch (Exception ignored) {
                        }
                        post(cb, buildResult(cfg, branch, relativePath, commitUrl, fileSha), null);
                    } else {
                        post(cb, null, "Не удалось записать файл в репозиторий: HTTP "
                                + response.code() + " — " + readBody(response));
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

    private PreviewResult buildResult(GitHubConfig cfg, String branch, String path,
                                      String commitUrl, String sha) {
        PreviewResult r = new PreviewResult();
        r.owner = cfg.owner;
        r.repo = cfg.repo;
        r.branch = branch;
        r.path = path;
        r.sha = sha == null ? "" : sha;
        String enc = FileUtils.encodePath(path);
        r.rawUrl = "https://raw.githubusercontent.com/" + cfg.owner + "/" + cfg.repo
                + "/" + branch + "/" + enc;
        r.cdnUrl = "https://cdn.jsdelivr.net/gh/" + cfg.owner + "/" + cfg.repo
                + "@" + branch + "/" + enc;
        r.pageUrl = "https://github.com/" + cfg.owner + "/" + cfg.repo
                + "/blob/" + branch + "/" + enc;
        r.commitUrl = commitUrl;
        return r;
    }

    private static byte[] readBytes(File file) throws IOException {
        java.io.FileInputStream fis = new java.io.FileInputStream(file);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = fis.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
        } finally {
            fis.close();
        }
        return bos.toByteArray();
    }

    private HttpUrl repoUrl(GitHubConfig cfg) {
        return new HttpUrl.Builder()
                .scheme("https")
                .host("api.github.com")
                .addPathSegments("repos/" + cfg.owner + "/" + cfg.repo)
                .build();
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

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return "";
        }
        return o.get(key).getAsString();
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
