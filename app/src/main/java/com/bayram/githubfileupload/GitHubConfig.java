package com.bayram.githubfileupload;

/**
 * Настройки GitHub-репозитория и режим авторизации («oauth» через GitHub или «manual» по токену).
 */
public class GitHubConfig {

    public static final String MODE_OAUTH = "oauth";
    public static final String MODE_MANUAL = "manual";

    // Активные параметры, которые используют MainActivity, UploadService и HistoryActivity
    public String owner = "";
    public String repo = "";
    public String token = "";
    public String branch = "main";
    public String previewBranch = "files";

    // Выбранная вкладка в настройках: "oauth" или "manual"
    public String authMode = "";

    // Сохранённые данные входа через GitHub OAuth (Device Flow)
    public String oauthToken = "";
    public String oauthOwner = "";
    public String oauthName = "";
    public String oauthAvatarUrl = "";
    public String oauthRepo = "";
    public String oauthBranch = "main";

    // Сохранённые данные ручного режима (чтобы при переключении режимов ничего не терялось)
    public String manualToken = "";
    public String manualOwner = "";
    public String manualRepo = "";
    public String manualBranch = "main";
    public String manualPreviewBranch = "files";

    public GitHubConfig() {
    }

    public GitHubConfig(String owner, String repo, String token, String branch, String previewBranch) {
        this.owner = owner;
        this.repo = repo;
        this.token = token;
        this.branch = branch;
        this.previewBranch = previewBranch;
    }

    public boolean isValid() {
        return notEmpty(owner) && notEmpty(repo) && notEmpty(token);
    }

    public boolean isOAuthMode() {
        return !MODE_MANUAL.equalsIgnoreCase(authMode);
    }

    public boolean isOAuthLoggedIn() {
        return notEmpty(oauthToken) && notEmpty(oauthOwner);
    }

    /** Применяет параметры выбранной вкладки как активные рабочие настройки приложения. */
    public void syncActiveFromMode() {
        if (isOAuthMode()) {
            this.token = safe(oauthToken);
            this.owner = safe(oauthOwner);
            this.repo = safe(oauthRepo);
            this.branch = notEmpty(oauthBranch) ? oauthBranch.trim() : "main";
            if (!notEmpty(previewBranch)) {
                this.previewBranch = "files";
            }
        } else {
            this.token = safe(manualToken);
            this.owner = safe(manualOwner);
            this.repo = safe(manualRepo);
            this.branch = notEmpty(manualBranch) ? manualBranch.trim() : "main";
            this.previewBranch = notEmpty(manualPreviewBranch) ? manualPreviewBranch.trim() : "files";
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
