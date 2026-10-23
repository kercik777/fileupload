package com.bayram.githubfileupload;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
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
import androidx.core.graphics.drawable.DrawableCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;

public class MainActivity extends AppCompatActivity
        implements UploadChoiceBottomSheet.Listener, FileFormatDialogFragment.Listener {

    private static final int REQ_STORAGE_PERMISSION = 3000;

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

    private Boolean checkPermission1 = false;
    private GitHubConfig config;
    private Uri pickedUri;
    private String pickedName = "";
    private String pickedMime = "";
    private File pickedFile;
    private UploadItem lastItem;
    private boolean uploading;

    private final Handler ui = new Handler(Looper.getMainLooper());

    /** Системный файловый менеджер (SAF) для выбора любого файла. */
    private final ActivityResultLauncher<String[]> filePicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    try {
                        getContentResolver().takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception ignored) {
                    }
                    onFilePicked(uri);
                }
            });

    /** Прямой пикер галереи для выбора Фотографии или Видео. */
    private final ActivityResultLauncher<Intent> mediaGalleryPicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        onFilePicked(uri);
                    }
                }
            });

    /** Резервный пикер по MIME-типу, если системная Галерея отсутствует. */
    private final ActivityResultLauncher<String> fallbackContentPicker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::onFilePicked);

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
        fileThumb.setClipToOutline(true);
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

        fileEmptyState.setOnClickListener(v -> showFormatDialog());
        findViewById(R.id.btnChangeFile).setOnClickListener(v -> showFormatDialog());
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
        checkStoragePermissionOnEnter();
    }

    /**
     * Проверка полного доступа к файлам при входе в приложение:
     * Android 11+ -> ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
     * Android 6..10 -> WRITE_EXTERNAL_STORAGE.
     */
    private void checkStoragePermissionOnEnter() {
        checkPermission1 = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // For Android 11+
            if (Environment.isExternalStorageManager()) {
                checkPermission1 = true;
            } else {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    Uri uri = Uri.fromParts("package", getPackageName(), null);
                    intent.setData(uri);
                    startActivity(intent);
                } catch (Exception e) {
                    try {
                        Intent fallback = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        startActivity(fallback);
                    } catch (Exception ignored) {
                    }
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // For Android 6 to 10
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_DENIED) {
                requestPermissions(
                        new String[]{
                                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                Manifest.permission.READ_EXTERNAL_STORAGE
                        },
                        REQ_STORAGE_PERMISSION);
            } else {
                checkPermission1 = true;
            }
        } else {
            checkPermission1 = true;
        }
    }

    private void refreshPermissionState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            checkPermission1 = Environment.isExternalStorageManager();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkPermission1 = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        } else {
            checkPermission1 = true;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE_PERMISSION) {
            checkPermission1 = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissionState();
        config = Prefs.getConfig(this);
        updateIdleStatus();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        tintMenuIconsWhite(menu);
        return true;
    }

    private void tintMenuIconsWhite(Menu menu) {
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            Drawable icon = item.getIcon();
            if (icon != null) {
                Drawable wrapped = DrawableCompat.wrap(icon.mutate());
                DrawableCompat.setTint(wrapped, Color.WHITE);
                item.setIcon(wrapped);
            }
        }
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

    private void showFormatDialog() {
        new FileFormatDialogFragment().show(getSupportFragmentManager(), "file-format");
    }

    @Override
    public void onFormatChosen(int format) {
        if (format == FileFormatDialogFragment.FORMAT_PHOTO) {
            launchPhotoPicker();
        } else if (format == FileFormatDialogFragment.FORMAT_VIDEO) {
            launchVideoPicker();
        } else {
            filePicker.launch(new String[]{"*/*"});
        }
    }

    private void launchPhotoPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            intent.setType("image/*");
            mediaGalleryPicker.launch(intent);
        } catch (Exception e) {
            fallbackContentPicker.launch("image/*");
        }
    }

    private void launchVideoPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
            intent.setType("video/*");
            mediaGalleryPicker.launch(intent);
        } catch (Exception e) {
            fallbackContentPicker.launch("video/*");
        }
    }

    private void onFilePicked(Uri uri) {
        if (uri == null) {
            return;
        }
        pickedUri = uri;
        pickedMime = FileUtils.mimeOf(this, uri);
        pickedName = FileUtils.ensureExtension(FileUtils.nameOf(this, uri), pickedMime);
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
                        ui.post(() -> progressBar.setProgress(Math.max(0, Math.min(100, percent))));
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
            tvPreviewUrl.setText(item.bestPreviewUrl());
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
        String url = item.bestPreviewUrl();
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
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(i, getString(R.string.share)));
    }

    private void openBrowser(String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
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
