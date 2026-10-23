package com.bayram.githubfileupload;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.List;

public class HistoryActivity extends AppCompatActivity implements HistoryAdapter.Listener {

    private RecyclerView recycler;
    private TextView tvEmpty;

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
        tvEmpty = findViewById(R.id.tvEmpty);

        recycler.setLayoutManager(new LinearLayoutManager(this));
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.history_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_clear_history) {
            Prefs.clearHistory(this);
            refresh();
            Toast.makeText(this, R.string.history_deleted, Toast.LENGTH_SHORT).show();
            return true;
        }
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void refresh() {
        List<UploadItem> items = Prefs.getHistory(this);
        recycler.setAdapter(new HistoryAdapter(this, items, this));
        tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onCopyDownload(UploadItem item) {
        copy(item.downloadUrl);
    }

    @Override
    public void onCopyPreview(UploadItem item) {
        copy(item.previewUrl.isEmpty() ? item.previewRawUrl : item.previewUrl);
    }

    @Override
    public void onDelete(UploadItem item) {
        GitHubConfig cfg = Prefs.getConfig(this);
        if (!item.downloadUrl.isEmpty() && cfg.isValid()) {
            new GitHubReleaseManager().deleteRelease(cfg, item.tag, new GitHubReleaseManager.Callback<Boolean>() {
                @Override
                public void onSuccess(Boolean aBoolean) {
                    Prefs.removeHistory(HistoryActivity.this, item.id);
                    refresh();
                    Toast.makeText(HistoryActivity.this, R.string.history_deleted,
                            Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(String error) {
                    Toast.makeText(HistoryActivity.this, error, Toast.LENGTH_LONG).show();
                }
            });
        } else {
            Prefs.removeHistory(this, item.id);
            refresh();
        }
    }

    @Override
    public void onOpen(UploadItem item) {
        if (!item.hasPreview()) {
            return;
        }
        String url = item.previewUrl.isEmpty() ? item.previewRawUrl : item.previewUrl;
        if (item.isImage() || item.isVideo()) {
            Intent i = new Intent(this, PreviewActivity.class);
            i.putExtra(PreviewActivity.EXTRA_URL, url);
            i.putExtra(PreviewActivity.EXTRA_MIME, item.mime);
            i.putExtra(PreviewActivity.EXTRA_NAME, item.name);
            startActivity(i);
        } else {
            openBrowser(url);
        }
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
