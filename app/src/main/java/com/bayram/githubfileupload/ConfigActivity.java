package com.bayram.githubfileupload;

import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;

public class ConfigActivity extends AppCompatActivity {

    private TextInputEditText etOwner;
    private TextInputEditText etRepo;
    private TextInputEditText etToken;
    private TextInputEditText etBranch;
    private TextInputEditText etPreviewBranch;
    private TextView tvStatus;
    private Button btnSave;
    private Button btnCheck;

    private final GitHubRepoManager repos = new GitHubRepoManager();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_config);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        etOwner = findViewById(R.id.etOwner);
        etRepo = findViewById(R.id.etRepo);
        etToken = findViewById(R.id.etToken);
        etBranch = findViewById(R.id.etBranch);
        etPreviewBranch = findViewById(R.id.etPreviewBranch);
        tvStatus = findViewById(R.id.tvConfigStatus);
        btnSave = findViewById(R.id.btnSave);
        btnCheck = findViewById(R.id.btnCheck);

        GitHubConfig cfg = Prefs.getConfig(this);
        etOwner.setText(cfg.owner);
        etRepo.setText(cfg.repo);
        etToken.setText(cfg.token);
        etBranch.setText(cfg.branch);
        etPreviewBranch.setText(cfg.previewBranch);

        btnSave.setOnClickListener(v -> save());
        btnCheck.setOnClickListener(v -> check());
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private GitHubConfig currentConfig() {
        String owner = etOwner.getText() == null ? "" : etOwner.getText().toString().trim();
        String repo = etRepo.getText() == null ? "" : etRepo.getText().toString().trim();

        // Удобство: если пользователь вставил "https://github.com/owner/repo" или "owner/repo"
        if (repo.startsWith("https://github.com/") || repo.startsWith("http://github.com/")) {
            String tail = repo.substring(repo.indexOf("github.com/") + "github.com/".length());
            while (tail.endsWith("/")) tail = tail.substring(0, tail.length() - 1);
            if (tail.endsWith(".git")) tail = tail.substring(0, tail.length() - 4);
            String[] parts = tail.split("/");
            if (parts.length >= 2) {
                if (owner.isEmpty()) owner = parts[0];
                repo = parts[1];
            }
        } else if (repo.contains("/")) {
            String[] parts = repo.split("/");
            if (parts.length == 2) {
                if (owner.isEmpty()) owner = parts[0].trim();
                repo = parts[1].trim();
            }
        }
        if (repo.endsWith(".git")) {
            repo = repo.substring(0, repo.length() - 4);
        }

        return new GitHubConfig(
                owner,
                repo,
                etToken.getText() == null ? "" : etToken.getText().toString().trim(),
                etBranch.getText() == null || etBranch.getText().toString().trim().isEmpty()
                        ? "main" : etBranch.getText().toString().trim(),
                etPreviewBranch.getText() == null || etPreviewBranch.getText().toString().trim().isEmpty()
                        ? "files" : etPreviewBranch.getText().toString().trim());
    }

    private void save() {
        GitHubConfig cfg = currentConfig();
        if (!cfg.isValid()) {
            setStatus(getString(R.string.error_not_configured), true);
            return;
        }
        etOwner.setText(cfg.owner);
        etRepo.setText(cfg.repo);
        Prefs.saveConfig(this, cfg);
        setStatus(getString(R.string.config_saved), false);
        Toast.makeText(this, R.string.config_saved, Toast.LENGTH_SHORT).show();
    }

    private void check() {
        GitHubConfig cfg = currentConfig();
        if (!cfg.isValid()) {
            setStatus(getString(R.string.error_not_configured), true);
            return;
        }
        setStatus(getString(R.string.config_checking), false);
        repos.check(cfg, new GitHubRepoManager.Callback<GitHubRepoManager.RepoInfo>() {
            @Override
            public void onSuccess(GitHubRepoManager.RepoInfo info) {
                String base = info.fullName.isEmpty() ? getString(R.string.config_connected) : info.fullName;
                if (info.privateRepo) {
                    base += " · " + getString(R.string.error_private_repo);
                }
                setStatus(base, info.privateRepo);
            }

            @Override
            public void onError(String error) {
                setStatus(error == null ? getString(R.string.config_failed) : error, true);
            }
        });
    }

    private void setStatus(String text, boolean error) {
        tvStatus.setText(text);
        tvStatus.setTextColor(getResources().getColor(
                error ? R.color.md_error : R.color.md_primary));
    }
}
