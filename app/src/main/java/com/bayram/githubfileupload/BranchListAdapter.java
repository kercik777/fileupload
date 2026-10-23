package com.bayram.githubfileupload;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.List;

/**
 * Адаптер списка веток репозитория внутри диалога выбора и создания веток.
 */
public class BranchListAdapter extends RecyclerView.Adapter<BranchListAdapter.VH> {

    public interface Listener {
        void onBranchClicked(GitHubAccountManager.BranchInfo branch);
        void onBranchDeleteClicked(GitHubAccountManager.BranchInfo branch);
    }

    private final Context ctx;
    private final List<GitHubAccountManager.BranchInfo> items;
    private final Listener listener;
    private String selectedBranch = "";

    public BranchListAdapter(Context ctx, List<GitHubAccountManager.BranchInfo> items,
                             String selectedBranch, Listener listener) {
        this.ctx = ctx;
        this.items = items;
        this.selectedBranch = selectedBranch == null ? "" : selectedBranch.trim();
        this.listener = listener;
    }

    public void setSelectedBranch(String branch) {
        this.selectedBranch = branch == null ? "" : branch.trim();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.item_branch, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        final GitHubAccountManager.BranchInfo b = items.get(position);
        boolean isSelected = !selectedBranch.isEmpty() && b.name.equalsIgnoreCase(selectedBranch);

        holder.tvName.setText(b.name);
        holder.tvDefaultBadge.setVisibility(b.isDefault ? View.VISIBLE : View.GONE);
        holder.tvSelectedBadge.setVisibility(isSelected ? View.VISIBLE : View.GONE);

        if (isSelected) {
            holder.root.setBackgroundResource(R.drawable.bg_repo_selected);
            holder.ivIcon.setImageResource(R.drawable.ic_check);
        } else {
            holder.root.setBackgroundResource(R.drawable.bg_choice_item);
            holder.ivIcon.setImageResource(R.drawable.ic_branch);
        }

        // Дополнительные незащищённые ветки можно удалить в 1 клик
        boolean canDelete = !b.isDefault && !b.protectedBranch;
        holder.btnDelete.setVisibility(canDelete ? View.VISIBLE : View.GONE);

        holder.root.setOnClickListener(v -> listener.onBranchClicked(b));
        holder.btnDelete.setOnClickListener(v -> listener.onBranchDeleteClicked(b));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final View root;
        final ImageView ivIcon;
        final TextView tvName;
        final TextView tvDefaultBadge;
        final TextView tvSelectedBadge;
        final MaterialButton btnDelete;

        VH(@NonNull View itemView) {
            super(itemView);
            root = itemView.findViewById(R.id.branchRowRoot);
            ivIcon = itemView.findViewById(R.id.ivBranchIcon);
            tvName = itemView.findViewById(R.id.tvBranchName);
            tvDefaultBadge = itemView.findViewById(R.id.tvBranchDefaultBadge);
            tvSelectedBadge = itemView.findViewById(R.id.tvBranchSelectedBadge);
            btnDelete = itemView.findViewById(R.id.btnDeleteBranch);
        }
    }
}
