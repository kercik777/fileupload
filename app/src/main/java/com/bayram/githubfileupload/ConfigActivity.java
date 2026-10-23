package com.bayram.githubfileupload;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.ImageViewCompat;
import android.content.res.ColorStateList;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

/**
 * Экран настроек GitHub с двумя переключаемыми режимами:
 * 1) «Через GitHub» — вход через OAuth Device Flow без ручного создания токена,
 *    список всех репозиториев аккаунта, переключение в 1 клик, создание нового
 *    публичного репозитория с README.md и полное удаление репозитория вместе с файлами.
 * 2) «Ручной режим» — быстрый выбор репозитория по одному лишь токену + классический
 *    ручной ввод владельца, репозитория и веток.
 */
public class ConfigActivity extends AppCompatActivity implements
        RepoListAdapter.Listener,
        GitHubDeviceAuthDialogFragment.Listener,
        CreateRepoDialogFragment.Listener,
        DeleteRepoDialogFragment.Listener {

    public static final String EXTRA_FORCE_MODE = "extra_force_mode";
    public static final String EXTRA_AUTO_START_OAUTH = "extra_auto_start_oauth";

    // Mode tabs
    private View tabOAuth;
    private View tabManual;
    private ImageView ivTabOAuth;
    private ImageView ivTabManual;
    private TextView tvTabOAuth;
    private TextView tvTabManual;

    // OAuth cards
    private View oauthLoginCard;
    private View oauthAccountCard;
    private ImageView ivOAuthAvatar;
    private TextView tvOAuthLogin;
    private TextView tvOAuthSub;

    // Manual cards & inputs
    private View manualTokenCard;
    private View manualFieldsCard;
    private TextInputEditText etToken;
    private TextInputEditText etOwner;
    private TextInputEditText etRepo;
    private TextInputEditText etBranch;
    private TextInputEditText etPreviewBranch;

    // Shared repositories list card
    private View reposCard;
    private ProgressBar progressRepos;
    private TextView tvReposEmpty;
    private RecyclerView recyclerRepos;

    private TextView tvStatus;

    private final GitHubRepoManager repoChecker = new GitHubRepoManager();
    private final GitHubAccountManager accountManager = new GitHubAccountManager();
    private final List<GitHubAccountManager.UserRepo> repoItems = new ArrayList<>();
    private RepoListAdapter repoAdapter;
    private GitHubConfig cfg;
    private int loadGeneration = 0;
    private boolean busyRepoAction = false;

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

        tabOAuth = findViewById(R.id.tabOAuth);
        tabManual = findViewById(R.id.tabManual);
        ivTabOAuth = findViewById(R.id.ivTabOAuth);
        ivTabManual = findViewById(R.id.ivTabManual);
        tvTabOAuth = findViewById(R.id.tvTabOAuth);
        tvTabManual = findViewById(R.id.tvTabManual);

        oauthLoginCard = findViewById(R.id.oauthLoginCard);
        oauthAccountCard = findViewById(R.id.oauthAccountCard);
        ivOAuthAvatar = findViewById(R.id.ivOAuthAvatar);
        ivOAuthAvatar.setClipToOutline(true);
        tvOAuthLogin = findViewById(R.id.tvOAuthLogin);
        tvOAuthSub = findViewById(R.id.tvOAuthSub);

        manualTokenCard = findViewById(R.id.manualTokenCard);
        manualFieldsCard = findViewById(R.id.manualFieldsCard);
        etToken = findViewById(R.id.etToken);
        etOwner = findViewById(R.id.etOwner);
        etRepo = findViewById(R.id.etRepo);
        etBranch = findViewById(R.id.etBranch);
        etPreviewBranch = findViewById(R.id.etPreviewBranch);

        reposCard = findViewById(R.id.reposCard);
        progressRepos = findViewById(R.id.progressRepos);
        tvReposEmpty = findViewById(R.id.tvReposEmpty);
        recyclerRepos = findViewById(R.id.recyclerRepos);

        tvStatus = findViewById(R.id.tvConfigStatus);

        Button btnOAuthLogin = findViewById(R.id.btnOAuthLogin);
        MaterialButton btnOAuthLogout = findViewById(R.id.btnOAuthLogout);
        MaterialButton btnLoadManualRepos = findViewById(R.id.btnLoadManualRepos);
        MaterialButton btnRefreshRepos = findViewById(R.id.btnRefreshRepos);
        MaterialButton btnCreateRepo = findViewById(R.id.btnCreateRepo);
        Button btnSave = findViewById(R.id.btnSave);
        MaterialButton btnCheck = findViewById(R.id.btnCheck);

        recyclerRepos.setLayoutManager(new LinearLayoutManager(this));
        repoAdapter = new RepoListAdapter(this, repoItems, this);
        recyclerRepos.setAdapter(repoAdapter);

        cfg = Prefs.getConfig(this);

        if (savedInstanceState == null && getIntent() != null) {
            String forced = getIntent().getStringExtra(EXTRA_FORCE_MODE);
            if (GitHubConfig.MODE_OAUTH.equalsIgnoreCase(forced)
                    || GitHubConfig.MODE_MANUAL.equalsIgnoreCase(forced)) {
                cfg.authMode = forced;
                cfg.syncActiveFromMode();
                Prefs.saveConfig(this, cfg);
            }
        }

        populateManualInputsFromConfig();

        tabOAuth.setOnClickListener(v -> switchMode(GitHubConfig.MODE_OAUTH));
        tabManual.setOnClickListener(v -> switchMode(GitHubConfig.MODE_MANUAL));

        btnOAuthLogin.setOnClickListener(v -> openDeviceAuthDialog());
        btnOAuthLogout.setOnClickListener(v -> logoutOAuth());
        btnLoadManualRepos.setOnClickListener(v -> onLoadManualReposClicked());
        btnRefreshRepos.setOnClickListener(v -> refreshReposForCurrentMode());
        btnCreateRepo.setOnClickListener(v -> openCreateRepoDialog());
        btnSave.setOnClickListener(v -> saveManual());
        btnCheck.setOnClickListener(v -> checkManual());

        renderModeUi(true);

        if (savedInstanceState == null && getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_AUTO_START_OAUTH, false)
                && cfg.isOAuthMode()
                && !cfg.isOAuthLoggedIn()) {
            openDeviceAuthDialog();
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void switchMode(String targetMode) {
        if (targetMode.equalsIgnoreCase(cfg.authMode)) {
            return;
        }
        // Если уходим с ручной вкладки — запомним введённые поля, чтобы не пропали
        if (!cfg.isOAuthMode()) {
            captureManualDraftIntoConfig();
        }
        cfg.authMode = targetMode;
        cfg.syncActiveFromMode();
        Prefs.saveConfig(this, cfg);

        repoItems.clear();
        repoAdapter.notifyDataSetChanged();
        setStatus("", false);
        renderModeUi(true);
    }

    private void renderModeUi(boolean autoLoadRepos) {
        boolean oauth = cfg.isOAuthMode();

        int colorOnPrimary = getResources().getColor(R.color.md_on_primary);
        int colorInactive = getResources().getColor(R.color.md_on_surface_variant);

        tabOAuth.setBackgroundResource(oauth ? R.drawable.bg_tab_selected : R.drawable.bg_tab_unselected);
        tvTabOAuth.setTextColor(oauth ? colorOnPrimary : colorInactive);
        ImageViewCompat.setImageTintList(ivTabOAuth, ColorStateList.valueOf(oauth ? colorOnPrimary : colorInactive));

        tabManual.setBackgroundResource(!oauth ? R.drawable.bg_tab_selected : R.drawable.bg_tab_unselected);
        tvTabManual.setTextColor(!oauth ? colorOnPrimary : colorInactive);
        ImageViewCompat.setImageTintList(ivTabManual, ColorStateList.valueOf(!oauth ? colorOnPrimary : colorInactive));

        repoAdapter.setActiveRepo(cfg.owner, cfg.repo);

        if (oauth) {
            manualTokenCard.setVisibility(View.GONE);
            manualFieldsCard.setVisibility(View.GONE);

            if (cfg.isOAuthLoggedIn()) {
                oauthLoginCard.setVisibility(View.GONE);
                oauthAccountCard.setVisibility(View.VISIBLE);
                reposCard.setVisibility(View.VISIBLE);

                String title = "@" + cfg.oauthOwner;
                if (cfg.oauthName != null && !cfg.oauthName.trim().isEmpty()
                        && !cfg.oauthName.equalsIgnoreCase(cfg.oauthOwner)) {
                    title = cfg.oauthName.trim() + " (@" + cfg.oauthOwner + ")";
                }
                tvOAuthLogin.setText(title);
                updateOAuthSubtitle();

                if (cfg.oauthAvatarUrl != null && !cfg.oauthAvatarUrl.isEmpty()) {
                    ImageLoader.load(this, ivOAuthAvatar, cfg.oauthAvatarUrl, R.drawable.ic_account);
                } else {
                    ivOAuthAvatar.setImageResource(R.drawable.ic_account);
                }

                if (autoLoadRepos) {
                    loadReposWithToken(cfg.oauthToken, true);
                }
            } else {
                oauthLoginCard.setVisibility(View.VISIBLE);
                oauthAccountCard.setVisibility(View.GONE);
                reposCard.setVisibility(View.GONE);
            }
        } else {
            oauthLoginCard.setVisibility(View.GONE);
            oauthAccountCard.setVisibility(View.GONE);
            manualTokenCard.setVisibility(View.VISIBLE);
            manualFieldsCard.setVisibility(View.VISIBLE);

            populateManualInputsFromConfig();

            String manualTok = textOf(etToken);
            if (!manualTok.isEmpty() && autoLoadRepos) {
                reposCard.setVisibility(View.VISIBLE);
                loadReposWithToken(manualTok, false);
            } else if (repoItems.isEmpty()) {
                reposCard.setVisibility(View.GONE);
            }
        }
    }

    private void updateOAuthSubtitle() {
        if (cfg.oauthRepo != null && !cfg.oauthRepo.trim().isEmpty()) {
            tvOAuthSub.setText(getString(R.string.repo_selected_toast,
                    cfg.oauthOwner + "/" + cfg.oauthRepo));
        } else {
            tvOAuthSub.setText(R.string.oauth_logged_in_badge);
        }
    }

    private void populateManualInputsFromConfig() {
        etToken.setText(cfg.manualToken == null ? "" : cfg.manualToken);
        etOwner.setText(cfg.manualOwner == null ? "" : cfg.manualOwner);
        etRepo.setText(cfg.manualRepo == null ? "" : cfg.manualRepo);
        etBranch.setText(cfg.manualBranch == null || cfg.manualBranch.isEmpty() ? "main" : cfg.manualBranch);
        etPreviewBranch.setText(cfg.manualPreviewBranch == null || cfg.manualPreviewBranch.isEmpty()
                ? "files" : cfg.manualPreviewBranch);
    }

    private void captureManualDraftIntoConfig() {
        ParsedOwnerRepo parsed = parseOwnerAndRepo(textOf(etOwner), textOf(etRepo));
        cfg.manualToken = textOf(etToken);
        cfg.manualOwner = parsed.owner;
        cfg.manualRepo = parsed.repo;
        String br = textOf(etBranch);
        String pbr = textOf(etPreviewBranch);
        cfg.manualBranch = br.isEmpty() ? "main" : br;
        cfg.manualPreviewBranch = pbr.isEmpty() ? "files" : pbr;
    }

    private void openDeviceAuthDialog() {
        new GitHubDeviceAuthDialogFragment()
                .show(getSupportFragmentManager(), "github-device-auth");
    }

    @Override
    public void onGitHubAuthorized(GitHubAccountManager.AuthResult result) {
        if (result == null || result.accessToken == null || result.accessToken.isEmpty()) {
            return;
        }
        if (cfg.oauthOwner != null && !cfg.oauthOwner.isEmpty()
                && !cfg.oauthOwner.equalsIgnoreCase(result.user.login)) {
            cfg.oauthRepo = "";
        }
        cfg.authMode = GitHubConfig.MODE_OAUTH;
        cfg.oauthToken = result.accessToken;
        cfg.oauthOwner = result.user.login;
        cfg.oauthName = result.user.name;
        cfg.oauthAvatarUrl = result.user.avatarUrl;
        if (cfg.oauthRepo == null) {
            cfg.oauthRepo = "";
        }
        cfg.syncActiveFromMode();
        Prefs.saveConfig(this, cfg);

        Toast.makeText(this, getString(R.string.device_auth_success, cfg.oauthOwner),
                Toast.LENGTH_SHORT).show();
        renderModeUi(true);
    }

    private void logoutOAuth() {
        loadGeneration++;
        cfg.oauthToken = "";
        cfg.oauthOwner = "";
        cfg.oauthName = "";
        cfg.oauthAvatarUrl = "";
        cfg.oauthRepo = "";
        cfg.oauthBranch = "main";
        cfg.syncActiveFromMode();
        Prefs.saveConfig(this, cfg);

        repoItems.clear();
        repoAdapter.notifyDataSetChanged();
        setStatus(getString(R.string.oauth_logout_done), false);
        Toast.makeText(this, R.string.oauth_logout_done, Toast.LENGTH_SHORT).show();
        renderModeUi(false);
    }

    private void onLoadManualReposClicked() {
        String tok = textOf(etToken);
        if (tok.isEmpty()) {
            setStatus(getString(R.string.manual_enter_token_first), true);
            Toast.makeText(this, R.string.manual_enter_token_first, Toast.LENGTH_SHORT).show();
            return;
        }
        cfg.manualToken = tok;
        Prefs.saveConfig(this, cfg);
        reposCard.setVisibility(View.VISIBLE);
        loadReposWithToken(tok, false);
    }

    private void refreshReposForCurrentMode() {
        if (cfg.isOAuthMode()) {
            if (cfg.isOAuthLoggedIn()) {
                loadReposWithToken(cfg.oauthToken, true);
            }
        } else {
            onLoadManualReposClicked();
        }
    }

    private void loadReposWithToken(final String token, final boolean forOAuthMode) {
        final int gen = ++loadGeneration;
        progressRepos.setVisibility(View.VISIBLE);
        tvReposEmpty.setVisibility(View.GONE);

        accountManager.loadUserAndRepos(token,
                new GitHubAccountManager.Callback<GitHubAccountManager.TokenReposResult>() {
                    @Override
                    public void onSuccess(GitHubAccountManager.TokenReposResult res) {
                        if (isFinishing() || gen != loadGeneration) return;
                        progressRepos.setVisibility(View.GONE);

                        if (forOAuthMode) {
                            if (!res.user.login.isEmpty()) {
                                cfg.oauthOwner = res.user.login;
                                cfg.oauthName = res.user.name;
                                cfg.oauthAvatarUrl = res.user.avatarUrl;
                                cfg.syncActiveFromMode();
                                Prefs.saveConfig(ConfigActivity.this, cfg);
                            }
                        } else {
                            // В ручном режиме автоматически подставляем логин владельца токена, если он пуст
                            if (!res.user.login.isEmpty() && textOf(etOwner).isEmpty()) {
                                etOwner.setText(res.user.login);
                                cfg.manualOwner = res.user.login;
                                Prefs.saveConfig(ConfigActivity.this, cfg);
                            }
                        }

                        repoItems.clear();
                        repoItems.addAll(res.repos);
                        repoAdapter.setActiveRepo(cfg.owner, cfg.repo);

                        if (repoItems.isEmpty()) {
                            tvReposEmpty.setVisibility(View.VISIBLE);
                            tvReposEmpty.setText(forOAuthMode
                                    ? R.string.repos_empty_oauth : R.string.repos_empty_manual);
                        } else {
                            tvReposEmpty.setVisibility(View.GONE);
                        }
                    }

                    @Override
                    public void onError(String error) {
                        if (isFinishing() || gen != loadGeneration) return;
                        progressRepos.setVisibility(View.GONE);
                        if (repoItems.isEmpty()) {
                            tvReposEmpty.setVisibility(View.VISIBLE);
                            tvReposEmpty.setText(error == null
                                    ? getString(R.string.error_network) : error);
                        }
                        setStatus(error == null ? getString(R.string.error_network) : error, true);
                    }
                });
    }

    @Override
    public void onRepoSelected(GitHubAccountManager.UserRepo repo) {
        if (repo == null) {
            return;
        }
        if (cfg.isOAuthMode()) {
            cfg.oauthOwner = repo.owner;
            cfg.oauthRepo = repo.name;
            cfg.oauthBranch = repo.defaultBranch;
            cfg.syncActiveFromMode();
            Prefs.saveConfig(this, cfg);
            updateOAuthSubtitle();
        } else {
            String tok = textOf(etToken);
            if (!tok.isEmpty()) {
                cfg.manualToken = tok;
            }
            cfg.manualOwner = repo.owner;
            cfg.manualRepo = repo.name;
            cfg.manualBranch = repo.defaultBranch;
            String pbr = textOf(etPreviewBranch);
            cfg.manualPreviewBranch = pbr.isEmpty() ? "files" : pbr;

            etOwner.setText(repo.owner);
            etRepo.setText(repo.name);
            etBranch.setText(repo.defaultBranch);

            cfg.syncActiveFromMode();
            Prefs.saveConfig(this, cfg);
        }

        repoAdapter.setActiveRepo(cfg.owner, cfg.repo);
        String full = repo.owner + "/" + repo.name;
        String msg = getString(R.string.repo_selected_toast, full);
        if (repo.privateRepo) {
            msg += " · " + getString(R.string.error_private_repo);
        }
        setStatus(msg, repo.privateRepo);
        Toast.makeText(this, getString(R.string.repo_selected_toast, full), Toast.LENGTH_SHORT).show();
    }

    private void openCreateRepoDialog() {
        if (busyRepoAction) {
            return;
        }
        String tok = activeTokenForRepoActions();
        if (tok.isEmpty()) {
            Toast.makeText(this, R.string.manual_enter_token_first, Toast.LENGTH_SHORT).show();
            return;
        }
        new CreateRepoDialogFragment().show(getSupportFragmentManager(), "create-repo");
    }

    @Override
    public void onCreateRepoConfirmed(String repoName) {
        if (busyRepoAction) {
            return;
        }
        String tok = activeTokenForRepoActions();
        if (tok.isEmpty()) {
            Toast.makeText(this, R.string.manual_enter_token_first, Toast.LENGTH_SHORT).show();
            return;
        }
        busyRepoAction = true;
        progressRepos.setVisibility(View.VISIBLE);
        setStatus(getString(R.string.create_repo_creating), false);

        accountManager.createPublicRepo(tok, repoName,
                new GitHubAccountManager.Callback<GitHubAccountManager.UserRepo>() {
                    @Override
                    public void onSuccess(GitHubAccountManager.UserRepo created) {
                        busyRepoAction = false;
                        if (isFinishing()) return;
                        progressRepos.setVisibility(View.GONE);
                        tvReposEmpty.setVisibility(View.GONE);

                        // Ставим новый репозиторий на первое место и сразу выбираем его активным
                        for (int i = repoItems.size() - 1; i >= 0; i--) {
                            if (repoItems.get(i).name.equalsIgnoreCase(created.name)) {
                                repoItems.remove(i);
                            }
                        }
                        repoItems.add(0, created);
                        onRepoSelected(created);

                        String full = created.owner + "/" + created.name;
                        setStatus(getString(R.string.create_repo_success, full), false);
                        Toast.makeText(ConfigActivity.this,
                                getString(R.string.create_repo_success, full), Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(String error) {
                        busyRepoAction = false;
                        if (isFinishing()) return;
                        progressRepos.setVisibility(View.GONE);
                        String msg = (error == null || error.isEmpty())
                                ? getString(R.string.error_network) : error;
                        setStatus(msg, true);
                        Toast.makeText(ConfigActivity.this, msg, Toast.LENGTH_LONG).show();
                    }
                });
    }

    @Override
    public void onRepoDeleteRequested(GitHubAccountManager.UserRepo repo) {
        if (busyRepoAction || repo == null) {
            return;
        }
        DeleteRepoDialogFragment.newInstance(repo.owner, repo.name)
                .show(getSupportFragmentManager(), "delete-repo");
    }

    @Override
    public void onDeleteRepoConfirmed(final String owner, final String repo) {
        if (busyRepoAction) {
            return;
        }
        String tok = activeTokenForRepoActions();
        if (tok.isEmpty()) {
            Toast.makeText(this, R.string.manual_enter_token_first, Toast.LENGTH_SHORT).show();
            return;
        }
        busyRepoAction = true;
        progressRepos.setVisibility(View.VISIBLE);
        final String full = owner + "/" + repo;
        setStatus(getString(R.string.delete_repo_deleting, full), false);

        accountManager.deleteRepo(tok, owner, repo, new GitHubAccountManager.Callback<Boolean>() {
            @Override
            public void onSuccess(Boolean ok) {
                busyRepoAction = false;
                if (isFinishing()) return;
                progressRepos.setVisibility(View.GONE);

                // Очищаем локальный кэш истории удалённого репозитория
                Prefs.removeRepoHistory(ConfigActivity.this, owner, repo);

                for (int i = repoItems.size() - 1; i >= 0; i--) {
                    GitHubAccountManager.UserRepo r = repoItems.get(i);
                    if (r.name.equalsIgnoreCase(repo)
                            && (owner.isEmpty() || r.owner.equalsIgnoreCase(owner))) {
                        repoItems.remove(i);
                    }
                }

                // Если удалён активный репозиторий — снимаем выбор
                if (repo.equalsIgnoreCase(cfg.oauthRepo)
                        && (owner.isEmpty() || owner.equalsIgnoreCase(cfg.oauthOwner))) {
                    cfg.oauthRepo = "";
                }
                if (repo.equalsIgnoreCase(cfg.manualRepo)
                        && (owner.isEmpty() || owner.equalsIgnoreCase(cfg.manualOwner))) {
                    cfg.manualRepo = "";
                    if (!cfg.isOAuthMode()) {
                        etRepo.setText("");
                    }
                }
                cfg.syncActiveFromMode();
                Prefs.saveConfig(ConfigActivity.this, cfg);

                if (cfg.isOAuthMode()) {
                    updateOAuthSubtitle();
                }

                repoAdapter.setActiveRepo(cfg.owner, cfg.repo);
                if (repoItems.isEmpty()) {
                    tvReposEmpty.setVisibility(View.VISIBLE);
                    tvReposEmpty.setText(cfg.isOAuthMode()
                            ? R.string.repos_empty_oauth : R.string.repos_empty_manual);
                }

                String done = getString(R.string.delete_repo_success, full);
                setStatus(done, false);
                Toast.makeText(ConfigActivity.this, done, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String error) {
                busyRepoAction = false;
                if (isFinishing()) return;
                progressRepos.setVisibility(View.GONE);
                String msg = (error == null || error.isEmpty())
                        ? getString(R.string.error_network) : error;
                setStatus(msg, true);
                Toast.makeText(ConfigActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    private String activeTokenForRepoActions() {
        if (cfg.isOAuthMode()) {
            return cfg.oauthToken == null ? "" : cfg.oauthToken.trim();
        }
        return textOf(etToken);
    }

    private void saveManual() {
        captureManualDraftIntoConfig();
        cfg.authMode = GitHubConfig.MODE_MANUAL;
        cfg.syncActiveFromMode();

        if (!cfg.isValid()) {
            setStatus(getString(R.string.error_not_configured), true);
            return;
        }
        etOwner.setText(cfg.owner);
        etRepo.setText(cfg.repo);
        Prefs.saveConfig(this, cfg);
        repoAdapter.setActiveRepo(cfg.owner, cfg.repo);
        setStatus(getString(R.string.config_saved), false);
        Toast.makeText(this, R.string.config_saved, Toast.LENGTH_SHORT).show();
    }

    private void checkManual() {
        captureManualDraftIntoConfig();
        cfg.authMode = GitHubConfig.MODE_MANUAL;
        cfg.syncActiveFromMode();

        if (!cfg.isValid()) {
            setStatus(getString(R.string.error_not_configured), true);
            return;
        }
        setStatus(getString(R.string.config_checking), false);
        repoChecker.check(cfg, new GitHubRepoManager.Callback<GitHubRepoManager.RepoInfo>() {
            @Override
            public void onSuccess(GitHubRepoManager.RepoInfo info) {
                if (isFinishing()) return;
                String base = info.fullName.isEmpty() ? getString(R.string.config_connected) : info.fullName;
                if (info.privateRepo) {
                    base += " · " + getString(R.string.error_private_repo);
                }
                setStatus(base, info.privateRepo);
            }

            @Override
            public void onError(String error) {
                if (isFinishing()) return;
                setStatus(error == null ? getString(R.string.config_failed) : error, true);
            }
        });
    }

    private static final class ParsedOwnerRepo {
        final String owner;
        final String repo;

        ParsedOwnerRepo(String owner, String repo) {
            this.owner = owner;
            this.repo = repo;
        }
    }

    private static ParsedOwnerRepo parseOwnerAndRepo(String rawOwner, String rawRepo) {
        String owner = rawOwner == null ? "" : rawOwner.trim();
        String repo = rawRepo == null ? "" : rawRepo.trim();

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
        return new ParsedOwnerRepo(owner, repo);
    }

    private static String textOf(TextInputEditText et) {
        return (et == null || et.getText() == null) ? "" : et.getText().toString().trim();
    }

    private void setStatus(String text, boolean error) {
        tvStatus.setText(text == null ? "" : text);
        tvStatus.setTextColor(getResources().getColor(
                error ? R.color.md_error : R.color.md_primary));
    }
}
