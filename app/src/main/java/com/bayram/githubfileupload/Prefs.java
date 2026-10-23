package com.bayram.githubfileupload;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Постоянное хранение настроек GitHub и локального кэша истории загрузок.
 * Токен хранится только локально на устройстве.
 */
public final class Prefs {

    private static final String FILE = "github_uploader";
    private static final String KEY_CONFIG = "config";
    private static final String KEY_HISTORY = "history";
    private static final String KEY_OAUTH_STATE = "oauth_pending_state";
    private static final String KEY_MIGRATED_DEFAULT_MAIN_V26 = "migrated_default_main_v26";
    private static final int MAX_HISTORY = 200;

    private static final Gson GSON = new Gson();

    private Prefs() {
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static GitHubConfig getConfig(Context ctx) {
        String json = sp(ctx).getString(KEY_CONFIG, null);
        if (json == null || json.isEmpty()) {
            sp(ctx).edit().putBoolean(KEY_MIGRATED_DEFAULT_MAIN_V26, true).apply();
            GitHubConfig fresh = new GitHubConfig();
            fresh.authMode = GitHubConfig.MODE_OAUTH;
            fresh.lastReleaseTag = "latest";
            fresh.lastBranchFolder = "files";
            return fresh;
        }
        try {
            GitHubConfig cfg = GSON.fromJson(json, GitHubConfig.class);
            if (cfg == null) {
                GitHubConfig fresh = new GitHubConfig();
                fresh.authMode = GitHubConfig.MODE_OAUTH;
                fresh.lastReleaseTag = "latest";
                fresh.lastBranchFolder = "files";
                return fresh;
            }
            if (cfg.branch == null || cfg.branch.isEmpty()) {
                cfg.branch = "main";
            }
            if (cfg.previewBranch == null || cfg.previewBranch.isEmpty()) {
                cfg.previewBranch = "main";
            }
            if (cfg.savedRepoBranches == null) {
                cfg.savedRepoBranches = new java.util.HashMap<>();
            }
            if (cfg.lastReleaseTag == null) {
                cfg.lastReleaseTag = "latest";
            }
            if (cfg.lastBranchFolder == null) {
                cfg.lastBranchFolder = "files";
            }

            // Однократная миграция до v2.6: меняем старую авто-подставленную ветку "files" на "main"
            if (!sp(ctx).getBoolean(KEY_MIGRATED_DEFAULT_MAIN_V26, false)) {
                if ("files".equalsIgnoreCase(cfg.previewBranch)) {
                    cfg.previewBranch = "main";
                }
                if (cfg.oauthPreviewBranch == null || cfg.oauthPreviewBranch.isEmpty()
                        || "files".equalsIgnoreCase(cfg.oauthPreviewBranch)) {
                    cfg.oauthPreviewBranch = "main";
                }
                if (cfg.manualPreviewBranch == null || cfg.manualPreviewBranch.isEmpty()
                        || "files".equalsIgnoreCase(cfg.manualPreviewBranch)) {
                    cfg.manualPreviewBranch = "main";
                }
                for (java.util.Map.Entry<String, String> entry : cfg.savedRepoBranches.entrySet()) {
                    if ("files".equalsIgnoreCase(entry.getValue())) {
                        entry.setValue("main");
                    }
                }
                sp(ctx).edit()
                        .putBoolean(KEY_MIGRATED_DEFAULT_MAIN_V26, true)
                        .putString(KEY_CONFIG, GSON.toJson(cfg))
                        .apply();
            }

            if (cfg.repo != null && !cfg.repo.trim().isEmpty()) {
                String existingSaved = cfg.getSavedBranchForRepo(cfg.owner, cfg.repo, "");
                if (existingSaved.isEmpty()) {
                    cfg.saveBranchForRepo(cfg.owner, cfg.repo, cfg.previewBranch);
                }
            }
            // Плавная миграция со старых версий, где был только ручной ввод
            if (cfg.authMode == null || cfg.authMode.isEmpty()) {
                if (cfg.token != null && !cfg.token.trim().isEmpty()) {
                    cfg.authMode = GitHubConfig.MODE_MANUAL;
                    if (cfg.manualToken == null || cfg.manualToken.isEmpty()) {
                        cfg.manualToken = cfg.token;
                    }
                    if (cfg.manualOwner == null || cfg.manualOwner.isEmpty()) {
                        cfg.manualOwner = cfg.owner == null ? "" : cfg.owner;
                    }
                    if (cfg.manualRepo == null || cfg.manualRepo.isEmpty()) {
                        cfg.manualRepo = cfg.repo == null ? "" : cfg.repo;
                    }
                    cfg.manualBranch = cfg.branch;
                    cfg.manualPreviewBranch = cfg.previewBranch;
                } else {
                    cfg.authMode = GitHubConfig.MODE_OAUTH;
                }
            }
            return cfg;
        } catch (Exception e) {
            GitHubConfig fresh = new GitHubConfig();
            fresh.authMode = GitHubConfig.MODE_OAUTH;
            return fresh;
        }
    }

    public static void saveConfig(Context ctx, GitHubConfig cfg) {
        sp(ctx).edit().putString(KEY_CONFIG, GSON.toJson(cfg)).apply();
    }

    /** Автоматически сохраняет последние введённые на главном экране тег релиза и папку на ветке. */
    public static void saveUploadInputs(Context ctx, String releaseTag, String branchFolder) {
        GitHubConfig cfg = getConfig(ctx);
        cfg.lastReleaseTag = releaseTag == null ? "" : releaseTag;
        cfg.lastBranchFolder = branchFolder == null ? "" : branchFolder;
        saveConfig(ctx, cfg);
    }

    public static List<UploadItem> getHistory(Context ctx) {
        String json = sp(ctx).getString(KEY_HISTORY, null);
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            Type type = new TypeToken<List<UploadItem>>() {
            }.getType();
            List<UploadItem> list = GSON.fromJson(json, type);
            return list == null ? new ArrayList<UploadItem>() : list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** Возвращает локальный кэш для указанного репозитория (либо все записи, если конфиг пуст). */
    public static List<UploadItem> getHistoryForRepo(Context ctx, GitHubConfig cfg) {
        List<UploadItem> all = getHistory(ctx);
        if (cfg == null || !cfg.isValid()) {
            return all;
        }
        List<UploadItem> filtered = new ArrayList<>();
        for (UploadItem it : all) {
            if (it == null) continue;
            if ((it.owner == null || it.owner.isEmpty())
                    || (it.owner.equalsIgnoreCase(cfg.owner) && it.repo.equalsIgnoreCase(cfg.repo))) {
                filtered.add(it);
            }
        }
        return filtered;
    }

    public static void saveHistory(Context ctx, List<UploadItem> list) {
        List<UploadItem> trimmed = list == null ? new ArrayList<UploadItem>() : list;
        if (trimmed.size() > MAX_HISTORY) {
            trimmed = new ArrayList<>(trimmed.subList(0, MAX_HISTORY));
        }
        sp(ctx).edit().putString(KEY_HISTORY, GSON.toJson(trimmed)).apply();
    }

    /** Сохраняет актуальный онлайн-список для данного репозитория, не затирая записи других репо. */
    public static void replaceRepoHistory(Context ctx, GitHubConfig cfg, List<UploadItem> repoItems) {
        if (cfg == null || !cfg.isValid()) {
            saveHistory(ctx, repoItems);
            return;
        }
        List<UploadItem> all = getHistory(ctx);
        List<UploadItem> merged = new ArrayList<>();
        if (repoItems != null) {
            merged.addAll(repoItems);
        }
        for (UploadItem existing : all) {
            if (existing == null) continue;
            boolean sameRepo = existing.owner != null && existing.repo != null
                    && existing.owner.equalsIgnoreCase(cfg.owner)
                    && existing.repo.equalsIgnoreCase(cfg.repo);
            if (!sameRepo) {
                merged.add(existing);
            }
        }
        saveHistory(ctx, merged);
    }

    /** Полностью удаляет локальный кэш истории удалённого репозитория. */
    public static void removeRepoHistory(Context ctx, String owner, String repo) {
        if (owner == null || repo == null) {
            return;
        }
        List<UploadItem> all = getHistory(ctx);
        List<UploadItem> kept = new ArrayList<>();
        for (UploadItem existing : all) {
            if (existing == null) continue;
            boolean sameRepo = existing.owner != null && existing.repo != null
                    && existing.owner.equalsIgnoreCase(owner)
                    && existing.repo.equalsIgnoreCase(repo);
            if (!sameRepo) {
                kept.add(existing);
            }
        }
        saveHistory(ctx, kept);
    }

    public static void addHistory(Context ctx, UploadItem item) {
        List<UploadItem> list = getHistory(ctx);
        List<UploadItem> next = new ArrayList<>();
        next.add(item);
        for (UploadItem existing : list) {
            if (existing != null && !existing.sameEntry(item)) {
                next.add(existing);
            }
        }
        saveHistory(ctx, next);
    }

    /** Обновляет запись в локальном кэше (например, после удаления только одного из двух вариантов). */
    public static void updateHistoryItem(Context ctx, UploadItem matchTarget, UploadItem updated) {
        if (matchTarget == null || updated == null) {
            return;
        }
        List<UploadItem> list = getHistory(ctx);
        List<UploadItem> next = new ArrayList<>();
        boolean replaced = false;
        for (UploadItem it : list) {
            if (it == null) continue;
            if (!replaced && it.sameEntry(matchTarget)) {
                next.add(updated);
                replaced = true;
            } else {
                next.add(it);
            }
        }
        if (!replaced) {
            next.add(0, updated);
        }
        saveHistory(ctx, next);
    }

    public static UploadItem snapshotOf(UploadItem src) {
        if (src == null) {
            return null;
        }
        return GSON.fromJson(GSON.toJson(src), UploadItem.class);
    }

    public static void removeHistory(Context ctx, long id) {
        List<UploadItem> list = getHistory(ctx);
        List<UploadItem> next = new ArrayList<>();
        for (UploadItem it : list) {
            if (it != null && it.id != id) {
                next.add(it);
            }
        }
        saveHistory(ctx, next);
    }

    public static void removeHistoryItem(Context ctx, UploadItem target) {
        if (target == null) {
            return;
        }
        List<UploadItem> list = getHistory(ctx);
        List<UploadItem> next = new ArrayList<>();
        for (UploadItem it : list) {
            if (it != null && !it.sameEntry(target)) {
                next.add(it);
            }
        }
        saveHistory(ctx, next);
    }

    public static void clearHistory(Context ctx) {
        sp(ctx).edit().remove(KEY_HISTORY).apply();
    }

    /** Сохраняет одноразовый параметр state на время перехода в браузер при входе через GitHub. */
    public static void savePendingOAuthState(Context ctx, String state) {
        sp(ctx).edit().putString(KEY_OAUTH_STATE, state == null ? "" : state).apply();
    }

    public static String getPendingOAuthState(Context ctx) {
        String s = sp(ctx).getString(KEY_OAUTH_STATE, "");
        return s == null ? "" : s;
    }

    public static void clearPendingOAuthState(Context ctx) {
        sp(ctx).edit().remove(KEY_OAUTH_STATE).apply();
    }
}
