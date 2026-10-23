package com.bayram.githubfileupload;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * Нижняя sheet с выбором цели загрузки:
 * скачивание / просмотр в браузере / оба варианта.
 */
public class UploadChoiceBottomSheet extends BottomSheetDialogFragment {

    public interface Listener {
        void onChoice(int mode);
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
        View view = inflater.inflate(R.layout.dialog_upload_choice, container, false);

        view.findViewById(R.id.optionDownload).setOnClickListener(v -> {
            if (listener != null) listener.onChoice(UploadService.MODE_DOWNLOAD);
            dismiss();
        });
        view.findViewById(R.id.optionPreview).setOnClickListener(v -> {
            if (listener != null) listener.onChoice(UploadService.MODE_PREVIEW);
            dismiss();
        });
        view.findViewById(R.id.optionBoth).setOnClickListener(v -> {
            if (listener != null) listener.onChoice(UploadService.MODE_BOTH);
            dismiss();
        });
        return view;
    }
}
