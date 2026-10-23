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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

/**
 * Диалог выбора формата перед открытием пикера:
 * Фотография / Видео / Файл.
 */
public class FileFormatDialogFragment extends DialogFragment {

    public static final int FORMAT_PHOTO = 0;
    public static final int FORMAT_VIDEO = 1;
    public static final int FORMAT_FILE = 2;

    public interface Listener {
        void onFormatChosen(int format);
    }

    private Listener listener;

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
        View view = inflater.inflate(R.layout.dialog_file_format, container, false);

        view.findViewById(R.id.optionFormatPhoto).setOnClickListener(v -> {
            if (listener != null) {
                listener.onFormatChosen(FORMAT_PHOTO);
            }
            dismiss();
        });

        view.findViewById(R.id.optionFormatVideo).setOnClickListener(v -> {
            if (listener != null) {
                listener.onFormatChosen(FORMAT_VIDEO);
            }
            dismiss();
        });

        view.findViewById(R.id.optionFormatFile).setOnClickListener(v -> {
            if (listener != null) {
                listener.onFormatChosen(FORMAT_FILE);
            }
            dismiss();
        });

        view.findViewById(R.id.btnCancelFormat).setOnClickListener(v -> dismiss());
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
