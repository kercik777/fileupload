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
 * Диалог подтверждения полного удаления репозитория вместе со всеми загруженными файлами.
 */
public class DeleteRepoDialogFragment extends DialogFragment {

    private static final String ARG_OWNER = "owner";
    private static final String ARG_REPO = "repo";

    public interface Listener {
        void onDeleteRepoConfirmed(String owner, String repo);
    }

    private Listener listener;

    public static DeleteRepoDialogFragment newInstance(String owner, String repo) {
        DeleteRepoDialogFragment f = new DeleteRepoDialogFragment();
        Bundle args = new Bundle();
        args.putString(ARG_OWNER, owner == null ? "" : owner);
        args.putString(ARG_REPO, repo == null ? "" : repo);
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
        View view = inflater.inflate(R.layout.dialog_delete_repo, container, false);

        Bundle args = getArguments();
        final String owner = args == null ? "" : args.getString(ARG_OWNER, "");
        final String repo = args == null ? "" : args.getString(ARG_REPO, "");

        TextView tvFullName = view.findViewById(R.id.tvDeleteRepoFullName);
        MaterialButton btnConfirm = view.findViewById(R.id.btnConfirmDeleteRepo);
        View btnCancel = view.findViewById(R.id.btnCancelDeleteRepo);

        tvFullName.setText(owner.isEmpty() ? repo : (owner + "/" + repo));

        btnConfirm.setOnClickListener(v -> {
            if (listener != null) {
                listener.onDeleteRepoConfirmed(owner, repo);
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
