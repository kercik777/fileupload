package com.bayram.githubfileupload;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.button.MaterialButton;

/**
 * Диалог авторизации через GitHub OAuth Device Flow:
 * показывает одноразовый код, автоматически копирует его в буфер обмена,
 * открывает страницу подтверждения GitHub и сам забирает токен после нажатия Authorize.
 */
public class GitHubDeviceAuthDialogFragment extends DialogFragment {

    public interface Listener {
        void onGitHubAuthorized(GitHubAccountManager.AuthResult result);
    }

    private Listener listener;
    private final GitHubAccountManager accountManager = new GitHubAccountManager();
    private GitHubAccountManager.PollSession pollSession;
    private GitHubAccountManager.DeviceCodeInfo currentInfo;
    private boolean browserOpened;

    private View loadingBox;
    private TextView tvLoading;
    private View readyBox;
    private View codeCopyBox;
    private TextView tvUserCode;
    private Button btnOpenBrowser;
    private ProgressBar progressPoll;
    private TextView tvPollStatus;
    private MaterialButton btnRetry;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof Listener) {
            listener = (Listener) context;
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setCancelable(false);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.dialog_device_auth, container, false);

        loadingBox = view.findViewById(R.id.deviceLoadingBox);
        tvLoading = view.findViewById(R.id.tvDeviceLoading);
        readyBox = view.findViewById(R.id.deviceReadyBox);
        codeCopyBox = view.findViewById(R.id.codeCopyBox);
        tvUserCode = view.findViewById(R.id.tvUserCode);
        btnOpenBrowser = view.findViewById(R.id.btnOpenGitHubVerify);
        progressPoll = view.findViewById(R.id.progressPoll);
        tvPollStatus = view.findViewById(R.id.tvPollStatus);
        btnRetry = view.findViewById(R.id.btnRetryDeviceAuth);
        View btnCancel = view.findViewById(R.id.btnCancelDeviceAuth);

        codeCopyBox.setOnClickListener(v -> {
            if (currentInfo != null) {
                copyCode(currentInfo.userCode, true);
            }
        });

        btnOpenBrowser.setOnClickListener(v -> {
            if (currentInfo != null) {
                copyCode(currentInfo.userCode, false);
                openVerifyUrl(currentInfo.verificationUri);
            }
        });

        btnRetry.setOnClickListener(v -> startDeviceFlow());
        btnCancel.setOnClickListener(v -> {
            stopPolling();
            dismissAllowingStateLoss();
        });

        startDeviceFlow();
        return view;
    }

    private void startDeviceFlow() {
        stopPolling();
        browserOpened = false;
        currentInfo = null;

        loadingBox.setVisibility(View.VISIBLE);
        tvLoading.setText(R.string.device_auth_loading);
        readyBox.setVisibility(View.GONE);
        btnRetry.setVisibility(View.GONE);

        accountManager.requestDeviceCode(new GitHubAccountManager.Callback<GitHubAccountManager.DeviceCodeInfo>() {
            @Override
            public void onSuccess(GitHubAccountManager.DeviceCodeInfo info) {
                if (!isAdded()) return;
                currentInfo = info;
                loadingBox.setVisibility(View.GONE);
                readyBox.setVisibility(View.VISIBLE);
                btnRetry.setVisibility(View.GONE);
                progressPoll.setVisibility(View.VISIBLE);
                tvPollStatus.setText(R.string.device_auth_waiting);
                tvUserCode.setText(info.userCode);

                copyCode(info.userCode, true);
                beginPolling(info);
            }

            @Override
            public void onError(String error) {
                if (!isAdded()) return;
                loadingBox.setVisibility(View.VISIBLE);
                readyBox.setVisibility(View.GONE);
                tvLoading.setText(error == null ? getString(R.string.error_network) : error);
                btnRetry.setVisibility(View.VISIBLE);
            }
        });
    }

    private void beginPolling(GitHubAccountManager.DeviceCodeInfo info) {
        pollSession = accountManager.startPollingToken(info,
                new GitHubAccountManager.Callback<GitHubAccountManager.AuthResult>() {
                    @Override
                    public void onSuccess(GitHubAccountManager.AuthResult result) {
                        if (!isAdded()) return;
                        if (listener != null) {
                            listener.onGitHubAuthorized(result);
                        }
                        dismissAllowingStateLoss();
                    }

                    @Override
                    public void onError(String error) {
                        if (!isAdded()) return;
                        progressPoll.setVisibility(View.GONE);
                        tvPollStatus.setText(error == null ? getString(R.string.error_network) : error);
                        btnRetry.setVisibility(View.VISIBLE);
                    }
                });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (browserOpened && pollSession != null) {
            pollSession.pollNow();
        }
    }

    @Override
    public void onDestroyView() {
        stopPolling();
        super.onDestroyView();
    }

    private void stopPolling() {
        if (pollSession != null) {
            pollSession.cancel();
            pollSession = null;
        }
    }

    private void copyCode(String code, boolean showToast) {
        Context ctx = getContext();
        if (ctx == null || code == null || code.isEmpty()) {
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("github-code", code));
            if (showToast) {
                Toast.makeText(ctx, getString(R.string.device_auth_code_copied, code),
                        Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ignored) {
        }
    }

    private void openVerifyUrl(String url) {
        Context ctx = getContext();
        if (ctx == null || url == null || url.isEmpty()) {
            return;
        }
        try {
            browserOpened = true;
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(ctx, url, Toast.LENGTH_LONG).show();
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
