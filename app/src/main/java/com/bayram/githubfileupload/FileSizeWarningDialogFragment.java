package com.bayram.githubfileupload;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.ImageViewCompat;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.button.MaterialButton;

/**
 * Умное предупреждение о лимитах размера файла на GitHub:
 * - до 20 МБ: без предупреждений во всех режимах;
 * - от 20 до 65 МБ (при выборе «Просмотр» или «Оба варианта»): предупреждает о пороге CDN 20 МБ,
 *   позволяет продолжить загрузку (через прямую raw-ссылку) или переключиться на «Только скачивание»;
 * - от 65 МБ до 2 ГБ:
 *   • если выбрано «Только скачивание» — загружается сразу БЕЗ предупреждения;
 *   • если выбрано «Просмотр» или «Оба варианта» — предупреждает о потолке ветки (65 МБ)
 *     и в 1 клик предлагает загрузить «Только для скачивания (до 2 ГБ)»;
 * - свыше 2 ГБ (2048 МБ): предупреждает о максимальном лимите GitHub Releases (2 ГБ) в любом режиме.
 */
public class FileSizeWarningDialogFragment extends DialogFragment {

    public static final long LIMIT_CDN_20MB = 20L * 1024L * 1024L;
    public static final long LIMIT_BRANCH_65MB = 65L * 1024L * 1024L;
    public static final long LIMIT_RELEASE_2GB = 2048L * 1024L * 1024L;

    public static final int WARN_NONE = 0;
    public static final int WARN_OVER_20MB_PREVIEW = 1;
    public static final int WARN_OVER_65MB_PREVIEW = 2;
    public static final int WARN_OVER_2GB_ALL = 3;

    private static final String ARG_WARN_TYPE = "warn_type";
    private static final String ARG_FILE_SIZE = "file_size";
    private static final String ARG_REQUESTED_MODE = "requested_mode";

    public interface Listener {
        void onSizeWarningProceed(int resolvedUploadMode);
    }

    private Listener listener;

    /** Определяет, нужно ли показать предупреждение перед стартом загрузки. */
    public static int evaluate(long fileBytes, int requestedMode) {
        if (fileBytes > LIMIT_RELEASE_2GB) {
            return WARN_OVER_2GB_ALL;
        }
        if (requestedMode == UploadService.MODE_DOWNLOAD) {
            return WARN_NONE;
        }
        if (fileBytes > LIMIT_BRANCH_65MB) {
            return WARN_OVER_65MB_PREVIEW;
        }
        if (fileBytes > LIMIT_CDN_20MB) {
            return WARN_OVER_20MB_PREVIEW;
        }
        return WARN_NONE;
    }

    public static FileSizeWarningDialogFragment newInstance(int warnType, long fileSize, int requestedMode) {
        FileSizeWarningDialogFragment f = new FileSizeWarningDialogFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_WARN_TYPE, warnType);
        args.putLong(ARG_FILE_SIZE, fileSize);
        args.putInt(ARG_REQUESTED_MODE, requestedMode);
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
        View view = inflater.inflate(R.layout.dialog_size_warning, container, false);

        Bundle args = getArguments();
        final int warnType = args == null ? WARN_OVER_65MB_PREVIEW : args.getInt(ARG_WARN_TYPE);
        final long fileSize = args == null ? 0L : args.getLong(ARG_FILE_SIZE);
        final int requestedMode = args == null ? UploadService.MODE_BOTH : args.getInt(ARG_REQUESTED_MODE);

        View badge = view.findViewById(R.id.sizeWarnBadge);
        ImageView ivIcon = view.findViewById(R.id.ivSizeWarnIcon);
        TextView tvTitle = view.findViewById(R.id.tvSizeWarnTitle);
        TextView tvMessage = view.findViewById(R.id.tvSizeWarnMessage);
        Button btnDownloadOnly = view.findViewById(R.id.btnSizeDownloadOnly);
        MaterialButton btnContinue = view.findViewById(R.id.btnSizeContinueAnyway);
        MaterialButton btnCancel = view.findViewById(R.id.btnSizeCancel);

        String formattedSize = FileUtils.formatSize(fileSize);

        if (warnType == WARN_OVER_2GB_ALL) {
            badge.setBackgroundResource(R.drawable.bg_danger_circle);
            ivIcon.setImageResource(R.drawable.ic_error);
            ImageViewCompat.setImageTintList(ivIcon,
                    ColorStateList.valueOf(getResources().getColor(R.color.md_error)));

            tvTitle.setText(R.string.size_warn_over_2gb_title);
            tvMessage.setText(getString(R.string.size_warn_over_2gb_msg, formattedSize));
            btnDownloadOnly.setVisibility(View.GONE);
            btnContinue.setVisibility(View.GONE);
            btnCancel.setText(R.string.ok_understood);
        } else if (warnType == WARN_OVER_65MB_PREVIEW) {
            badge.setBackgroundResource(R.drawable.bg_warning_circle);
            ivIcon.setImageResource(R.drawable.ic_info);

            tvTitle.setText(R.string.size_warn_over_65mb_title);
            tvMessage.setText(getString(R.string.size_warn_over_65mb_msg, formattedSize));
            btnDownloadOnly.setVisibility(View.VISIBLE);
            btnContinue.setVisibility(View.GONE);
            btnCancel.setText(R.string.cancel);
        } else {
            badge.setBackgroundResource(R.drawable.bg_warning_circle);
            ivIcon.setImageResource(R.drawable.ic_info);

            tvTitle.setText(R.string.size_warn_over_20mb_title);
            tvMessage.setText(getString(R.string.size_warn_over_20mb_msg, formattedSize));
            btnDownloadOnly.setVisibility(View.VISIBLE);
            btnContinue.setVisibility(View.VISIBLE);
            btnCancel.setText(R.string.cancel);
        }

        btnDownloadOnly.setOnClickListener(v -> {
            if (listener != null) {
                listener.onSizeWarningProceed(UploadService.MODE_DOWNLOAD);
            }
            dismiss();
        });

        btnContinue.setOnClickListener(v -> {
            if (listener != null) {
                listener.onSizeWarningProceed(requestedMode);
            }
            dismiss();
        });

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
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92f);
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }
}
