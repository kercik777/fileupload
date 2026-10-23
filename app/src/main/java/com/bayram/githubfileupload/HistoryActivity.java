package com.bayram.githubfileupload;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/**
 * Экран истории загрузок в онлайн-режиме (через GitHub API) с локальным кэшем.
 * Позволяет удалять как файл целиком, так и по отдельности только ссылку скачивания
 * или только ссылку просмотра.
 */
public class HistoryActivity extends AppCompatActivity
        implements HistoryAdapter.Listener, DeleteChoiceDialogFragment.Listener {

    private RecyclerView recycler;
    private ProgressBar progressSync;
    private View emptyBox;
    private TextView tvEmpty;
    private MaterialButton btnOpenSettings;

    private final GitHubHistoryManager historyManager = new GitHubHistoryManager();
    private final List<UploadItem> currentItems = new ArrayList<>();
    private HistoryAdapter adapter;
    private boolean loadingOnline;
    private boolean deletingBusy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        recycler = findViewById(R.id.recycler);
        progressSync = findViewById(R.id.progressSync);
        emptyBox = findViewById(R.id.emptyBox);
        tvEmpty = findViewById(R.id.tvEmpty);
        btnOpenSettings = findViewById(R.id.btnOpenSettings);

        btnOpenSettings.setOnClickListener(v ->
                startActivity(new Intent(this, ConfigActivity.class)));

        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new HistoryAdapter(this, currentItems, this);
        recycler.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncHistory();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.history_menu, menu);
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            Drawable icon = item.getIcon();
            if (icon != null && item.getItemId() == R.id.action_refresh) {
                Drawable wrapped = DrawableCompat.wrap(icon.mutate());
                DrawableCompat.setTint(wrapped, Color.WHITE);
                item.setIcon(wrapped);
            }
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_refresh) {
            syncHistory();
            return true;
        }
        if (id == R.id.action_clear_history) {
            Prefs.clearHistory(this);
            Toast.makeText(this, R.string.history_cache_cleared, Toast.LENGTH_SHORT).show();
            syncHistory();
            return true;
        }
        if (id == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void syncHistory() {
        GitHubConfig cfg = Prefs.getConfig(this);
        List<UploadItem> cached = Prefs.getHistoryForRepo(this, cfg);
        setItems(cached);

        if (!cfg.isValid()) {
            progressSync.setVisibility(View.GONE);
            if (currentItems.isEmpty()) {
                emptyBox.setVisibility(View.VISIBLE);
                tvEmpty.setText(R.string.history_need_config);
                btnOpenSettings.setVisibility(View.VISIBLE);
            } else {
                emptyBox.setVisibility(View.GONE);
            }
            return;
        }

        btnOpenSettings.setVisibility(View.GONE);
        if (loadingOnline) {
            return;
        }
        loadingOnline = true;
        progressSync.setVisibility(View.VISIBLE);
        if (currentItems.isEmpty()) {
            emptyBox.setVisibility(View.VISIBLE);
            tvEmpty.setText(R.string.history_loading);
        } else {
            emptyBox.setVisibility(View.GONE);
        }

        historyManager.loadOnlineHistory(this, cfg, new GitHubHistoryManager.Callback<List<UploadItem>>() {
            @Override
            public void onSuccess(List<UploadItem> result) {
                loadingOnline = false;
                if (isFinishing()) return;
                progressSync.setVisibility(View.GONE);
                setItems(result);
                if (currentItems.isEmpty()) {
                    emptyBox.setVisibility(View.VISIBLE);
                    tvEmpty.setText(R.string.history_empty);
                } else {
                    emptyBox.setVisibility(View.GONE);
                }
            }

            @Override
            public void onError(String error) {
                loadingOnline = false;
                if (isFinishing()) return;
                progressSync.setVisibility(View.GONE);
                if (currentItems.isEmpty()) {
                    emptyBox.setVisibility(View.VISIBLE);
                    tvEmpty.setText(error == null || error.isEmpty()
                            ? getString(R.string.error_network) : error);
                }
                if (error != null && !error.isEmpty()) {
                    Toast.makeText(HistoryActivity.this, error, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void setItems(List<UploadItem> items) {
        currentItems.clear();
        if (items != null) {
            currentItems.addAll(items);
        }
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onCopyDownload(UploadItem item) {
        copy(item.downloadUrl);
    }

    @Override
    public void onCopyPreview(UploadItem item) {
        copy(item.bestPreviewUrl());
    }

    @Override
    public void onDelete(UploadItem item) {
        showDeleteDialog(item, 0);
    }

    @Override
    public void onDeleteDownloadOnly(UploadItem item) {
        showDeleteDialog(item, GitHubHistoryManager.DELETE_DOWNLOAD_ONLY);
    }

    @Override
    public void onDeletePreviewOnly(UploadItem item) {
        showDeleteDialog(item, GitHubHistoryManager.DELETE_PREVIEW_ONLY);
    }

    private void showDeleteDialog(UploadItem item, int forcedDeleteMode) {
        if (deletingBusy || item == null) {
            return;
        }
        if (item.id == 0) {
            item.id = System.nanoTime();
        }
        DeleteChoiceDialogFragment.newInstance(item, forcedDeleteMode)
                .show(getSupportFragmentManager(), "delete-choice");
    }

    @Override
    public void onDeleteConfirmed(long itemId, int deleteMode) {
        if (deletingBusy) {
            return;
        }
        UploadItem target = findById(itemId);
        if (target == null) {
            return;
        }
        performDelete(target, deleteMode);
    }

    private UploadItem findById(long itemId) {
        for (UploadItem it : currentItems) {
            if (it != null && it.id == itemId) {
                return it;
            }
        }
        return null;
    }

    private void performDelete(final UploadItem item, final int deleteMode) {
        deletingBusy = true;
        progressSync.setVisibility(View.VISIBLE);
        Toast.makeText(this, R.string.history_deleting, Toast.LENGTH_SHORT).show();

        GitHubConfig cfg = Prefs.getConfig(this);
        historyManager.deleteByMode(this, cfg, item, deleteMode,
                new GitHubHistoryManager.Callback<Boolean>() {
                    @Override
                    public void onSuccess(Boolean ok) {
                        deletingBusy = false;
                        if (isFinishing()) return;
                        progressSync.setVisibility(View.GONE);

                        boolean stillHasAnyLink = item.hasDownload() || item.hasPreview();
                        if (deleteMode == GitHubHistoryManager.DELETE_DOWNLOAD_ONLY && stillHasAnyLink) {
                            notifyUpdatedInPlace(item);
                            Toast.makeText(HistoryActivity.this,
                                    R.string.history_deleted_download_only, Toast.LENGTH_SHORT).show();
                        } else if (deleteMode == GitHubHistoryManager.DELETE_PREVIEW_ONLY && stillHasAnyLink) {
                            notifyUpdatedInPlace(item);
                            Toast.makeText(HistoryActivity.this,
                                    R.string.history_deleted_preview_only, Toast.LENGTH_SHORT).show();
                        } else {
                            removeFromCurrent(item);
                            Toast.makeText(HistoryActivity.this,
                                    R.string.history_deleted, Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onError(String error) {
                        deletingBusy = false;
                        if (isFinishing()) return;
                        progressSync.setVisibility(View.GONE);
                        Toast.makeText(HistoryActivity.this,
                                (error == null || error.isEmpty())
                                        ? getString(R.string.error_network) : error,
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void notifyUpdatedInPlace(UploadItem target) {
        int idx = currentItems.indexOf(target);
        if (idx >= 0) {
            adapter.notifyItemChanged(idx);
        } else {
            adapter.notifyDataSetChanged();
        }
    }

    private void removeFromCurrent(UploadItem target) {
        int removedIdx = currentItems.indexOf(target);
        if (removedIdx >= 0) {
            currentItems.remove(removedIdx);
            adapter.notifyItemRemoved(removedIdx);
        } else {
            for (int i = currentItems.size() - 1; i >= 0; i--) {
                if (currentItems.get(i).sameEntry(target)) {
                    currentItems.remove(i);
                }
            }
            adapter.notifyDataSetChanged();
        }
        if (currentItems.isEmpty()) {
            emptyBox.setVisibility(View.VISIBLE);
            tvEmpty.setText(R.string.history_empty);
        }
    }

    @Override
    public void onOpen(UploadItem item) {
        if (item == null) {
            return;
        }
        String url = item.primaryUrl();
        if (url.isEmpty()) {
            return;
        }
        if (item.isImage() || item.isVideo()) {
            Intent i = new Intent(this, PreviewActivity.class);
            i.putExtra(PreviewActivity.EXTRA_URL, url);
            i.putExtra(PreviewActivity.EXTRA_MIME, item.effectiveMime());
            i.putExtra(PreviewActivity.EXTRA_NAME, item.name);
            startActivity(i);
        } else {
            openBrowser(url);
        }
    }

    @Override
    public void onShare(UploadItem item) {
        if (item == null) {
            return;
        }
        String text = item.primaryUrl();
        if (item.hasDownload() && item.hasPreview()) {
            text = getString(R.string.preview_url_label) + ": " + item.bestPreviewUrl()
                    + "\n" + getString(R.string.download_url_label) + ": " + item.downloadUrl;
        }
        if (text.isEmpty()) {
            return;
        }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(i, getString(R.string.share)));
    }

    private void copy(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("link", text));
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    private void openBrowser(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, R.string.preview_unsupported, Toast.LENGTH_SHORT).show();
        }
    }
}
