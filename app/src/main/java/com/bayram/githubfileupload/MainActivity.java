package com.bayram.githubfileupload;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;

public class MainActivity extends AppCompatActivity implements UploadChoiceBottomSheet.Listener {

    private MaterialToolbar toolbar;
    private View fileEmptyState;
    private View fileSelectedState;
    private ImageView fileIcon;
    private ImageView fileThumb;
    private TextView tvFileName;
    private TextView tvFileMeta;
    private TextView tvStatus;
    private TextView tvIdleStatus;
    private Button btnUpload;
    private View progressBox;
    private ProgressBar progressBar;
    private View downloadCard;
    private View previewCard;
    private TextView tvDownloadUrl;
    private TextView tvPreviewUrl;
    private TextInputEditText etTag;

    private GitHubConfig config;
    private Uri pickedUri;
    private String pickedName = "";
    private String pickedMime = "";
    private File pickedFile;
    private UploadItem lastItem;
    private boolean uploading;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String[]> filePicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onFilePicked);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> openConfig());

        fileEmptyState = findViewById(R.id.fileEmptyState);
        fileSelectedState = findViewById(R.id.fileSelectedState);
        fileIcon = findViewById(R.id.fileIcon);
        fileThumb = findViewById(R.id.fileThumb);
        tvFileName = findViewById(R.id.tvFileName);
        tvFileMeta = findViewById(R.id.tvFileMeta);
        tvStatus = findViewById(R.id.tvStatus);
        tvIdleStatus = findViewById(R.id.tvIdleStatus);
        btnUpload = findViewById(R.id.btnUpload);
        progressBox = findViewById(R.id.progressBox);
        progressBar = findViewById(R.id.progressBar);
        downloadCard = findViewById(R.id.downloadCard);
        previewCard = findViewById(R.id.previewCard);
        tvDownloadUrl = findViewById(R.id.tvDownloadUrl);
        tvPreviewUrl = findViewById(R.id.tvPreviewUrl);
        etTag = findViewById(R.id.etTag);

        config = Prefs.getConfig(this);
        if (etTag.getText() == null || etTag.getText().toString().isEmpty()) {
            etTag.setText(R.string.tag_default);
        }

        fileEmptyState.setOnClickListener(v -> pickFile());
        findViewById(R.id.btnChangeFile).setOnClickListener(v -> pickFile());
        btnUpload.setOnClickListener(v -> onUploadClick());

        findViewById(R.id.btnCopyDownload).setOnClickListener(v -> copy(tvDownloadUrl.getText().toString()));
        findViewById(R.id.btnOpenDownload).setOnClickListener(v -> openBrowser(tvDownloadUrl.getText().toString()));
        findViewById(R.id.btnShareDownload).setOnClickListener(v -> share(tvDownloadUrl.getText().toString()));

        findViewById(R.id.btnCopyPreview).setOnClickListener(v -> copy(tvPreviewUrl.getText().toString()));
        findViewById(R.id.btnOpenPreview).setOnClickListener(v -> onOpenPreview());
        findViewById(R.id.btnSharePreview).setOnClickListener(v -> share(tvPreviewUrl.getText().toString()));

        findViewById(R.id.btnHistory).setOnClickListener(v ->
                startActivity(new Intent(this, HistoryActivity.class)));
        findViewById(R.id.btnSettings).setOnClickListener(v -> openConfig());

        updateIdleStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        config = Prefs.getConfig(this);
        updateIdleStatus();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_history) {
            startActivity(new Intent(this, HistoryActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateIdleStatus() {
        if (uploading) {
            return;
        }
        boolean ready = pickedUri != null && config != null && config.isValid();
        tvIdleStatus.setText(ready ? R.string.status_ready : R.string.status_waiting);
        btnUpload.setEnabled(pickedUri != null);
    }

    private void pickFile() {
        filePicker.launch(new String[]{"*/*"});
    }

    private void onFilePicked(Uri uri) {
        if (uri == null) {
            return;
        }
        pickedUri = uri;
        pickedName = FileUtils.nameOf(this, uri);
        pickedMime = FileUtils.mimeOf(this, uri);
        pickedFile = null;

        fileEmptyState.setVisibility(View.GONE);
        fileSelectedState.setVisibility(View.VISIBLE);
        tvFileName.setText(pickedName);
        tvFileMeta.setText(pickedMime);

        if (FileUtils.isImage(pickedMime)) {
            fileIcon.setVisibility(View.GONE);
            fileThumb.setVisibility(View.VISIBLE);
            ImageLoader.load(this, fileThumb, uri.toString(), 0);
        } else {
            fileThumb.setVisibility(View.GONE);
            fileIcon.setVisibility(View.VISIBLE);
            fileIcon.setImageResource(iconForMime(pickedMime));
        }

        hideCards();
        updateIdleStatus();

        new Thread(() -> {
            try {
                pickedFile = FileUtils.copyToCache(MainActivity.this, uri, pickedName);
            } catch (Exception e) {
                pickedFile = null;
            }
            ui.post(this::onFileReady);
        }).start();
    }

    private void onFileReady() {
        if (pickedFile != null) {
            tvFileMeta.setText(FileUtils.formatSize(pickedFile.length()) + " · " + pickedMime);
        } else {
            tvFileMeta.setText(pickedMime + " · ?");
            toast(R.string.error_missing_file);
        }
        updateIdleStatus();
    }

    private int iconForMime(String mime) {
        String m = mime == null ? "" : mime.toLowerCase();
        if (m.startsWith("image/")) return R.drawable.ic_image;
        if (m.startsWith("video/")) return R.drawable.ic_video;
        if (m.startsWith("audio/")) return R.drawable.ic_audio;
        if (m.contains("pdf")) return R.drawable.ic_text_snippet;
        return R.drawable.ic_insert_drive_file;
    }

    private void onUploadClick() {
        if (pickedUri == null || pickedFile == null) {
            toast(R.string.status_waiting);
            return;
        }
        if (config == null || !config.isValid()) {
            toast(R.string.error_not_configured);
            openConfig();
            return;
        }
        new UploadChoiceBottomSheet().show(getSupportFragmentManager(), "upload-choice");
    }

    @Override
    public void onChoice(int mode) {
        if (pickedFile == null) {
            return;
        }
        startUpload(mode);
    }

    private void startUpload(int mode) {
        uploading = true;
        btnUpload.setEnabled(false);
        progressBox.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(false);
        progressBar.setProgress(0);
        tvStatus.setText(R.string.status_uploading);
        tvIdleStatus.setVisibility(View.GONE);

        final File file = pickedFile;
        final String tag = etTag.getText() == null ? "latest" : etTag.getText().toString();
        new UploadService().upload(config, file, pickedName, pickedMime, tag, mode,
                new UploadService.Callback() {
                    @Override
                    public void onProgress(int percent) {
                        progressBar.setProgress(Math.max(0, Math.min(100, percent)));
                    }

                    @Override
                    public void onSuccess(UploadItem item) {
                        ui.post(() -> onUploadSuccess(item));
                    }

                    @Override
                    public void onError(String error) {
                        ui.post(() -> onUploadError(error));
                    }
                });
    }

    private void onUploadSuccess(UploadItem item) {
        uploading = false;
        progressBox.setVisibility(View.GONE);
        tvIdleStatus.setVisibility(View.VISIBLE);
        tvIdleStatus.setText(R.string.status_done);

        lastItem = item;
        item.sourceUri = pickedUri == null ? "" : pickedUri.toString();
        if (item.hasDownload()) {
            downloadCard.setVisibility(View.VISIBLE);
            tvDownloadUrl.setText(item.downloadUrl);
        } else {
            downloadCard.setVisibility(View.GONE);
        }
        if (item.hasPreview()) {
            previewCard.setVisibility(View.VISIBLE);
            tvPreviewUrl.setText(item.previewUrl);
        } else {
            previewCard.setVisibility(View.GONE);
        }

        Prefs.addHistory(this, item);
        toast(R.string.status_done);
        btnUpload.setEnabled(true);
        updateIdleStatus();
    }

    private void onUploadError(String error) {
        uploading = false;
        progressBox.setVisibility(View.GONE);
        tvIdleStatus.setVisibility(View.VISIBLE);
        tvIdleStatus.setText(R.string.status_failed);
        if (error == null || error.isEmpty()) {
            toast(R.string.error_network);
        } else {
            toast(error);
        }
        btnUpload.setEnabled(true);
        updateIdleStatus();
    }

    private void hideCards() {
        downloadCard.setVisibility(View.GONE);
        previewCard.setVisibility(View.GONE);
    }

    private void onOpenPreview() {
        UploadItem item = lastItem;
        if (item == null) {
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
            toast(R.string.copied_nothing);
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("link", text));
            toast(R.string.copied);
        } catch (Exception e) {
            toast(R.string.copied_failed);
        }
    }

    private void share(String text) {
        if (text == null || text.isEmpty()) {
            toast(R.string.copied_nothing);
            return;
        }
        Intent i = new Intent(android.content.Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(android.content.Intent.EXTRA_TEXT, text);
        startActivity(android.content.Intent.createChooser(i, getString(R.string.share)));
    }

    private void openBrowser(String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        try {
            startActivity(new Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            toast(R.string.preview_unsupported);
        }
    }

    private void openConfig() {
        startActivity(new Intent(this, ConfigActivity.class));
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
}
