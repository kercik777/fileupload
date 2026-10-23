package com.bayram.githubfileupload;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Настройки GitHub-репозитория, режим авторизации, постоянное хранение выбранной ветки
 * (по умолчанию "main"), а также последних введённых тега релиза и папки на ветке.
 */
public class GitHubConfig {

    public static final String MODE_OAUTH = "oauth";
    public static final String MODE_MANUAL = "manual";

    // Активные параметры, которые используют MainActivity, UploadService и HistoryActivity
    public String owner = "";
    public String repo = "";
    public String token = "";
    public String branch = "main";
    public String previewBranch = "main";

    // Выбранная вкладка в настройках: "oauth" или "manual"
    public String authMode = "";

    // Сохранённые данные входа через GitHub OAuth
    public String oauthToken = "";
    public String oauthOwner = "";
    public String oauthName = "";
    public String oauthAvatarUrl = "";
    public String oauthRepo = "";
    public String oauthBranch = "main";
    public String oauthPreviewBranch = "main";

    // Сохранённые данные ручного режима (чтобы при переключении режимов ничего не терялось)
    public String manualToken = "";
    public String manualOwner = "";
    public String manualRepo = "";
    public String manualBranch = "main";
    public String manualPreviewBranch = "main";

    // Последние введённые на главном экране тег релиза и название папки на ветке
    public String lastReleaseTag;
    public String lastBranchFolder;

    // Индивидуально сохранённая ветка загрузки для каждого "owner/repo"
    public Map<String, String> savedRepoBranches = new HashMap<>();

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

    /** Возвращает сохранённую ветку для указанного репозитория (по умолчанию "main"). */
    public String getSavedBranchForRepo(String repoOwner, String repoName, String fallback) {
        String key = repoKey(repoOwner, repoName);
        if (!key.isEmpty() && savedRepoBranches != null) {
            String saved = savedRepoBranches.get(key);
            if (notEmpty(saved)) {
                return saved.trim();
            }
        }
        return notEmpty(fallback) ? fallback.trim() : "main";
    }

    /** Сохраняет выбранную ветку для указанного репозитория и обновляет активные поля, если репо активен. */
    public void saveBranchForRepo(String repoOwner, String repoName, String chosenBranch) {
        if (!notEmpty(chosenBranch)) {
            return;
        }
        String cleanBranch = chosenBranch.trim();
        if (savedRepoBranches == null) {
            savedRepoBranches = new HashMap<>();
        }
        String key = repoKey(repoOwner, repoName);
        if (!key.isEmpty()) {
            savedRepoBranches.put(key, cleanBranch);
        }

        if (sameRepo(repoOwner, repoName, oauthOwner, oauthRepo)) {
            oauthPreviewBranch = cleanBranch;
        }
        if (sameRepo(repoOwner, repoName, manualOwner, manualRepo)) {
            manualPreviewBranch = cleanBranch;
        }
        if (sameRepo(repoOwner, repoName, owner, repo)) {
            previewBranch = cleanBranch;
        }
    }

    /** Применяет параметры выбранной вкладки как активные рабочие настройки приложения. */
    public void syncActiveFromMode() {
        if (savedRepoBranches == null) {
            savedRepoBranches = new HashMap<>();
        }
        if (isOAuthMode()) {
            this.token = safe(oauthToken);
            this.owner = safe(oauthOwner);
            this.repo = safe(oauthRepo);
            this.branch = notEmpty(oauthBranch) ? oauthBranch.trim() : "main";
            String fallback = notEmpty(oauthPreviewBranch) ? oauthPreviewBranch.trim() : "main";
            this.previewBranch = getSavedBranchForRepo(this.owner, this.repo, fallback);
            this.oauthPreviewBranch = this.previewBranch;
        } else {
            this.token = safe(manualToken);
            this.owner = safe(manualOwner);
            this.repo = safe(manualRepo);
            this.branch = notEmpty(manualBranch) ? manualBranch.trim() : "main";
            String fallback = notEmpty(manualPreviewBranch) ? manualPreviewBranch.trim() : "main";
            this.previewBranch = getSavedBranchForRepo(this.owner, this.repo, fallback);
            this.manualPreviewBranch = this.previewBranch;
        }
    }

    private static String repoKey(String o, String r) {
        if (!notEmpty(r)) {
            return "";
        }
        String ow = notEmpty(o) ? o.trim().toLowerCase(Locale.ROOT) : "_";
        return ow + "/" + r.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean sameRepo(String o1, String r1, String o2, String r2) {
        if (!notEmpty(r1) || !notEmpty(r2) || !r1.trim().equalsIgnoreCase(r2.trim())) {
            return false;
        }
        if (!notEmpty(o1) || !notEmpty(o2)) {
            return true;
        }
        return o1.trim().equalsIgnoreCase(o2.trim());
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
