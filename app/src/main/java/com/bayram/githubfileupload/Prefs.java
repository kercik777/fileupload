package com.bayram.githubfileupload;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Постоянное хранение настроек GitHub и истории загрузок.
 * Токен хранится только локально на устройстве.
 */
public final class Prefs {

    private static final String FILE = "github_uploader";
    private static final String KEY_CONFIG = "config";
    private static final String KEY_HISTORY = "history";

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

    public static void saveHistory(Context ctx, List<UploadItem> list) {
        sp(ctx).edit().putString(KEY_HISTORY, GSON.toJson(list)).apply();
    }

    public static void addHistory(Context ctx, UploadItem item) {
        List<UploadItem> list = getHistory(ctx);
        list.add(0, item);
        if (list.size() > 50) {
            list = new ArrayList<>(list.subList(0, 50));
        }
        saveHistory(ctx, list);
    }

    public static void removeHistory(Context ctx, long id) {
        List<UploadItem> list = getHistory(ctx);
        List<UploadItem> next = new ArrayList<>();
        for (UploadItem it : list) {
            if (it.id != id) {
                next.add(it);
            }
        }
        saveHistory(ctx, next);
    }

    public static void clearHistory(Context ctx) {
        sp(ctx).edit().remove(KEY_HISTORY).apply();
    }
}
