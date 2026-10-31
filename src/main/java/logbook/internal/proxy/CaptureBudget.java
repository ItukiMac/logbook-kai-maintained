package logbook.internal.proxy;

/** Shared raw-payload AND segment-count reservations; no blocking on forwarding threads. */
final class CaptureBudget {
    static final CaptureBudget SHARED = new CaptureBudget(128L * 1024 * 1024, 32768);
    private final long maxBytes;
    private final int maxChunks;
    private long bytes;
    private int chunks;

    CaptureBudget(long maxBytes, int maxChunks) {
        if (maxBytes <= 0 || maxChunks <= 0)
            throw new IllegalArgumentException("Positive limits required");
        this.maxBytes = maxBytes;
        this.maxChunks = maxChunks;
    }

    synchronized boolean reserve(int length) {
        if (length <= 0)
            throw new IllegalArgumentException("Positive length required");
        if (length > maxBytes - bytes || chunks >= maxChunks)
            return false;
        bytes += length;
        chunks++;
        return true;
    }

    synchronized void release(long length, int count) {
        if (length < 0 || count < 0 || length > bytes || count > chunks) {
            throw new IllegalStateException("Unbalanced capture reservation");
        }
        bytes -= length;
        chunks -= count;
    }

    synchronized long usedBytes() {
        return bytes;
    }

    synchronized int usedChunks() {
        return chunks;
    }
}
