package com.bayram.githubfileupload;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Онлайн-менеджер истории загрузок через GitHub API:
 * - Загружает все файлы из GitHub Releases и из ветки предпросмотра (files/) даже после
 *   переустановки приложения или на новом телефоне.
 * - Поддерживает раздельное удаление: только скачивание (Release), только просмотр (ветка files)
 *   или оба варианта сразу.
 */
public class GitHubHistoryManager {

    public static final int DELETE_DOWNLOAD_ONLY = 1;
    public static final int DELETE_PREVIEW_ONLY = 2;
    public static final int DELETE_ALL = 3;

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(String error);
    }

    private final OkHttpClient http;
    private final Handler ui;
    private final GitHubReleaseManager releases;
    private final GitHubRepoManager repos;

    public GitHubHistoryManager() {
        http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .build();
        ui = new Handler(Looper.getMainLooper());
        releases = new GitHubReleaseManager();
        repos = new GitHubRepoManager();
    }

    /** Загружает полную историю файлов напрямую из GitHub по API-ключу. */
    public void loadOnlineHistory(Context ctx, GitHubConfig cfg, Callback<List<UploadItem>> cb) {
        if (cfg == null || !cfg.isValid()) {
            post(cb, null, "Сначала укажите владельца, репозиторий и токен в настройках");
            return;
        }
        final Context appCtx = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                List<UploadItem> localCache = Prefs.getHistoryForRepo(appCtx, cfg);
                List<UploadItem> releaseItems = fetchReleaseAssets(cfg);
                List<UploadItem> previewItems = fetchPreviewBranchFiles(cfg, localCache);

                List<UploadItem> merged = mergeOnlineAndLocal(cfg, releaseItems, previewItems, localCache);
                Prefs.replaceRepoHistory(appCtx, cfg, merged);
                post(cb, merged, null);
            } catch (ApiException ae) {
                post(cb, null, ae.getMessage());
            } catch (Exception e) {
                String msg = e.getMessage();
                post(cb, null, (msg == null || msg.isEmpty()) ? "Ошибка сети при загрузке истории" : msg);
            }
        }).start();
    }

    /**
     * Удаление с выбором режима:
     * - DELETE_DOWNLOAD_ONLY: удаляет только из Releases, сохраняя ссылку на просмотр;
     * - DELETE_PREVIEW_ONLY: удаляет только из ветки files, сохраняя ссылку на скачивание;
     * - DELETE_ALL: удаляет всё и убирает запись из истории.
     */
    public void deleteByMode(Context ctx, GitHubConfig cfg, UploadItem item, int deleteMode,
                             Callback<Boolean> cb) {
        if (item == null) {
            post(cb, true, null);
            return;
        }
        if (deleteMode == DELETE_DOWNLOAD_ONLY) {
            deleteDownloadOnly(ctx, cfg, item, cb);
        } else if (deleteMode == DELETE_PREVIEW_ONLY) {
            deletePreviewOnly(ctx, cfg, item, cb);
        } else {
            deleteItem(ctx, cfg, item, cb);
        }
    }

    private void deleteDownloadOnly(Context ctx, GitHubConfig cfg, UploadItem item,
                                    Callback<Boolean> cb) {
        final Context appCtx = ctx.getApplicationContext();
        final UploadItem before = Prefs.snapshotOf(item);
        final boolean hasValidCfg = cfg != null && cfg.isValid();

        if (!hasValidCfg || !item.hasDownload()) {
            applyClearDownloadFields(item);
            persistAfterPartialDelete(appCtx, before, item);
            post(cb, true, null);
            return;
        }

        releases.deleteItemFromRelease(cfg, item, new GitHubReleaseManager.Callback<Boolean>() {
            @Override
            public void onSuccess(Boolean ok) {
                applyClearDownloadFields(item);
                persistAfterPartialDelete(appCtx, before, item);
                post(cb, true, null);
            }

            @Override
            public void onError(String error) {
                post(cb, null, error);
            }
        });
    }

    private void deletePreviewOnly(Context ctx, GitHubConfig cfg, UploadItem item,
                                   Callback<Boolean> cb) {
        final Context appCtx = ctx.getApplicationContext();
        final UploadItem before = Prefs.snapshotOf(item);
        final boolean hasValidCfg = cfg != null && cfg.isValid();
        final String repoPath = GitHubRepoManager.extractRepoPathFromItem(item);

        if (!hasValidCfg || repoPath.isEmpty()) {
            applyClearPreviewFields(item);
            persistAfterPartialDelete(appCtx, before, item);
            post(cb, true, null);
            return;
        }

        String branch = (item.previewBranch != null && !item.previewBranch.trim().isEmpty())
                ? item.previewBranch.trim() : cfg.previewBranch;
        repos.deleteFile(cfg, branch, repoPath, item.repoFileSha, new GitHubRepoManager.Callback<Boolean>() {
            @Override
            public void onSuccess(Boolean ok) {
                applyClearPreviewFields(item);
                persistAfterPartialDelete(appCtx, before, item);
                post(cb, true, null);
            }

            @Override
            public void onError(String error) {
                post(cb, null, error);
            }
        });
    }

    private static void applyClearDownloadFields(UploadItem item) {
        item.downloadUrl = "";
        item.releaseId = 0L;
        item.releaseAssetId = 0L;
        item.releaseAssetName = "";
        item.tag = "";
        item.mode = item.hasPreview() ? "preview" : "download";
    }

    private static void applyClearPreviewFields(UploadItem item) {
        item.previewUrl = "";
        item.previewRawUrl = "";
        item.pageUrl = "";
        item.previewPath = "";
        item.repoFileSha = "";
        item.mode = item.hasDownload() ? "download" : "preview";
    }

    private static void persistAfterPartialDelete(Context appCtx, UploadItem before, UploadItem current) {
        if (current.hasDownload() || current.hasPreview()) {
            Prefs.updateHistoryItem(appCtx, before, current);
        } else {
            Prefs.removeHistoryItem(appCtx, before);
        }
    }

    /** Полное удаление любого файла (релиз, репозиторий или оба варианта). */
    public void deleteItem(Context ctx, GitHubConfig cfg, UploadItem item, Callback<Boolean> cb) {
        if (item == null) {
            post(cb, true, null);
            return;
        }
        final Context appCtx = ctx.getApplicationContext();
        final UploadItem before = Prefs.snapshotOf(item);
        final boolean hasValidCfg = cfg != null && cfg.isValid();
        final boolean needReleaseDelete = hasValidCfg && item.hasDownload();
        final String repoPath = GitHubRepoManager.extractRepoPathFromItem(item);
        final boolean needRepoDelete = hasValidCfg && !repoPath.isEmpty();

        if (!needReleaseDelete && !needRepoDelete) {
            Prefs.removeHistoryItem(appCtx, before);
            post(cb, true, null);
            return;
        }

        if (needReleaseDelete) {
            releases.deleteItemFromRelease(cfg, item, new GitHubReleaseManager.Callback<Boolean>() {
                @Override
                public void onSuccess(Boolean ok) {
                    if (needRepoDelete) {
                        deleteRepoPart(appCtx, cfg, item, before, repoPath, cb);
                    } else {
                        Prefs.removeHistoryItem(appCtx, before);
                        post(cb, true, null);
                    }
                }

                @Override
                public void onError(String error) {
                    post(cb, null, error);
                }
            });
        } else {
            deleteRepoPart(appCtx, cfg, item, before, repoPath, cb);
        }
    }

    private void deleteRepoPart(Context appCtx, GitHubConfig cfg, UploadItem item,
                                UploadItem before, String repoPath, Callback<Boolean> cb) {
        String branch = (item.previewBranch != null && !item.previewBranch.trim().isEmpty())
                ? item.previewBranch.trim() : cfg.previewBranch;
        repos.deleteFile(cfg, branch, repoPath, item.repoFileSha, new GitHubRepoManager.Callback<Boolean>() {
            @Override
            public void onSuccess(Boolean ok) {
                Prefs.removeHistoryItem(appCtx, before);
                post(cb, true, null);
            }

            @Override
            public void onError(String error) {
                post(cb, null, error);
            }
        });
    }

    private List<UploadItem> fetchReleaseAssets(GitHubConfig cfg) throws Exception {
        HttpUrl url = new HttpUrl.Builder()
                .scheme("https")
                .host("api.github.com")
                .addPathSegments("repos/" + cfg.owner + "/" + cfg.repo + "/releases")
                .addQueryParameter("per_page", "100")
                .build();

        Request req = auth(cfg).url(url).get().build();
        try (Response resp = http.newCall(req).execute()) {
            if (resp.code() == 401) {
                throw new ApiException("Неверный токен или нет доступа (HTTP 401)");
            }
            if (resp.code() == 404) {
                throw new ApiException("Репозиторий не найден или нет прав доступа (HTTP 404)");
            }
            if (!resp.isSuccessful()) {
                throw new ApiException("Ошибка GitHub API: HTTP " + resp.code());
            }
            String body = readBody(resp);
            JsonElement parsed = JsonParser.parseString(body);
            List<UploadItem> result = new ArrayList<>();
            if (!parsed.isJsonArray()) {
                return result;
            }
            JsonArray arr = parsed.getAsJsonArray();
            for (JsonElement relEl : arr) {
                if (!relEl.isJsonObject()) continue;
                JsonObject rel = relEl.getAsJsonObject();
                long releaseId = rel.has("id") ? rel.get("id").getAsLong() : 0L;
                String tag = str(rel, "tag_name");
                String relPublished = str(rel, "published_at");
                String relCreated = str(rel, "created_at");

                if (!rel.has("assets") || !rel.get("assets").isJsonArray()) {
                    continue;
                }
                JsonArray assets = rel.getAsJsonArray("assets");
                for (JsonElement assetEl : assets) {
                    if (!assetEl.isJsonObject()) continue;
                    JsonObject asset = assetEl.getAsJsonObject();
                    String dlUrl = str(asset, "browser_download_url");
                    if (dlUrl.isEmpty()) continue;

                    String assetName = str(asset, "name");
                    long assetId = asset.has("id") ? asset.get("id").getAsLong() : 0L;
                    long size = asset.has("size") ? asset.get("size").getAsLong() : 0L;
                    String contentType = str(asset, "content_type");
                    long timestamp = parseIsoTime(
                            str(asset, "updated_at"),
                            str(asset, "created_at"),
                            relPublished,
                            relCreated);

                    UploadItem it = new UploadItem();
                    it.owner = cfg.owner;
                    it.repo = cfg.repo;
                    it.previewBranch = cfg.previewBranch;
                    it.name = assetName.isEmpty() ? "file" : assetName;
                    it.size = size;
                    it.mime = resolveMime(it.name, contentType);
                    it.tag = tag;
                    it.time = timestamp;
                    it.downloadUrl = dlUrl;
                    it.releaseId = releaseId;
                    it.releaseAssetId = assetId;
                    it.releaseAssetName = assetName;
                    it.mode = "download";
                    result.add(it);
                }
            }
            return result;
        }
    }

    private List<UploadItem> fetchPreviewBranchFiles(GitHubConfig cfg,
                                                     List<UploadItem> localCache) throws Exception {
        String activeBranch = (cfg.previewBranch == null || cfg.previewBranch.trim().isEmpty())
                ? "main" : cfg.previewBranch.trim();

        // Сначала проверяем текущую активную ветку (по умолчанию main), затем остальные ветки репозитория
        List<String> branchesToScan = new ArrayList<>();
        branchesToScan.add(activeBranch);
        addUniqueBranch(branchesToScan, cfg.branch);
        addUniqueBranch(branchesToScan, "main");
        addUniqueBranch(branchesToScan, "files");

        HttpUrl brListUrl = new HttpUrl.Builder()
                .scheme("https")
                .host("api.github.com")
                .addPathSegments("repos/" + cfg.owner + "/" + cfg.repo + "/branches")
                .addQueryParameter("per_page", "25")
                .build();
        try (Response brResp = http.newCall(auth(cfg).url(brListUrl).get().build()).execute()) {
            if (brResp.isSuccessful()) {
                JsonElement el = JsonParser.parseString(readBody(brResp));
                if (el.isJsonArray()) {
                    for (JsonElement bEl : el.getAsJsonArray()) {
                        if (bEl.isJsonObject()) {
                            addUniqueBranch(branchesToScan, str(bEl.getAsJsonObject(), "name"));
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // Собираем известные локальные пути и текущую папку на ветке, чтобы находить любые папки
        java.util.Set<String> knownLocalPaths = new java.util.HashSet<>();
        if (localCache != null) {
            for (UploadItem loc : localCache) {
                if (loc != null && loc.previewPath != null && !loc.previewPath.trim().isEmpty()) {
                    knownLocalPaths.add(loc.previewPath.trim());
                }
            }
        }
        String customFolder = GitHubRepoManager.normalizeFolderPath(cfg.lastBranchFolder);

        List<UploadItem> result = new ArrayList<>();
        java.util.Set<String> seenPathSha = new java.util.HashSet<>();

        for (int bIdx = 0; bIdx < branchesToScan.size() && bIdx < 15; bIdx++) {
            String branch = branchesToScan.get(bIdx);
            HttpUrl url = new HttpUrl.Builder()
                    .scheme("https")
                    .host("api.github.com")
                    .addPathSegments("repos/" + cfg.owner + "/" + cfg.repo + "/git/trees/" + branch)
                    .addQueryParameter("recursive", "1")
                    .build();

            Request req = auth(cfg).url(url).get().build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    continue;
                }
                String body = readBody(resp);
                JsonElement parsed = JsonParser.parseString(body);
                if (!parsed.isJsonObject()) {
                    continue;
                }
                JsonObject treeRoot = parsed.getAsJsonObject();
                if (!treeRoot.has("tree") || !treeRoot.get("tree").isJsonArray()) {
                    continue;
                }
                JsonArray arr = treeRoot.getAsJsonArray("tree");
                for (JsonElement el : arr) {
                    if (!el.isJsonObject()) continue;
                    JsonObject f = el.getAsJsonObject();
                    if (!"blob".equalsIgnoreCase(str(f, "type"))) {
                        continue;
                    }
                    String path = str(f, "path");
                    if (path.isEmpty()) continue;

                    int slash = path.lastIndexOf('/');
                    String rawName = slash >= 0 ? path.substring(slash + 1) : path;
                    if (isSystemRepoFile(rawName)) {
                        continue;
                    }

                    long stampTime = FileUtils.parseTimestampPrefix(rawName);
                    boolean inFilesDir = path.startsWith("files/");
                    boolean inCustomDir = !customFolder.isEmpty() && path.startsWith(customFolder + "/");
                    boolean inLocal = knownLocalPaths.contains(path);

                    // Включаем все файлы приложения (с префиксом времени в любой папке/корне),
                    // а также любые файлы внутри папки files/ или выбранной пользователем папки
                    if (stampTime <= 0L && !inFilesDir && !inCustomDir && !inLocal) {
                        continue;
                    }

                    String sha = str(f, "sha");
                    String dedupeKey = path + "|" + sha;
                    if (!seenPathSha.add(dedupeKey)) {
                        continue;
                    }

                    String displayName = FileUtils.stripTimestampPrefix(rawName);
                    long size = f.has("size") && !f.get("size").isJsonNull()
                            ? f.get("size").getAsLong() : 0L;

                    String enc = FileUtils.encodePath(path);
                    UploadItem it = new UploadItem();
                    it.owner = cfg.owner;
                    it.repo = cfg.repo;
                    it.previewBranch = branch;
                    it.name = displayName.isEmpty() ? rawName : displayName;
                    it.size = size;
                    it.mime = resolveMime(it.name, null);
                    it.time = stampTime;
                    it.previewPath = path;
                    it.repoFileSha = sha;
                    it.previewUrl = "https://cdn.jsdelivr.net/gh/" + cfg.owner + "/" + cfg.repo
                            + "@" + branch + "/" + enc;
                    it.previewRawUrl = "https://raw.githubusercontent.com/" + cfg.owner + "/" + cfg.repo
                            + "/" + branch + "/" + enc;
                    it.pageUrl = "https://github.com/" + cfg.owner + "/" + cfg.repo
                            + "/blob/" + branch + "/" + enc;
                    it.mode = "preview";
                    result.add(it);
                }
            }
        }
        return result;
    }

    private static boolean isSystemRepoFile(String rawName) {
        if (rawName == null || rawName.isEmpty()) {
            return true;
        }
        if (FileUtils.parseTimestampPrefix(rawName) > 0L) {
            return false;
        }
        String lower = rawName.toLowerCase(Locale.ROOT);
        return "readme.md".equals(lower)
                || "readme".equals(lower)
                || "license".equals(lower)
                || "license.md".equals(lower)
                || ".gitignore".equals(lower)
                || ".gitattributes".equals(lower)
                || ".gitkeep".equals(lower);
    }

    private static void addUniqueBranch(List<String> target, String candidate) {
        if (candidate == null || candidate.trim().isEmpty()) {
            return;
        }
        String c = candidate.trim();
        for (String existing : target) {
            if (existing.equalsIgnoreCase(c)) {
                return;
            }
        }
        target.add(c);
    }

    private static List<UploadItem> mergeOnlineAndLocal(
            GitHubConfig cfg,
            List<UploadItem> releaseItems,
            List<UploadItem> previewItems,
            List<UploadItem> localCache) {

        boolean[] usedPreview = new boolean[previewItems.size()];
        List<UploadItem> combined = new ArrayList<>();

        for (UploadItem rel : releaseItems) {
            int matchedIdx = -1;

            for (UploadItem loc : localCache) {
                if (loc != null && loc.hasDownload() && loc.hasPreview()
                        && rel.downloadUrl.equals(loc.downloadUrl)) {
                    for (int i = 0; i < previewItems.size(); i++) {
                        if (usedPreview[i]) continue;
                        UploadItem p = previewItems.get(i);
                        if ((loc.previewPath != null && loc.previewPath.equals(p.previewPath))
                                || loc.bestPreviewUrl().equals(p.bestPreviewUrl())) {
                            matchedIdx = i;
                            break;
                        }
                    }
                    break;
                }
            }

            if (matchedIdx < 0) {
                String relClean = FileUtils.sanitize(rel.name);
                for (int i = 0; i < previewItems.size(); i++) {
                    if (usedPreview[i]) continue;
                    UploadItem p = previewItems.get(i);
                    String prevClean = FileUtils.sanitize(p.name);
                    boolean sameNameAndSize = relClean.equalsIgnoreCase(prevClean)
                            && rel.size > 0 && rel.size == p.size;
                    boolean closeTime = (rel.time <= 0 || p.time <= 0)
                            || Math.abs(rel.time - p.time) <= 15L * 60L * 1000L;
                    if (sameNameAndSize && closeTime) {
                        matchedIdx = i;
                        break;
                    }
                }
            }

            if (matchedIdx >= 0) {
                usedPreview[matchedIdx] = true;
                UploadItem p = previewItems.get(matchedIdx);
                rel.previewUrl = p.previewUrl;
                rel.previewRawUrl = p.previewRawUrl;
                rel.pageUrl = p.pageUrl;
                rel.previewPath = p.previewPath;
                rel.previewBranch = p.previewBranch;
                rel.repoFileSha = p.repoFileSha;
                if (rel.time <= 0 && p.time > 0) {
                    rel.time = p.time;
                }
                rel.mode = "both";
            }
            combined.add(rel);
        }

        for (int i = 0; i < previewItems.size(); i++) {
            if (!usedPreview[i]) {
                combined.add(previewItems.get(i));
            }
        }

        long seqId = System.currentTimeMillis();
        for (UploadItem item : combined) {
            for (UploadItem loc : localCache) {
                if (loc != null && item.sameEntry(loc)) {
                    if (loc.sourceUri != null && !loc.sourceUri.isEmpty()) {
                        item.sourceUri = loc.sourceUri;
                    }
                    if (item.time <= 0 && loc.time > 0) {
                        item.time = loc.time;
                    }
                    if (loc.id > 0) {
                        item.id = loc.id;
                    }
                    break;
                }
            }
            if (item.id == 0) {
                String key = cfg.owner + "/" + cfg.repo + "|" + item.downloadUrl + "|" + item.previewPath;
                item.id = Math.abs((long) key.hashCode()) + (++seqId % 100000L);
            }
        }

        Collections.sort(combined, (a, b) -> {
            int cmp = Long.compare(b.time, a.time);
            if (cmp != 0) return cmp;
            return b.name.compareToIgnoreCase(a.name);
        });

        return combined;
    }

    private static String resolveMime(String fileName, String apiContentType) {
        if (apiContentType != null && !apiContentType.isEmpty()
                && !"application/octet-stream".equalsIgnoreCase(apiContentType)
                && !"binary/octet-stream".equalsIgnoreCase(apiContentType)) {
            return apiContentType;
        }
        String byName = FileUtils.mimeFromName(fileName);
        return (byName != null && !byName.isEmpty()) ? byName : "application/octet-stream";
    }

    private static long parseIsoTime(String... candidates) {
        if (candidates == null) {
            return 0L;
        }
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        for (String c : candidates) {
            if (c == null || c.isEmpty()) continue;
            try {
                Date d = sdf.parse(c);
                if (d != null) {
                    return d.getTime();
                }
            } catch (Exception ignored) {
            }
        }
        return 0L;
    }

    private Request.Builder auth(GitHubConfig cfg) {
        return new Request.Builder()
                .header("Authorization", "token " + cfg.token)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "GitHubUploader/2.2");
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

    private static final class ApiException extends Exception {
        ApiException(String message) {
            super(message);
        }
    }
}
