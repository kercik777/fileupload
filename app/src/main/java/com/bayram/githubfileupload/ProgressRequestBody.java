package com.bayram.githubfileupload;

import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.Buffer;
import okio.BufferedSink;
import okio.ForwardingSink;
import okio.Okio;
import okio.Sink;

/**
 * RequestBody с отслеживанием прогресса загрузки.
 */
public class ProgressRequestBody extends RequestBody {

    public interface ProgressListener {
        void onProgress(long bytesWritten, long totalBytes);
    }

    private final RequestBody delegate;
    private final ProgressListener listener;

    public ProgressRequestBody(RequestBody delegate, ProgressListener listener) {
        this.delegate = delegate;
        this.listener = listener;
    }

    @Override
    public MediaType contentType() {
        return delegate.contentType();
    }

    @Override
    public long contentLength() {
        return delegate.contentLength();
    }

    @Override
    public void writeTo(BufferedSink sink) throws IOException {
        CountingSink countingSink = new CountingSink(sink);
        BufferedSink buffered = Okio.buffer(countingSink);
        delegate.writeTo(buffered);
        buffered.flush();
    }

    private final class CountingSink extends ForwardingSink {

        private long bytesWritten = 0;

        CountingSink(Sink delegate) {
            super(delegate);
        }

        @Override
        public void write(Buffer source, long byteCount) throws IOException {
            super.write(source, byteCount);
            bytesWritten += byteCount;
            if (listener != null) {
                listener.onProgress(bytesWritten, contentLength());
            }
        }
    }
}
