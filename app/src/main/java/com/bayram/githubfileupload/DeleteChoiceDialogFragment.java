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
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.button.MaterialButton;

/**
 * Умный диалог удаления файла в Истории:
 * - Если у файла доступны и Скачивание, и Просмотр — предлагает выбрать:
 *   «Удалить скачивание», «Удалить просмотр» или «Удалить оба варианта».
 * - Если выбран быстрый значок удаления конкретной ссылки или у файла только 1 режим —
 *   показывает аккуратное подтверждение именно для этого действия.
 */
public class DeleteChoiceDialogFragment extends DialogFragment {

    private static final String ARG_ITEM_ID = "item_id";
    private static final String ARG_NAME = "name";
    private static final String ARG_HAS_DOWNLOAD = "has_download";
    private static final String ARG_HAS_PREVIEW = "has_preview";
    private static final String ARG_FORCED_MODE = "forced_mode";

    public interface Listener {
        void onDeleteConfirmed(long itemId, int deleteMode);
    }

    private Listener listener;

    public static DeleteChoiceDialogFragment newInstance(UploadItem item, int forcedDeleteMode) {
        DeleteChoiceDialogFragment f = new DeleteChoiceDialogFragment();
        Bundle args = new Bundle();
        args.putLong(ARG_ITEM_ID, item.id);
        args.putString(ARG_NAME, item.name == null ? "" : item.name);
        args.putBoolean(ARG_HAS_DOWNLOAD, item.hasDownload());
        args.putBoolean(ARG_HAS_PREVIEW, item.hasPreview());
        args.putInt(ARG_FORCED_MODE, forcedDeleteMode);
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
        View view = inflater.inflate(R.layout.dialog_delete_choice, container, false);

        Bundle args = getArguments();
        final long itemId = args == null ? 0L : args.getLong(ARG_ITEM_ID);
        final String name = args == null ? "" : args.getString(ARG_NAME, "");
        final boolean hasDownload = args != null && args.getBoolean(ARG_HAS_DOWNLOAD, false);
        final boolean hasPreview = args != null && args.getBoolean(ARG_HAS_PREVIEW, false);
        final int forcedMode = args == null ? 0 : args.getInt(ARG_FORCED_MODE, 0);

        TextView tvTitle = view.findViewById(R.id.tvDeleteTitle);
        TextView tvSubtitle = view.findViewById(R.id.tvDeleteSubtitle);
        View dualGroup = view.findViewById(R.id.dualDeleteGroup);
        View optDownload = view.findViewById(R.id.optDeleteDownload);
        View optPreview = view.findViewById(R.id.optDeletePreview);
        MaterialButton btnBoth = view.findViewById(R.id.btnDeleteBoth);
        MaterialButton btnSingle = view.findViewById(R.id.btnConfirmSingleDelete);
        View btnCancel = view.findViewById(R.id.btnCancelDelete);

        boolean showDualMenu = hasDownload && hasPreview && forcedMode == 0;

        if (showDualMenu) {
            dualGroup.setVisibility(View.VISIBLE);
            btnSingle.setVisibility(View.GONE);
            tvTitle.setText(R.string.history_delete_what_title);
            tvSubtitle.setText(getString(R.string.history_delete_both_subtitle, name));

            optDownload.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDeleteConfirmed(itemId, GitHubHistoryManager.DELETE_DOWNLOAD_ONLY);
                }
                dismiss();
            });

            optPreview.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDeleteConfirmed(itemId, GitHubHistoryManager.DELETE_PREVIEW_ONLY);
                }
                dismiss();
            });

            btnBoth.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDeleteConfirmed(itemId, GitHubHistoryManager.DELETE_ALL);
                }
                dismiss();
            });
        } else {
            dualGroup.setVisibility(View.GONE);
            btnSingle.setVisibility(View.VISIBLE);

            final int resolvedMode;
            if (forcedMode == GitHubHistoryManager.DELETE_DOWNLOAD_ONLY && hasDownload && hasPreview) {
                resolvedMode = GitHubHistoryManager.DELETE_DOWNLOAD_ONLY;
                tvTitle.setText(R.string.history_delete_keep_preview_title);
                tvSubtitle.setText(getString(R.string.history_delete_keep_preview_msg, name));
                btnSingle.setText(R.string.delete_opt_download_title);
            } else if (forcedMode == GitHubHistoryManager.DELETE_PREVIEW_ONLY && hasDownload && hasPreview) {
                resolvedMode = GitHubHistoryManager.DELETE_PREVIEW_ONLY;
                tvTitle.setText(R.string.history_delete_keep_download_title);
                tvSubtitle.setText(getString(R.string.history_delete_keep_download_msg, name));
                btnSingle.setText(R.string.delete_opt_preview_title);
            } else {
                resolvedMode = GitHubHistoryManager.DELETE_ALL;
                tvTitle.setText(R.string.history_delete_title);
                if (hasDownload && !hasPreview) {
                    tvSubtitle.setText(getString(R.string.history_delete_single_download_msg, name));
                } else if (hasPreview && !hasDownload) {
                    tvSubtitle.setText(getString(R.string.history_delete_single_preview_msg, name));
                } else {
                    tvSubtitle.setText(getString(R.string.history_delete_message, name));
                }
                btnSingle.setText(R.string.delete_release);
            }

            btnSingle.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDeleteConfirmed(itemId, resolvedMode);
                }
                dismiss();
            });
        }

        btnCancel.setOnClickListener(v -> dismiss());
        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            Window window = dialog.getWindow();
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.90f);
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }
}
