package com.bayram.githubfileupload;

/**
 * Запись одной загрузки: файл, теги и полученные ссылки.
 * Хранится в истории и отображается в UI.
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
    /** content:// uri для превьюшки (может стать недоступным). */
    public String sourceUri = "";
    /** Относительный путь файла в репозитории. */
    public String previewPath = "";

    public boolean hasDownload() {
        return downloadUrl != null && !downloadUrl.isEmpty();
    }

    public boolean hasPreview() {
        return previewUrl != null && !previewUrl.isEmpty();
    }

    public boolean isImage() {
        return mime != null && mime.toLowerCase().startsWith("image/");
    }

    public boolean isVideo() {
        return mime != null && mime.toLowerCase().startsWith("video/");
    }

    public int iconRes() {
        String m = mime == null ? "" : mime.toLowerCase();
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
}
