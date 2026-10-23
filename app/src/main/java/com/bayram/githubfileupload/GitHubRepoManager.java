package com.bayram.githubfileupload;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.google.gson.Gson;
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
 * Класс, который делает файл доступным для просмотра в браузере.
 *
 * <p>GitHub Releases всегда отдают файл с Content-Disposition: attachment, поэтому
 * браузер скачивает его, а не показывает. Чтобы файл можно было видеть в браузере, мы
 * записываем его в сам репозиторий (на отдельный бранч) — и тогда работают прямые ссылки
 * raw.githubusercontent.com и CDN jsDelivr. Они отдают файл с правильным Content-Type
 * и без заголовка «скачивать», поэтому фото и видео открываются прямо в браузере.</p>
 */
public class GitHubRepoManager {

    private static final MediaType JSON_MT = MediaType.get("application/json; charset=utf-8");
    /** Лимит репозитория для одного файла. */
    private static final long MAX_FILE_BYTES = 90L * 1024 * 1024;

    private final OkHttpClient http;
    private final Gson gson;
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
        gson = new Gson();
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

    private void ensureBranch(GitHubConfig cfg, String defaultBranch, String relativePath,
                              File file, String message, Callback<PreviewResult> cb) {
        String preview = cfg.previewBranch;
        if (preview == null || preview.trim().isEmpty()) {
            preview = "files";
        }
        preview = preview.trim();

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
                        try {
                            JsonObject obj = JsonParser.parseString(readBody(response)).getAsJsonObject();
                            JsonObject commit = obj.has("commit") ? obj.getAsJsonObject("commit") : null;
                            if (commit != null && commit.has("html_url")) {
                                commitUrl = commit.get("html_url").getAsString();
                            }
                        } catch (Exception ignored) {
                        }
                        post(cb, buildResult(cfg, branch, relativePath, commitUrl), null);
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

    private PreviewResult buildResult(GitHubConfig cfg, String branch, String path, String commitUrl) {
        PreviewResult r = new PreviewResult();
        r.owner = cfg.owner;
        r.repo = cfg.repo;
        r.branch = branch;
        r.path = path;
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
                .header("User-Agent", "GitHubUploader/2.0");
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
