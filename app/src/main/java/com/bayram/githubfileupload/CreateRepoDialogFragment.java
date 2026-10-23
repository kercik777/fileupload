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
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.textfield.TextInputEditText;

/**
 * Диалог создания нового публичного репозитория с README.md в один клик.
 */
public class CreateRepoDialogFragment extends DialogFragment {

    public interface Listener {
        void onCreateRepoConfirmed(String repoName);
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
        View view = inflater.inflate(R.layout.dialog_create_repo, container, false);

        final TextInputEditText etName = view.findViewById(R.id.etNewRepoName);
        Button btnConfirm = view.findViewById(R.id.btnConfirmCreateRepo);
        View btnCancel = view.findViewById(R.id.btnCancelCreateRepo);

        btnConfirm.setOnClickListener(v -> {
            String raw = etName.getText() == null ? "" : etName.getText().toString();
            String clean = GitHubAccountManager.sanitizeRepoName(raw);
            if (clean.isEmpty()) {
                Context ctx = getContext();
                if (ctx != null) {
                    Toast.makeText(ctx, R.string.create_repo_invalid_name, Toast.LENGTH_SHORT).show();
                }
                return;
            }
            if (listener != null) {
                listener.onCreateRepoConfirmed(clean);
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
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.90f);
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }
}
