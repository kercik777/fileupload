package com.bayram.githubfileupload;

import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class GitHubReleaseManager {

    private static final String API_BASE = "https://api.github.com";
    private static final String UPLOAD_BASE = "https://uploads.github.com";
    private static final MediaType JSON_MT = MediaType.get("application/json; charset=utf-8");
    private static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");

    private final OkHttpClient http;
    private final Gson gson;
    private final Handler ui;

    public static class Config {
        public String owner;
        public String repo;
        public String token;
        public String branch;

        public Config(String owner, String repo, String token, String branch) {
            this.owner = owner;
            this.repo = repo;
            this.token = token;
            this.branch = branch;
        }
    }

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(String error);
    }

    public GitHubReleaseManager() {
    http = new OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.MINUTES)   // ← 2 минуты на подключение
            .readTimeout(2, TimeUnit.MINUTES)      // ← 2 минуты на чтение
            .writeTimeout(5, TimeUnit.MINUTES)     // ← 2 минуты на запись (важно для загрузки!)
            .build();
    gson = new Gson();
    ui = new Handler(Looper.getMainLooper());
}

    public void uploadFile(Config cfg, String tag, String filePath, Callback<String> cb) {
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            post(cb, null, "Файл не найден: " + filePath);
            return;
        }
        
        // Шаг 1: Получаем или создаём релиз
        getOrCreateRelease(cfg, tag, file.getName(), file, cb);
    }

    private void getOrCreateRelease(Config cfg, String tag, String fileName, File file, Callback<String> cb) {
        String url = API_BASE + "/repos/" + cfg.owner + "/" + cfg.repo + "/releases/tags/" + tag;
        Request request = auth(cfg).url(url).get().build();

        http.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onResponse(okhttp3.Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    String json = response.body().string();
                    JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                    long releaseId = obj.get("id").getAsLong();
                    // Шаг 2: Загружаем файл в релиз
                    uploadAsset(cfg, releaseId, fileName, file, cb);
                } else if (response.code() == 404) {
                    // Релиз не найден, создаём новый
                    createRelease(cfg, tag, fileName, file, cb);
                } else {
                    post(cb, null, "Ошибка получения релиза: HTTP " + response.code());
                }
                response.close();
            }

            @Override
            public void onFailure(okhttp3.Call call, IOException e) {
                post(cb, null, e.getMessage());
            }
        });
    }

    private void createRelease(Config cfg, String tag, String fileName, File file, Callback<String> cb) {
        String url = API_BASE + "/repos/" + cfg.owner + "/" + cfg.repo + "/releases";
        JsonObject body = new JsonObject();
        body.addProperty("tag_name", tag);
        body.addProperty("name", tag);
        body.addProperty("body", "Uploaded via app");
        body.addProperty("draft", false);
        body.addProperty("prerelease", false);
        body.addProperty("target_commitish", cfg.branch);

        Request request = auth(cfg)
                .url(url)
                .post(RequestBody.create(body.toString(), JSON_MT))
                .build();

        http.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onResponse(okhttp3.Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    String json = response.body().string();
                    JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                    long releaseId = obj.get("id").getAsLong();
                    // Загружаем файл
                    uploadAsset(cfg, releaseId, fileName, file, cb);
                } else {
                    String errorBody = response.body() != null ? response.body().string() : "";
                    post(cb, null, "Ошибка создания релиза: HTTP " + response.code() + " - " + errorBody);
                }
                response.close();
            }

            @Override
            public void onFailure(okhttp3.Call call, IOException e) {
                post(cb, null, e.getMessage());
            }
        });
    }

    private void uploadAsset(Config cfg, long releaseId, String fileName, File file, Callback<String> cb) {
        // ВАЖНО: используем uploads.github.com, а не api.github.com!
        String url = UPLOAD_BASE + "/repos/" + cfg.owner + "/" + cfg.repo + "/releases/" + releaseId + "/assets?name=" + fileName;
        
        RequestBody fileBody = RequestBody.create(file, OCTET_STREAM);
        Request request = auth(cfg)
                .url(url)
                .post(fileBody)
                .header("Content-Type", "application/octet-stream")
                .build();

        http.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onResponse(okhttp3.Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    String json = response.body().string();
                    JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                    String downloadUrl = obj.get("browser_download_url").getAsString();
                    post(cb, downloadUrl, null);
                } else {
                    String errorBody = response.body() != null ? response.body().string() : "";
                    post(cb, null, "Ошибка загрузки файла: HTTP " + response.code() + " - " + errorBody);
                }
                response.close();
            }

            @Override
            public void onFailure(okhttp3.Call call, IOException e) {
                post(cb, null, e.getMessage());
            }
        });
    }

    private Request.Builder auth(Config cfg) {
        return new Request.Builder()
                .header("Authorization", "token " + cfg.token)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "GitHubReleaseManager/1.0");
    }

    private <T> void post(Callback<T> cb, T result, String error) {
        ui.post(() -> {
            if (error != null) cb.onError(error);
            else cb.onSuccess(result);
        });
    }
    
    public void deleteRelease(Config cfg, String tag, Callback<Boolean> cb) {
    // Шаг 1: Получаем ID релиза
    String url = API_BASE + "/repos/" + cfg.owner + "/" + cfg.repo + "/releases/tags/" + tag;
    Request request = auth(cfg).url(url).get().build();

    http.newCall(request).enqueue(new okhttp3.Callback() {
        @Override
        public void onResponse(okhttp3.Call call, Response response) throws IOException {
            if (response.isSuccessful()) {
                String json = response.body().string();
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                long releaseId = obj.get("id").getAsLong();
                
                // Шаг 2: Удаляем релиз
                String deleteUrl = API_BASE + "/repos/" + cfg.owner + "/" + cfg.repo + "/releases/" + releaseId;
                Request deleteRequest = auth(cfg).url(deleteUrl).delete().build();
                
                http.newCall(deleteRequest).enqueue(new okhttp3.Callback() {
                    @Override
                    public void onResponse(okhttp3.Call call, Response deleteResponse) {
                        if (deleteResponse.isSuccessful()) {
                            // Шаг 3: Удаляем ТЕГ (это ключевая часть!)
                            deleteGitTag(cfg, tag, cb);
                        } else {
                            post(cb, null, "Ошибка удаления релиза: HTTP " + deleteResponse.code());
                        }
                        deleteResponse.close();
                    }
                    
                    @Override
                    public void onFailure(okhttp3.Call call, IOException e) {
                        post(cb, null, e.getMessage());
                    }
                });
            } else {
                post(cb, null, "Релиз не найден: " + tag);
            }
            response.close();
        }
        
        @Override
        public void onFailure(okhttp3.Call call, IOException e) {
            post(cb, null, e.getMessage());
        }
    });
}

// Новый метод: удаление Git-тега
private void deleteGitTag(Config cfg, String tag, Callback<Boolean> cb) {
    String url = API_BASE + "/repos/" + cfg.owner + "/" + cfg.repo + "/git/refs/tags/" + tag;
    Request request = auth(cfg).url(url).delete().build();
    
    http.newCall(request).enqueue(new okhttp3.Callback() {
        @Override
        public void onResponse(okhttp3.Call call, Response response) throws IOException {
            // 204 = успешное удаление, 404 = тега уже нет
            boolean success = response.code() == 204 || response.code() == 404;
            String error = success ? null : "Ошибка удаления тега: HTTP " + response.code();
            post(cb, success, error);
            response.close();
        }
        
        @Override
        public void onFailure(okhttp3.Call call, IOException e) {
            post(cb, null, e.getMessage());
        }
    });
}
}