package com.bayram.githubfileupload;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

/**
 * Диалог выбора, создания и ручного ввода ветки репозитория.
 * Вызывается как с главного экрана (MainActivity), так и из списка репозиториев в Настройках.
 * Любой выбор сразу сохраняется в Prefs для данного репозитория.
 */
public class BranchPickerDialogFragment extends DialogFragment implements BranchListAdapter.Listener {

    private static final String ARG_OWNER = "owner";
    private static final String ARG_REPO = "repo";
    private static final String ARG_TOKEN = "token";
    private static final String ARG_CURRENT_BRANCH = "current_branch";
    private static final String ARG_DEFAULT_BRANCH = "default_branch";

    public interface Listener {
        void onBranchChanged(String owner, String repo, String selectedBranch);
    }

    private Listener listener;
    private final GitHubAccountManager accountManager = new GitHubAccountManager();
    private final List<GitHubAccountManager.BranchInfo> branches = new ArrayList<>();
    private BranchListAdapter adapter;

    private String owner = "";
    private String repo = "";
    private String token = "";
    private String currentBranch = "main";
    private String defaultBranch = "main";
    private boolean busyAction = false;

    private TextInputEditText etBranchInput;
    private ProgressBar progressBranches;
    private TextView tvBranchesEmpty;

    public static BranchPickerDialogFragment newInstance(String owner, String repo, String token,
                                                         String currentBranch, String defaultBranch) {
        BranchPickerDialogFragment f = new BranchPickerDialogFragment();
        Bundle args = new Bundle();
        args.putString(ARG_OWNER, owner == null ? "" : owner.trim());
        args.putString(ARG_REPO, repo == null ? "" : repo.trim());
        args.putString(ARG_TOKEN, token == null ? "" : token.trim());
        args.putString(ARG_CURRENT_BRANCH, (currentBranch == null || currentBranch.trim().isEmpty())
                ? "main" : currentBranch.trim());
        args.putString(ARG_DEFAULT_BRANCH, (defaultBranch == null || defaultBranch.trim().isEmpty())
                ? "main" : defaultBranch.trim());
        f.setArguments(args);
        return f;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof Listener) {
            listener = (Listener) context;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.dialog_branch_picker, container, false);

        Bundle args = getArguments();
        if (args != null) {
            owner = args.getString(ARG_OWNER, "");
            repo = args.getString(ARG_REPO, "");
            token = args.getString(ARG_TOKEN, "");
            currentBranch = args.getString(ARG_CURRENT_BRANCH, "main");
            defaultBranch = args.getString(ARG_DEFAULT_BRANCH, "main");
        }

        TextView tvSubtitle = view.findViewById(R.id.tvBranchRepoSubtitle);
        etBranchInput = view.findViewById(R.id.etBranchNameInput);
        MaterialButton btnCreateNow = view.findViewById(R.id.btnCreateBranchNow);
        MaterialButton btnSaveManual = view.findViewById(R.id.btnSaveManualBranch);
        MaterialButton btnRefresh = view.findViewById(R.id.btnRefreshBranches);
        progressBranches = view.findViewById(R.id.progressBranches);
        tvBranchesEmpty = view.findViewById(R.id.tvBranchesEmpty);
        RecyclerView recycler = view.findViewById(R.id.recyclerBranches);
        View btnClose = view.findViewById(R.id.btnCloseBranchDialog);

        String fullRepo = owner.isEmpty() ? repo : (owner + "/" + repo);
        tvSubtitle.setText(getString(R.string.branch_dialog_subtitle, fullRepo));

        recycler.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new BranchListAdapter(requireContext(), branches, currentBranch, this);
        recycler.setAdapter(adapter);

        btnCreateNow.setOnClickListener(v -> onCreateBranchClicked());
        btnSaveManual.setOnClickListener(v -> onSaveManualBranchClicked());
        btnRefresh.setOnClickListener(v -> loadBranches());
        btnClose.setOnClickListener(v -> dismiss());

        loadBranches();
        return view;
    }

    private void loadBranches() {
        if (token.isEmpty() || owner.isEmpty() || repo.isEmpty()) {
            tvBranchesEmpty.setVisibility(View.VISIBLE);
            tvBranchesEmpty.setText(R.string.branch_empty);
            return;
        }
        progressBranches.setVisibility(View.VISIBLE);
        tvBranchesEmpty.setVisibility(View.GONE);

        accountManager.listRepoBranches(token, owner, repo,
                new GitHubAccountManager.Callback<GitHubAccountManager.BranchListResult>() {
                    @Override
                    public void onSuccess(GitHubAccountManager.BranchListResult result) {
                        if (!isAdded()) return;
                        progressBranches.setVisibility(View.GONE);
                        defaultBranch = result.defaultBranch;

                        branches.clear();
                        branches.addAll(result.branches);
                        adapter.setSelectedBranch(currentBranch);

                        if (branches.isEmpty()) {
                            tvBranchesEmpty.setVisibility(View.VISIBLE);
                            tvBranchesEmpty.setText(R.string.branch_empty);
                        } else {
                            tvBranchesEmpty.setVisibility(View.GONE);
                        }
                    }

                    @Override
                    public void onError(String error) {
                        if (!isAdded()) return;
                        progressBranches.setVisibility(View.GONE);
                        if (branches.isEmpty()) {
                            tvBranchesEmpty.setVisibility(View.VISIBLE);
                            tvBranchesEmpty.setText(error == null
                                    ? getString(R.string.error_network) : error);
                        }
                    }
                });
    }

    private void onCreateBranchClicked() {
        if (busyAction) {
            return;
        }
        String raw = etBranchInput.getText() == null ? "" : etBranchInput.getText().toString();
        final String clean = GitHubAccountManager.sanitizeBranchName(raw);
        if (clean.isEmpty()) {
            toast(getString(R.string.branch_invalid_name));
            return;
        }
        if (token.isEmpty() || owner.isEmpty() || repo.isEmpty()) {
            applyAndClose(clean, getString(R.string.branch_saved_toast, clean));
            return;
        }

        busyAction = true;
        progressBranches.setVisibility(View.VISIBLE);

        accountManager.createBranch(token, owner, repo, clean, defaultBranch,
                new GitHubAccountManager.Callback<GitHubAccountManager.BranchInfo>() {
                    @Override
                    public void onSuccess(GitHubAccountManager.BranchInfo created) {
                        busyAction = false;
                        if (!isAdded()) return;
                        progressBranches.setVisibility(View.GONE);
                        applyAndClose(created.name, getString(R.string.branch_created_toast, created.name));
                    }

                    @Override
                    public void onError(String error) {
                        busyAction = false;
                        if (!isAdded()) return;
                        progressBranches.setVisibility(View.GONE);
                        toast(error == null ? getString(R.string.error_network) : error);
                    }
                });
    }

    private void onSaveManualBranchClicked() {
        String raw = etBranchInput.getText() == null ? "" : etBranchInput.getText().toString();
        String clean = GitHubAccountManager.sanitizeBranchName(raw);
        if (clean.isEmpty()) {
            toast(getString(R.string.branch_invalid_name));
            return;
        }
        applyAndClose(clean, getString(R.string.branch_saved_toast, clean));
    }

    @Override
    public void onBranchClicked(GitHubAccountManager.BranchInfo branch) {
        if (branch == null || branch.name.isEmpty()) {
            return;
        }
        applyAndClose(branch.name, getString(R.string.branch_saved_toast, branch.name));
    }

    @Override
    public void onBranchDeleteClicked(final GitHubAccountManager.BranchInfo branch) {
        if (busyAction || branch == null) {
            return;
        }
        if (branch.isDefault) {
            toast(getString(R.string.branch_cannot_delete_default));
            return;
        }
        busyAction = true;
        progressBranches.setVisibility(View.VISIBLE);

        accountManager.deleteBranch(token, owner, repo, branch.name,
                new GitHubAccountManager.Callback<Boolean>() {
                    @Override
                    public void onSuccess(Boolean ok) {
                        busyAction = false;
                        if (!isAdded()) return;
                        progressBranches.setVisibility(View.GONE);

                        for (int i = branches.size() - 1; i >= 0; i--) {
                            if (branches.get(i).name.equalsIgnoreCase(branch.name)) {
                                branches.remove(i);
                            }
                        }

                        // Если удалили текущую выбранную ветку — переключаем на основную (main)
                        if (branch.name.equalsIgnoreCase(currentBranch)) {
                            currentBranch = defaultBranch.isEmpty() ? "main" : defaultBranch;
                            persistBranch(currentBranch);
                            if (listener != null) {
                                listener.onBranchChanged(owner, repo, currentBranch);
                            }
                        }
                        adapter.setSelectedBranch(currentBranch);
                        toast(getString(R.string.branch_deleted_toast, branch.name));
                    }

                    @Override
                    public void onError(String error) {
                        busyAction = false;
                        if (!isAdded()) return;
                        progressBranches.setVisibility(View.GONE);
                        toast(error == null ? getString(R.string.error_network) : error);
                    }
                });
    }

    private void applyAndClose(String chosenBranch, String toastMsg) {
        currentBranch = chosenBranch;
        persistBranch(chosenBranch);
        if (listener != null) {
            listener.onBranchChanged(owner, repo, chosenBranch);
        }
        toast(toastMsg);
        dismiss();
    }

    private void persistBranch(String chosenBranch) {
        Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        GitHubConfig cfg = Prefs.getConfig(ctx);
        cfg.saveBranchForRepo(owner, repo, chosenBranch);
        cfg.syncActiveFromMode();
        Prefs.saveConfig(ctx, cfg);
    }

    private void toast(String msg) {
        Context ctx = getContext();
        if (ctx != null && msg != null && !msg.isEmpty()) {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            Window window = dialog.getWindow();
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92f);
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }
}
