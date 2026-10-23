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
            return new GitHubConfig();
        }
        try {
            GitHubConfig cfg = GSON.fromJson(json, GitHubConfig.class);
            if (cfg == null) {
                return new GitHubConfig();
            }
            if (cfg.branch == null || cfg.branch.isEmpty()) {
                cfg.branch = "main";
            }
            if (cfg.previewBranch == null || cfg.previewBranch.isEmpty()) {
                cfg.previewBranch = "files";
            }
            return cfg;
        } catch (Exception e) {
            return new GitHubConfig();
        }
    }

    public static void saveConfig(Context ctx, GitHubConfig cfg) {
        sp(ctx).edit().putString(KEY_CONFIG, GSON.toJson(cfg)).apply();
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
}
