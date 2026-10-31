package logbook.internal.proxy;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ByteArrayInputStream2Test {
    @Test
    void zeroLengthReadAtEofReturnsZero() {
        var in = new ByteArrayInputStream2(new byte[0]);
        assertEquals(-1, in.read());
        assertEquals(0, in.read(new byte[2], 2, 0));
    }

    @Test
    void invalidArgumentsAreStillRejectedAtEof() {
        var in = new ByteArrayInputStream2(new byte[0]);
        assertThrows(NullPointerException.class, () -> in.read(null, 0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> in.read(new byte[1], 2, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> in.read(new byte[1], 0, -1));
    }

    @Test
    void markAndResetWorkAcrossEmptySegments() throws Exception {
        var in = new ByteArrayInputStream2(new byte[] { 1, 2 }, new byte[0], new byte[] { 3, 4 });
        assertEquals(1, in.read());
        in.mark(100);
        assertArrayEquals(new byte[] { 2, 3, 4 }, in.readAllBytes());
        in.reset();
        assertArrayEquals(new byte[] { 2, 3, 4 }, in.readAllBytes());
    }

    @Test
    void resetWithoutMarkReturnsToBeginning() throws Exception {
        var in = new ByteArrayInputStream2(new byte[] { 1 }, new byte[] { 2 });
        in.readAllBytes();
        in.reset();
        assertArrayEquals(new byte[] { 1, 2 }, in.readAllBytes());
    }

    @Test
    void availableCountsAllRemainingSegments() throws Exception {
        var in = new ByteArrayInputStream2(new byte[] { 1 }, new byte[0], new byte[] { 2, 3 });
        assertEquals(3, in.available());
        assertEquals(1, in.read());
        assertEquals(2, in.available());
        assertArrayEquals(new byte[] { 2, 3 }, in.readAllBytes());
        assertEquals(0, in.available());
    }

    @Test
    void cursorSnapshotsSegmentListAndPreservesUnsignedBytes() throws Exception {
        var chunks = new ArrayList<>(List.of(new byte[] { (byte) 255 }, new byte[] { 0 }));
        var in = new ByteArrayInputStream2(chunks);
        chunks.clear();
        assertEquals(255, in.read());
        assertEquals(0, in.read());
        assertEquals(-1, in.read());
    }

    @Test
    void bulkReadPreservesDestinationBoundaries() {
        var in = new ByteArrayInputStream2(new byte[] { 1 }, new byte[] { 2, 3 });
        byte[] output = { 9, 9, 9, 9, 9 };
        assertEquals(3, in.read(output, 1, 3));
        assertArrayEquals(new byte[] { 9, 1, 2, 3, 9 }, output);
    }
}
