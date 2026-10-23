package com.bayram.githubfileupload;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.util.Locale;

/**
 * Внутриприложенный предпросмотр фото и видео по ссылке.
 * Управление видео — таплям ( play / pause ), без внешнях контроллеров.
 */
public class PreviewActivity extends AppCompatActivity {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_MIME = "mime";
    public static final String EXTRA_NAME = "name";

    private ImageView imageView;
    private VideoView videoView;
    private ProgressBar progressBar;
    private TextView tvError;
    private MaterialButton btnOpenBrowser;

    private String url;
    private String mime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preview);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        imageView = findViewById(R.id.imageView);
        videoView = findViewById(R.id.videoView);
        progressBar = findViewById(R.id.progressBar);
        tvError = findViewById(R.id.tvError);
        btnOpenBrowser = findViewById(R.id.btnOpenBrowser);

        url = getIntent().getStringExtra(EXTRA_URL);
        mime = getIntent().getStringExtra(EXTRA_MIME);
        String name = getIntent().getStringExtra(EXTRA_NAME);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(name == null || name.isEmpty()
                    ? getString(R.string.title_preview) : name);
        }

        btnOpenBrowser.setOnClickListener(v -> openBrowser(url));

        if (url == null || url.isEmpty()) {
            showError(getString(R.string.preview_unsupported));
            return;
        }

        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (m.startsWith("image/")) {
            imageView.setVisibility(View.VISIBLE);
            progressBar.setVisibility(View.VISIBLE);
            tvError.setVisibility(View.GONE);
            ImageLoader.load(this, imageView, url, R.drawable.bg_thumb, 1600,
                    new ImageLoader.Listener() {
                        @Override
                        public void onReady() {
                            progressBar.setVisibility(View.GONE);
                        }

                        @Override
                        public void onError() {
                            progressBar.setVisibility(View.GONE);
                            showError(getString(R.string.preview_loading));
                        }
                    });
        } else if (m.startsWith("video/")) {
            videoView.setVisibility(View.VISIBLE);
            progressBar.setVisibility(View.VISIBLE);
            tvError.setVisibility(View.GONE);
            videoView.setVideoURI(Uri.parse(url));
            videoView.setOnPreparedListener(mp -> progressBar.setVisibility(View.GONE));
            videoView.setOnCompletionListener(mp -> progressBar.setVisibility(View.GONE));
            videoView.setOnErrorListener((mp, what, extra) -> {
                progressBar.setVisibility(View.GONE);
                showError(getString(R.string.preview_unsupported));
                return true;
            });
            videoView.setOnTouchListener(v -> {
                if (videoView.isPlaying()) {
                    videoView.pause();
                } else {
                    videoView.start();
                }
                return true;
            });
            videoView.start();
        } else {
            showError(getString(R.string.preview_unsupported));
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showError(String text) {
        progressBar.setVisibility(View.GONE);
        tvError.setVisibility(View.VISIBLE);
        tvError.setText(text);
    }

    private void openBrowser(String link) {
        if (link == null || link.isEmpty()) {
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, R.string.preview_unsupported, Toast.LENGTH_SHORT).show();
        }
    }
}
