package logbook.internal.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Owned, bounded HTTP capture. An overflow invalidates the WHOLE exchange, never a prefix. */
public class CapturedHttpRequestResponse implements AutoCloseable {
    public static final long MAX_EXCHANGE_BYTES = 32L * 1024 * 1024;
    public static final int MAX_EXCHANGE_CHUNKS = 8192;
    private static final Cleaner CLEANER = Cleaner.create();
    private final Storage storage;
    private final Cleaner.Cleanable cleanup;

    public CapturedHttpRequestResponse() {
        this(CaptureBudget.SHARED, MAX_EXCHANGE_BYTES, MAX_EXCHANGE_CHUNKS);
    }
    CapturedHttpRequestResponse(CaptureBudget budget, long maxBytes, int maxChunks) {
        storage = new Storage(budget, maxBytes, maxChunks);
        cleanup = CLEANER.register(this, storage);
    }
    public void putOriginRequest(byte[] data) { putOriginRequest(ByteBuffer.wrap(data)); }
    void putOriginRequest(ByteBuffer data) { storage.append(data, true); }
    public void putOriginResponse(byte[] data) { putOriginResponse(data, 0, data.length); }
    void putOriginResponse(byte[] data, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, data.length);
        storage.append(ByteBuffer.wrap(data, offset, length), false);
    }
    public InputStream getOriginRequest() { return storage.open(true); }
    public InputStream getOriginResponse() { return storage.open(false); }
    public boolean isComplete() { return storage.isComplete(); }
    public String rejectionReason() { return storage.rejectionReason(); }
    boolean seal() { return storage.seal(); }
    public void clear() { cleanup.clean(); }
    @Override public void close() { clear(); }

    /** A Cleaner action must never reference its referent. Streams retain this storage independently. */
    private static final class Storage implements Runnable {
        private final CaptureBudget budget;
        private final long maxBytes;
        private final int maxChunks;
        private final List<byte[]> request = new ArrayList<>();
        private final List<byte[]> response = new ArrayList<>();
        private int references = 1;
        private long bytes;
        private long requestBytes;
        private int chunks;
        private boolean ownerClosed;
        private boolean sealed;
        private String rejection;
        Storage(CaptureBudget budget, long maxBytes, int maxChunks) {
            this.budget = Objects.requireNonNull(budget);
            if (maxBytes <= 0 || maxChunks <= 0) throw new IllegalArgumentException("Positive limits required");
            this.maxBytes = maxBytes; this.maxChunks = maxChunks;
        }
        synchronized void append(ByteBuffer source, boolean isRequest) {
            if (ownerClosed || sealed || rejection != null) return;
            int length = source.remaining();
            if (length == 0) return;
            if (length > maxBytes - bytes || chunks >= maxChunks
                    || (isRequest && length > CapturedHttpRequestResponseConst.MAX_POST_FIELD_SIZE - requestBytes)) {
                reject("exchange-limit"); return;
            }
            if (!budget.reserve(length)) { reject("shared-budget"); return; }
            // Reserve BEFORE allocation; Jetty buffers and caller arrays are never retained or modified.
            try {
                byte[] copy = new byte[length];
                source.asReadOnlyBuffer().get(copy);
                (isRequest ? request : response).add(copy);
                bytes += length; chunks++;
                if (isRequest) requestBytes += length;
            } catch (RuntimeException | Error failure) {
                budget.release(length, 1);
                reject("capture-failed");
                throw failure;
            }
        }
        private void reject(String reason) { rejection = reason; run(); }
        synchronized boolean isComplete() { return !ownerClosed && rejection == null; }
        synchronized String rejectionReason() { return rejection; }
        synchronized boolean seal() {
            if (!isComplete()) return false;
            sealed = true; return true;
        }
        synchronized InputStream open(boolean isRequest) {
            if (!isComplete()) throw new IllegalStateException("Capture is incomplete or closed");
            var cursor = new ByteArrayInputStream2(isRequest ? request : response);
            references++;
            return new LeasedStream(this, cursor);
        }
        @Override public synchronized void run() {
            if (!ownerClosed) { ownerClosed = true; release(); }
        }
        synchronized void release() {
            if (--references == 0) {
                request.clear(); response.clear();
                budget.release(bytes, chunks);
                bytes = 0; chunks = 0;
            }
        }
    }
    private static final class StreamState implements Runnable {
        private Storage storage;
        private InputStream delegate;
        StreamState(Storage storage, InputStream delegate) { this.storage = storage; this.delegate = delegate; }
        synchronized InputStream input() throws IOException {
            if (delegate == null) throw new IOException("Capture stream is closed");
            return delegate;
        }
        @Override public synchronized void run() {
            if (storage != null) {
                // Release the cursor's byte-array references before releasing the shared reservation.
                delegate = null;
                storage.release(); storage = null;
            }
        }
    }
    private static final class LeasedStream extends InputStream {
        private final StreamState state;
        private final Cleaner.Cleanable cleanup;
        LeasedStream(Storage owner, InputStream input) {
            state = new StreamState(owner, input);
            cleanup = CLEANER.register(this, state);
        }
        @Override public int read() throws IOException { synchronized (state) { return state.input().read(); } }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            synchronized (state) { return state.input().read(b, off, len); }
        }
        @Override public int available() throws IOException { synchronized (state) { return state.input().available(); } }
        @Override public boolean markSupported() { return true; }
        @Override public void mark(int limit) {
            synchronized (state) {
                try { state.input().mark(limit); }
                catch (IOException closed) { /* mark on a closed stream has no effect */ }
            }
        }
        @Override public void reset() throws IOException { synchronized (state) { state.input().reset(); } }
        @Override public void close() { cleanup.clean(); }
    }
}
