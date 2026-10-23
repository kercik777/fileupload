package com.bayram.githubfileupload;

/**
 * Настройки GitHub-репозитория, используемые при загрузке.
 */
public class GitHubConfig {

    public String owner = "";
    public String repo = "";
    public String token = "";
    public String branch = "main";
    public String previewBranch = "files";

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

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
