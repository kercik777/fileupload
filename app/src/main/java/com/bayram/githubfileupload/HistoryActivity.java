package com.bayram.githubfileupload;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
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

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Экран истории загрузок в онлайн-режиме (через GitHub API) с мгновенным поиском по любой ссылке
 * (скачивание / CDN jsDelivr / raw.githubusercontent / GitHub blob) или названию файла.
 * В результатах поиска работают все обычные действия: копирование, просмотр и удаление.
 */
public class HistoryActivity extends AppCompatActivity
        implements HistoryAdapter.Listener, DeleteChoiceDialogFragment.Listener {

    private RecyclerView recycler;
    private ProgressBar progressSync;
    private View emptyBox;
    private ImageView ivEmptyIcon;
    private TextView tvEmpty;
    private MaterialButton btnOpenSettings;

    private EditText etHistorySearch;
    private MaterialButton btnPasteSearch;
    private ImageButton btnClearSearch;

    private final GitHubHistoryManager historyManager = new GitHubHistoryManager();
    private final List<UploadItem> allItems = new ArrayList<>();
    private final List<UploadItem> filteredItems = new ArrayList<>();
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
        ivEmptyIcon = findViewById(R.id.ivEmptyIcon);
        tvEmpty = findViewById(R.id.tvEmpty);
        btnOpenSettings = findViewById(R.id.btnOpenSettings);

        etHistorySearch = findViewById(R.id.etHistorySearch);
        btnPasteSearch = findViewById(R.id.btnPasteSearch);
        btnClearSearch = findViewById(R.id.btnClearSearch);

        btnOpenSettings.setOnClickListener(v ->
                startActivity(new Intent(this, ConfigActivity.class)));

        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new HistoryAdapter(this, filteredItems, this);
        recycler.setAdapter(adapter);

        setupSearchUi();
    }

    private void setupSearchUi() {
        updateSearchButtonsVisibility(currentSearchQuery());

        etHistorySearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String q = s == null ? "" : s.toString().trim();
                updateSearchButtonsVisibility(q);
                applySearchFilter();
            }
        });

        etHistorySearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard(v);
                return true;
            }
            return false;
        });

        btnPasteSearch.setOnClickListener(v -> pasteClipboardIntoSearch());

        btnClearSearch.setOnClickListener(v -> {
            etHistorySearch.setText("");
            hideKeyboard(etHistorySearch);
        });
    }

    private void updateSearchButtonsVisibility(String query) {
        boolean hasText = query != null && !query.isEmpty();
        btnClearSearch.setVisibility(hasText ? View.VISIBLE : View.GONE);
        btnPasteSearch.setVisibility(hasText ? View.GONE : View.VISIBLE);
    }

    private void pasteClipboardIntoSearch() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                    && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                if (cs != null) {
                    String clip = cs.toString().trim();
                    if (!clip.isEmpty()) {
                        etHistorySearch.setText(clip);
                        etHistorySearch.setSelection(clip.length());
                        hideKeyboard(etHistorySearch);
                        return;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        etHistorySearch.requestFocus();
    }

    private void hideKeyboard(View v) {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && v != null) {
                imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
            }
        } catch (Exception ignored) {
        }
    }

    private String currentSearchQuery() {
        return (etHistorySearch == null || etHistorySearch.getText() == null)
                ? "" : etHistorySearch.getText().toString().trim();
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
        setAllItems(cached);

        if (!cfg.isValid()) {
            progressSync.setVisibility(View.GONE);
            updateEmptyState(false, null, true);
            return;
        }

        btnOpenSettings.setVisibility(View.GONE);
        if (loadingOnline) {
            return;
        }
        loadingOnline = true;
        progressSync.setVisibility(View.VISIBLE);
        updateEmptyState(true, null, false);

        historyManager.loadOnlineHistory(this, cfg, new GitHubHistoryManager.Callback<List<UploadItem>>() {
            @Override
            public void onSuccess(List<UploadItem> result) {
                loadingOnline = false;
                if (isFinishing()) return;
                progressSync.setVisibility(View.GONE);
                setAllItems(result);
                updateEmptyState(false, null, false);
            }

            @Override
            public void onError(String error) {
                loadingOnline = false;
                if (isFinishing()) return;
                progressSync.setVisibility(View.GONE);
                updateEmptyState(false, error, false);
                if (error != null && !error.isEmpty()) {
                    Toast.makeText(HistoryActivity.this, error, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void setAllItems(List<UploadItem> items) {
        allItems.clear();
        if (items != null) {
            allItems.addAll(items);
        }
        applySearchFilter();
    }

    private void applySearchFilter() {
        String rawQuery = currentSearchQuery();
        filteredItems.clear();
        if (rawQuery.isEmpty()) {
            filteredItems.addAll(allItems);
        } else {
            for (UploadItem item : allItems) {
                if (item != null && matchesQuery(item, rawQuery)) {
                    filteredItems.add(item);
                }
            }
        }
        adapter.notifyDataSetChanged();

        GitHubConfig cfg = Prefs.getConfig(this);
        updateEmptyState(loadingOnline, null, cfg == null || !cfg.isValid());
    }

    /**
     * Проверяет совпадение со всеми видами ссылок файла (скачивание, jsDelivr CDN,
     * raw.githubusercontent, страница GitHub blob — как в закодированном, так и в декодированном виде),
     * а также с именем файла, путём на ветке, тегом релиза и веткой.
     */
    private static boolean matchesQuery(UploadItem item, String rawQuery) {
        String qRaw = rawQuery.trim().toLowerCase(Locale.ROOT);
        if (qRaw.isEmpty()) {
            return true;
        }
        String qDecoded = safeUrlDecode(qRaw).toLowerCase(Locale.ROOT);
        String qStripped = stripQueryAndFragment(qDecoded);

        return fieldMatches(item.downloadUrl, qRaw, qDecoded, qStripped)
                || fieldMatches(item.previewUrl, qRaw, qDecoded, qStripped)
                || fieldMatches(item.previewRawUrl, qRaw, qDecoded, qStripped)
                || fieldMatches(item.pageUrl, qRaw, qDecoded, qStripped)
                || fieldMatches(item.bestPreviewUrl(), qRaw, qDecoded, qStripped)
                || fieldMatches(item.previewPath, qRaw, qDecoded, qStripped)
                || fieldMatches(item.name, qRaw, qDecoded, qStripped)
                || fieldMatches(item.releaseAssetName, qRaw, qDecoded, qStripped)
                || fieldMatches(item.tag, qRaw, qDecoded, qStripped)
                || fieldMatches(item.previewBranch, qRaw, qDecoded, qStripped);
    }

    private static boolean fieldMatches(String field, String qRaw, String qDecoded, String qStripped) {
        if (field == null || field.isEmpty()) {
            return false;
        }
        String fRaw = field.toLowerCase(Locale.ROOT);
        if (fRaw.contains(qRaw)) {
            return true;
        }
        String fDecoded = safeUrlDecode(fRaw).toLowerCase(Locale.ROOT);
        if (fDecoded.contains(qDecoded)) {
            return true;
        }
        return !qStripped.isEmpty() && fDecoded.contains(qStripped);
    }

    private static String safeUrlDecode(String s) {
        if (s == null || s.isEmpty() || s.indexOf('%') < 0) {
            return s == null ? "" : s;
        }
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String stripQueryAndFragment(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        String res = url;
        int hash = res.indexOf('#');
        if (hash > 0) {
            res = res.substring(0, hash);
        }
        int q = res.indexOf('?');
        if (q > 0) {
            res = res.substring(0, q);
        }
        while (res.length() > 1 && res.endsWith("/")) {
            res = res.substring(0, res.length() - 1);
        }
        return res;
    }

    private void updateEmptyState(boolean isLoading, String errorMsg, boolean needConfig) {
        if (!filteredItems.isEmpty()) {
            emptyBox.setVisibility(View.GONE);
            return;
        }

        emptyBox.setVisibility(View.VISIBLE);
        String query = currentSearchQuery();

        if (!allItems.isEmpty() && !query.isEmpty()) {
            ivEmptyIcon.setImageResource(R.drawable.ic_search);
            tvEmpty.setText(R.string.history_search_empty);
            btnOpenSettings.setVisibility(View.GONE);
            return;
        }

        ivEmptyIcon.setImageResource(R.drawable.ic_history);
        if (needConfig) {
            tvEmpty.setText(R.string.history_need_config);
            btnOpenSettings.setVisibility(View.VISIBLE);
        } else if (isLoading) {
            tvEmpty.setText(R.string.history_loading);
            btnOpenSettings.setVisibility(View.GONE);
        } else if (errorMsg != null && !errorMsg.isEmpty()) {
            tvEmpty.setText(errorMsg);
            btnOpenSettings.setVisibility(View.GONE);
        } else {
            tvEmpty.setText(R.string.history_empty);
            btnOpenSettings.setVisibility(View.GONE);
        }
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
        for (UploadItem it : allItems) {
            if (it != null && it.id == itemId) {
                return it;
            }
        }
        for (UploadItem it : filteredItems) {
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
        int idx = filteredItems.indexOf(target);
        if (idx >= 0) {
            adapter.notifyItemChanged(idx);
        } else {
            adapter.notifyDataSetChanged();
        }
    }

    private void removeFromCurrent(UploadItem target) {
        allItems.remove(target);
        for (int i = allItems.size() - 1; i >= 0; i--) {
            if (allItems.get(i).sameEntry(target)) {
                allItems.remove(i);
            }
        }

        int removedIdx = filteredItems.indexOf(target);
        if (removedIdx >= 0) {
            filteredItems.remove(removedIdx);
            adapter.notifyItemRemoved(removedIdx);
        } else {
            for (int i = filteredItems.size() - 1; i >= 0; i--) {
                if (filteredItems.get(i).sameEntry(target)) {
                    filteredItems.remove(i);
                }
            }
            adapter.notifyDataSetChanged();
        }

        GitHubConfig cfg = Prefs.getConfig(this);
        updateEmptyState(false, null, cfg == null || !cfg.isValid());
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
