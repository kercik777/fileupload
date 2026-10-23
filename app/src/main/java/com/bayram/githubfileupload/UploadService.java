package com.bayram.githubfileupload;

import java.io.File;

/**
 * Оркестратор: по ошибкам и выбору пользователя выполняет
 * загрузку в релиз (скачивание) и/или в репозиторий (просмотр).
 */
public class UploadService {

    public static final int MODE_DOWNLOAD = 0;
    public static final int MODE_PREVIEW = 1;
    public static final int MODE_BOTH = 2;

    public interface Callback {
        void onProgress(int percent);
        void onSuccess(UploadItem item);
        void onError(String error);
    }

    private final GitHubReleaseManager releases = new GitHubReleaseManager();
    private final GitHubRepoManager repos = new GitHubRepoManager();

    public void upload(GitHubConfig cfg, File file, String fileName, String mime, String tag,
                       int mode, Callback cb) {
        if (!cfg.isValid()) {
            cb.onError("Сначала настройте репозиторий");
            return;
        }
        if (file == null || !file.exists() || !file.isFile()) {
            cb.onError("Файл не найден");
            return;
        }

        Run run = new Run();
        run.file = file;
        run.fileName = fileName;
        run.mime = mime;
        run.tag = (tag == null || tag.trim().isEmpty()) ? "latest" : tag.trim();

        boolean wantDownload = mode == MODE_DOWNLOAD || mode == MODE_BOTH;
        boolean wantPreview = mode == MODE_PREVIEW || mode == MODE_BOTH;

        if (wantDownload) {
            doDownload(cfg, run, wantPreview ? 0 : 5, wantPreview ? 50 : 100, cb, wantPreview);
        } else {
            doPreview(cfg, run, 5, 100, cb);
        }
    }

    private void doDownload(final GitHubConfig cfg, final Run run, final int startPct,
                            final int endPct, final Callback cb, final boolean thenPreview) {
        releases.upload(cfg, run.tag, run.file, run.fileName, run.mime, percent -> {
            int mapped = startPct + (int) ((percent / 100.0) * (endPct - startPct));
            cb.onProgress(mapped);
        }, url -> {
            run.downloadUrl = url;
            if (thenPreview) {
                doPreview(cfg, run, 50, 100, cb);
            } else {
                cb.onProgress(endPct);
                finish(cfg, run, cb);
            }
        }, err -> cb.onError(err));
    }

    private void doPreview(final GitHubConfig cfg, final Run run, final int startPct,
                           final int endPct, final Callback cb) {
        cb.onProgress(startPct);
        String relPath = "files/" + FileUtils.uniqueName(run.fileName);
        repos.upload(cfg, run.file, relPath, "Upload: " + run.fileName,
                new GitHubRepoManager.Callback<GitHubRepoManager.PreviewResult>() {
                    @Override
                    public void onSuccess(GitHubRepoManager.PreviewResult result) {
                        run.previewPath = result.path;
                        run.previewCdn = result.cdnUrl;
                        run.previewRaw = result.rawUrl;
                        run.pageUrl = result.pageUrl;
                        cb.onProgress(endPct);
                        finish(cfg, run, cb);
                    }

                    @Override
                    public void onError(String error) {
                        cb.onError(error);
                    }
                });
    }

    private void finish(GitHubConfig cfg, Run run, Callback cb) {
        UploadItem item = new UploadItem();
        item.id = System.currentTimeMillis();
        item.name = run.fileName;
        item.size = run.file.length();
        item.mime = run.mime;
        item.tag = run.tag;
        item.time = System.currentTimeMillis();
        item.owner = cfg.owner;
        item.repo = cfg.repo;
        item.previewBranch = cfg.previewBranch;
        item.downloadUrl = run.downloadUrl == null ? "" : run.downloadUrl;
        item.previewUrl = run.previewCdn == null ? "" : run.previewCdn;
        item.previewRawUrl = run.previewRaw == null ? "" : run.previewRaw;
        item.pageUrl = run.pageUrl == null ? "" : run.pageUrl;
        item.previewPath = run.previewPath == null ? "" : run.previewPath;

        boolean hasDownload = !item.downloadUrl.isEmpty();
        boolean hasPreview = !item.previewUrl.isEmpty();
        item.mode = hasDownload && hasPreview ? "both" : (hasPreview ? "preview" : "download");

        cb.onSuccess(item);
    }

    private static class Run {
        File file;
        String fileName;
        String mime;
        String tag;
        String downloadUrl;
        String previewCdn;
        String previewRaw;
        String previewPath;
        String pageUrl;
    }
}
