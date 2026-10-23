package com.bayram.githubfileupload;

import java.util.Locale;

/**
 * Запись одной загрузки: файл, теги и полученные ссылки.
 * Поддерживает как локальную историю, так и онлайн-синхронизацию через GitHub API.
 */
public class UploadItem {

    public long id;
    public String name = "";
    public long size;
    public String mime = "";
    public String tag = "";
    public long time;

    public String owner = "";
    public String repo = "";
    public String previewBranch = "";

    /** Прямая ссылка на assets релиза GitHub (скачивание). */
    public String downloadUrl = "";
    /** Ссылка CDN jsDelivr (просмотр в браузере). */
    public String previewUrl = "";
    /** raw.githubusercontent (просмотр в браузере). */
    public String previewRawUrl = "";
    /** Страница файла в репозитории GitHub. */
    public String pageUrl = "";

    /** download | preview | both */
    public String mode = "download";
    /** content:// uri для локальной превьюшки (если загружено с этого устройства). */
    public String sourceUri = "";
    /** Относительный путь файла в репозитории (например files/20261023_120000_photo.jpg). */
    public String previewPath = "";

    /** Метаданные GitHub API для точного удаления любых файлов. */
    public long releaseId;
    public long releaseAssetId;
    public String releaseAssetName = "";
    public String repoFileSha = "";

    public boolean hasDownload() {
        return downloadUrl != null && !downloadUrl.isEmpty();
    }

    public boolean hasPreview() {
        return (previewUrl != null && !previewUrl.isEmpty())
                || (previewRawUrl != null && !previewRawUrl.isEmpty());
    }

    public String bestPreviewUrl() {
        if (previewUrl != null && !previewUrl.isEmpty()) {
            return previewUrl;
        }
        return previewRawUrl == null ? "" : previewRawUrl;
    }

    public String primaryUrl() {
        if (hasPreview()) {
            return bestPreviewUrl();
        }
        if (hasDownload()) {
            return downloadUrl;
        }
        return pageUrl == null ? "" : pageUrl;
    }

    public boolean isImage() {
        if (mime != null && mime.toLowerCase(Locale.ROOT).startsWith("image/")) {
            return true;
        }
        String inferred = FileUtils.mimeFromName(name);
        return inferred != null && inferred.startsWith("image/");
    }

    public boolean isVideo() {
        if (mime != null && mime.toLowerCase(Locale.ROOT).startsWith("video/")) {
            return true;
        }
        String inferred = FileUtils.mimeFromName(name);
        return inferred != null && inferred.startsWith("video/");
    }

    public String effectiveMime() {
        if (mime != null && !mime.isEmpty() && !"application/octet-stream".equalsIgnoreCase(mime)) {
            return mime;
        }
        String byName = FileUtils.mimeFromName(name);
        return (byName != null && !byName.isEmpty()) ? byName : "application/octet-stream";
    }

    public int iconRes() {
        String m = effectiveMime().toLowerCase(Locale.ROOT);
        if (m.startsWith("image/")) {
            return R.drawable.ic_image;
        }
        if (m.startsWith("video/")) {
            return R.drawable.ic_video;
        }
        if (m.startsWith("audio/")) {
            return R.drawable.ic_audio;
        }
        if (m.contains("pdf")) {
            return R.drawable.ic_text_snippet;
        }
        if (m.startsWith("text/") || m.contains("json") || m.contains("xml")) {
            return R.drawable.ic_text_snippet;
        }
        return R.drawable.ic_insert_drive_file;
    }

    public boolean sameEntry(UploadItem other) {
        if (other == null) {
            return false;
        }
        if (id != 0 && id == other.id) {
            return true;
        }
        if (hasDownload() && other.hasDownload() && downloadUrl.equals(other.downloadUrl)) {
            return true;
        }
        if (previewPath != null && !previewPath.isEmpty()
                && other.previewPath != null && previewPath.equals(other.previewPath)
                && safeEq(owner, other.owner) && safeEq(repo, other.repo)) {
            return true;
        }
        String p1 = bestPreviewUrl();
        String p2 = other.bestPreviewUrl();
        return !p1.isEmpty() && p1.equals(p2);
    }

    private static boolean safeEq(String a, String b) {
        String sa = a == null ? "" : a;
        String sb = b == null ? "" : b;
        return sa.equalsIgnoreCase(sb);
    }
}
