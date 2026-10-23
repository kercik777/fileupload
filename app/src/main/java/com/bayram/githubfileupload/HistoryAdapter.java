package com.bayram.githubfileupload;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.VH> {

    public interface Listener {
        void onCopyDownload(UploadItem item);

        void onCopyPreview(UploadItem item);

        void onDelete(UploadItem item);

        void onOpen(UploadItem item);
    }

    private final Context ctx;
    private final List<UploadItem> items;
    private final Listener listener;

    public HistoryAdapter(Context ctx, List<UploadItem> items, Listener listener) {
        this.ctx = ctx;
        this.items = items;
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.item_history, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        UploadItem item = items.get(position);
        holder.name.setText(item.name);
        holder.meta.setText(FileUtils.formatSize(item.size) + " · " + formatTime(item.time));

        if (item.isImage() && item.sourceUri != null && !item.sourceUri.isEmpty()) {
            holder.thumb.setVisibility(View.VISIBLE);
            holder.icon.setVisibility(View.GONE);
            ImageLoader.load(ctx, holder.thumb, item.sourceUri, R.drawable.ic_image);
        } else {
            holder.thumb.setVisibility(View.GONE);
            holder.icon.setVisibility(View.VISIBLE);
            holder.icon.setImageResource(item.iconRes());
        }

        holder.downloadUrl.setVisibility(item.hasDownload() ? View.VISIBLE : View.GONE);
        holder.downloadUrl.setText(item.downloadUrl);

        holder.previewUrl.setVisibility(item.hasPreview() ? View.VISIBLE : View.GONE);
        holder.previewUrl.setText(item.previewUrl);

        holder.btnCopyDownload.setVisibility(item.hasDownload() ? View.VISIBLE : View.GONE);
        holder.btnCopyPreview.setVisibility(item.hasPreview() ? View.VISIBLE : View.GONE);
        holder.btnDelete.setVisibility(item.hasDownload() ? View.VISIBLE : View.GONE);

        holder.btnCopyDownload.setOnClickListener(v -> listener.onCopyDownload(item));
        holder.btnCopyPreview.setOnClickListener(v -> listener.onCopyPreview(item));
        holder.btnDelete.setOnClickListener(v -> listener.onDelete(item));
        holder.thumb.setOnClickListener(v -> listener.onOpen(item));
        holder.icon.setOnClickListener(v -> listener.onOpen(item));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static String formatTime(long millis) {
        return new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                .format(new Date(millis));
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final ImageView thumb;
        final TextView name;
        final TextView meta;
        final TextView downloadUrl;
        final TextView previewUrl;
        final com.google.android.material.button.MaterialButton btnCopyDownload;
        final com.google.android.material.button.MaterialButton btnCopyPreview;
        final com.google.android.material.button.MaterialButton btnDelete;

        VH(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.itemIcon);
            thumb = itemView.findViewById(R.id.itemThumb);
            name = itemView.findViewById(R.id.itemName);
            meta = itemView.findViewById(R.id.itemMeta);
            downloadUrl = itemView.findViewById(R.id.itemDownloadUrl);
            previewUrl = itemView.findViewById(R.id.itemPreviewUrl);
            btnCopyDownload = itemView.findViewById(R.id.itemCopyDownload);
            btnCopyPreview = itemView.findViewById(R.id.itemCopyPreview);
            btnDelete = itemView.findViewById(R.id.itemDelete);
        }
    }
}
