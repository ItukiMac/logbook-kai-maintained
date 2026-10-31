package logbook.internal.net;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class DecodedBodyLimitTest {
    @Test
    void exactLimitAndZeroLengthReadsPreserveInputStreamContract() throws Exception {
        try (var in = new BoundedBodyInputStream(new ByteArrayInputStream(new byte[] { 1, 2 }), 2)) {
            assertEquals(0, in.read(new byte[0], 0, 0));
            assertArrayEquals(new byte[] { 1, 2 }, in.readAllBytes());
            assertEquals(-1, in.read());
        }
    }

    @Test
    void oversizedPlainAndCompressedBodiesThrowInsteadOfReturningAValidPrefix() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new GZIPOutputStream(bytes)) {
            zip.write(new byte[100]);
        }
        for (byte[] input : new byte[][] { new byte[100], bytes.toByteArray() }) {
            try (var decoded = ResponseMetaDataWrapper.decoded(new ByteArrayInputStream(input), 20)) {
                assertThrows(IOException.class, decoded::readAllBytes);
            }
        }
    }

    @Test
    void skipAndMarkResetCannotBypassTheDecodedLimit() throws Exception {
        try (var in = new BoundedBodyInputStream(new ByteArrayInputStream(new byte[11]), 10)) {
            in.mark(20);
            assertEquals(10, in.skip(10));
            assertThrows(IOException.class, in::read);
            in.reset();
            assertEquals(10, in.skip(10));
            assertThrows(IOException.class, () -> in.skip(1));
        }
    }
}
