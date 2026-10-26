package logbook.internal.proxy;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** An independent, markable cursor over captured byte-array segments. */
public class ByteArrayInputStream2 extends InputStream {
    private final List<byte[]> chunks;
    private int index;
    private int offset;
    private int markIndex;
    private int markOffset;

    public ByteArrayInputStream2(byte[] bytes) { this(Collections.singletonList(bytes)); }
    public ByteArrayInputStream2(byte[]... bytes) { this(Arrays.asList(bytes)); }
    public ByteArrayInputStream2(List<byte[]> bytes) { this.chunks = List.copyOf(bytes); }

    private boolean advance() {
        while (index < chunks.size() && offset == chunks.get(index).length) {
            index++;
            offset = 0;
        }
        return index < chunks.size();
    }
    @Override public int read() {
        if (!advance()) return -1;
        return chunks.get(index)[offset++] & 0xff;
    }
    @Override public int read(byte[] bytes, int off, int len) {
        Objects.requireNonNull(bytes);
        Objects.checkFromIndexSize(off, len, bytes.length);
        if (len == 0) return 0; // also required at EOF
        if (!advance()) return -1;
        int copied = 0;
        while (copied < len && advance()) {
            byte[] current = chunks.get(index);
            int count = Math.min(len - copied, current.length - offset);
            System.arraycopy(current, offset, bytes, off + copied, count);
            offset += count;
            copied += count;
        }
        return copied;
    }
    @Override public int available() {
        long count = -offset;
        for (int n = index; n < chunks.size(); n++) {
            count += chunks.get(n).length;
            if (count >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        }
        return (int) Math.max(0, count);
    }
    @Override public void mark(int readlimit) { markIndex = index; markOffset = offset; }
    @Override public boolean markSupported() { return true; }
    @Override public void reset() { index = markIndex; offset = markOffset; }
    @Override public void close() { /* Like ByteArrayInputStream, no external resources. */ }
}
