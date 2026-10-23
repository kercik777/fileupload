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

        void onShare(UploadItem item);
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

        StringBuilder metaSb = new StringBuilder();
        metaSb.append(FileUtils.formatSize(item.size));
        if (item.time > 0) {
            metaSb.append(" · ").append(formatTime(item.time));
        }
        if (item.tag != null && !item.tag.isEmpty() && item.hasDownload()) {
            metaSb.append(" · ").append(item.tag);
        }
        holder.meta.setText(metaSb.toString());

        if (item.hasDownload() && item.hasPreview()) {
            holder.badge.setText(R.string.upload_mode_both);
        } else if (item.hasPreview()) {
            holder.badge.setText(R.string.upload_mode_preview);
        } else {
            holder.badge.setText(R.string.upload_mode_download);
        }

        String thumbSource = resolveThumbSource(item);
        if (!thumbSource.isEmpty()) {
            holder.thumb.setVisibility(View.VISIBLE);
            holder.icon.setVisibility(View.GONE);
            ImageLoader.load(ctx, holder.thumb, thumbSource, R.drawable.ic_image);
        } else {
            holder.thumb.setVisibility(View.GONE);
            holder.icon.setVisibility(View.VISIBLE);
            holder.icon.setImageResource(item.iconRes());
        }

        boolean hasDownload = item.hasDownload();
        boolean hasPreview = item.hasPreview();

        holder.downloadRow.setVisibility(hasDownload ? View.VISIBLE : View.GONE);
        if (hasDownload) {
            holder.downloadUrl.setText(item.downloadUrl);
        }

        holder.previewRow.setVisibility(hasPreview ? View.VISIBLE : View.GONE);
        if (hasPreview) {
            holder.previewUrl.setText(item.bestPreviewUrl());
        }

        // Кнопки Открыть / Поделиться / Удалить всегда доступны для любого файла
        holder.btnOpen.setIconResource((item.isImage() || item.isVideo())
                ? R.drawable.ic_eye : R.drawable.ic_open_in_new);

        holder.btnCopyDownload.setOnClickListener(v -> listener.onCopyDownload(item));
        holder.btnCopyPreview.setOnClickListener(v -> listener.onCopyPreview(item));
        holder.btnOpen.setOnClickListener(v -> listener.onOpen(item));
        holder.btnShare.setOnClickListener(v -> listener.onShare(item));
        holder.btnDelete.setOnClickListener(v -> listener.onDelete(item));
        holder.thumb.setOnClickListener(v -> listener.onOpen(item));
        holder.icon.setOnClickListener(v -> listener.onOpen(item));
    }

    private static String resolveThumbSource(UploadItem item) {
        if (!item.isImage()) {
            return "";
        }
        if (item.sourceUri != null && !item.sourceUri.isEmpty()) {
            return item.sourceUri;
        }
        if (item.previewRawUrl != null && !item.previewRawUrl.isEmpty()) {
            return item.previewRawUrl;
        }
        if (item.previewUrl != null && !item.previewUrl.isEmpty()) {
            return item.previewUrl;
        }
        if (item.hasDownload() && (item.size <= 0 || item.size <= 15L * 1024L * 1024L)) {
            return item.downloadUrl;
        }
        return "";
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
        final TextView badge;
        final View downloadRow;
        final View previewRow;
        final TextView downloadUrl;
        final TextView previewUrl;
        final MaterialButton btnCopyDownload;
        final MaterialButton btnCopyPreview;
        final MaterialButton btnOpen;
        final MaterialButton btnShare;
        final MaterialButton btnDelete;

        VH(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.itemIcon);
            thumb = itemView.findViewById(R.id.itemThumb);
            thumb.setClipToOutline(true);
            name = itemView.findViewById(R.id.itemName);
            meta = itemView.findViewById(R.id.itemMeta);
            badge = itemView.findViewById(R.id.itemBadge);
            downloadRow = itemView.findViewById(R.id.downloadRow);
            previewRow = itemView.findViewById(R.id.previewRow);
            downloadUrl = itemView.findViewById(R.id.itemDownloadUrl);
            previewUrl = itemView.findViewById(R.id.itemPreviewUrl);
            btnCopyDownload = itemView.findViewById(R.id.itemCopyDownload);
            btnCopyPreview = itemView.findViewById(R.id.itemCopyPreview);
            btnOpen = itemView.findViewById(R.id.itemOpen);
            btnShare = itemView.findViewById(R.id.itemShare);
            btnDelete = itemView.findViewById(R.id.itemDelete);
        }
    }
}
