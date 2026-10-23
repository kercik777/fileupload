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
 * Адаптер списка репозиториев аккаунта с переключением активного репозитория в 1 клик
 * и кнопкой полного удаления репозитория вместе со всеми файлами.
 */
public class RepoListAdapter extends RecyclerView.Adapter<RepoListAdapter.VH> {

    public interface Listener {
        void onRepoSelected(GitHubAccountManager.UserRepo repo);
        void onRepoDeleteRequested(GitHubAccountManager.UserRepo repo);
    }

    private final Context ctx;
    private final List<GitHubAccountManager.UserRepo> items;
    private final Listener listener;
    private String activeOwner = "";
    private String activeRepo = "";

    public RepoListAdapter(Context ctx, List<GitHubAccountManager.UserRepo> items, Listener listener) {
        this.ctx = ctx;
        this.items = items;
        this.listener = listener;
    }

    public void setActiveRepo(String owner, String repo) {
        this.activeOwner = owner == null ? "" : owner.trim();
        this.activeRepo = repo == null ? "" : repo.trim();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.item_repo, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        final GitHubAccountManager.UserRepo repo = items.get(position);
        boolean isActive = !activeRepo.isEmpty()
                && repo.name.equalsIgnoreCase(activeRepo)
                && (activeOwner.isEmpty() || repo.owner.equalsIgnoreCase(activeOwner));

        holder.tvName.setText(repo.name);

        String vis = ctx.getString(repo.privateRepo
                ? R.string.repo_private_badge : R.string.repo_public_badge);
        String meta = vis + " · " + repo.defaultBranch;
        if (!repo.owner.isEmpty()) {
            meta = "@" + repo.owner + " · " + meta;
        }
        holder.tvMeta.setText(meta);

        if (isActive) {
            holder.root.setBackgroundResource(R.drawable.bg_repo_selected);
            holder.tvActiveBadge.setVisibility(View.VISIBLE);
            holder.ivIcon.setImageResource(R.drawable.ic_check);
        } else {
            holder.root.setBackgroundResource(R.drawable.bg_choice_item);
            holder.tvActiveBadge.setVisibility(View.GONE);
            holder.ivIcon.setImageResource(repo.privateRepo ? R.drawable.ic_lock : R.drawable.ic_folder);
        }

        holder.root.setOnClickListener(v -> listener.onRepoSelected(repo));
        holder.btnDelete.setOnClickListener(v -> listener.onRepoDeleteRequested(repo));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final View root;
        final ImageView ivIcon;
        final TextView tvName;
        final TextView tvActiveBadge;
        final TextView tvMeta;
        final MaterialButton btnDelete;

        VH(@NonNull View itemView) {
            super(itemView);
            root = itemView.findViewById(R.id.repoRowRoot);
            ivIcon = itemView.findViewById(R.id.ivRepoIcon);
            tvName = itemView.findViewById(R.id.tvRepoName);
            tvActiveBadge = itemView.findViewById(R.id.tvRepoActiveBadge);
            tvMeta = itemView.findViewById(R.id.tvRepoMeta);
            btnDelete = itemView.findViewById(R.id.btnDeleteRepo);
        }
    }
}
