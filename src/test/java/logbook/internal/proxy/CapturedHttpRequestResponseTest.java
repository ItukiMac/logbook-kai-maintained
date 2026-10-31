package logbook.internal.proxy;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CapturedHttpRequestResponseTest {
    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void equalAdjacentRequestChunksAreNotDuplicates() throws Exception {
        CapturedHttpRequestResponse captured = new CapturedHttpRequestResponse();
        captured.putOriginRequest(bytes("abc"));
        captured.putOriginRequest(bytes("abc"));
        assertArrayEquals(bytes("abcabc"), captured.getOriginRequest().readAllBytes());
    }

    @Test
    void requestAndResponseHaveIndependentOrderedContents() throws Exception {
        CapturedHttpRequestResponse captured = new CapturedHttpRequestResponse();
        captured.putOriginRequest(bytes("request="));
        captured.putOriginResponse(bytes("response"));
        captured.putOriginRequest(bytes("value"));
        captured.putOriginResponse(bytes("response"));
        assertArrayEquals(bytes("request=value"), captured.getOriginRequest().readAllBytes());
        assertArrayEquals(bytes("responseresponse"), captured.getOriginResponse().readAllBytes());
    }

    @Test
    void listenerStreamsRemainIndependentAndReadableAfterHolderCleanup() throws Exception {
        CapturedHttpRequestResponse captured = new CapturedHttpRequestResponse();
        captured.putOriginRequest(bytes("abcdef"));
        InputStream first = captured.getOriginRequest();
        InputStream second = captured.getOriginRequest();
        assertEquals('a', first.read());
        captured.clear();
        assertArrayEquals(bytes("bcdef"), first.readAllBytes());
        assertArrayEquals(bytes("abcdef"), second.readAllBytes());
    }

    @Test
    void binaryAndEmptyChunksPreserveBytes() throws Exception {
        CapturedHttpRequestResponse captured = new CapturedHttpRequestResponse();
        captured.putOriginRequest(new byte[0]);
        captured.putOriginRequest(new byte[] { 0, (byte) 255 });
        captured.putOriginRequest(new byte[] { 0, (byte) 255 });
        assertArrayEquals(new byte[] { 0, (byte) 255, 0, (byte) 255 },
                captured.getOriginRequest().readAllBytes());
    }
}
