package logbook.internal.proxy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

class RequestContentListenerTest {
    private static HttpServletRequest requestWithAttributes() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        Map<String, Object> attributes = new HashMap<>();
        when(request.getAttribute(anyString())).thenAnswer(call -> attributes.get(call.getArgument(0)));
        doAnswer(call -> {
            attributes.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(request).setAttribute(anyString(), any());
        return request;
    }

    private static CapturedHttpRequestResponse captured(HttpServletRequest request) {
        return (CapturedHttpRequestResponse) request.getAttribute(
                CapturedHttpRequestResponseConst.CONTENT_HOLDER);
    }

    @Test void capturesOnlyRemainingBytesWithoutChangingBufferState() throws Exception {
        HttpServletRequest request = requestWithAttributes();
        ByteBuffer buffer = ByteBuffer.wrap(new byte[] {99, 1, 2, 3, 88});
        buffer.position(1).limit(4).mark();
        new RequestContentListener(request).onContent(null, buffer);
        assertEquals(1, buffer.position());
        assertEquals(4, buffer.limit());
        buffer.reset();
        assertEquals(1, buffer.position());
        assertArrayEquals(new byte[] {1, 2, 3}, captured(request).getOriginRequest().readAllBytes());
    }

    @Test void directReadOnlyBufferIsCopiedBeforeReuse() throws Exception {
        HttpServletRequest request = requestWithAttributes();
        ByteBuffer source = ByteBuffer.allocateDirect(4);
        source.put(new byte[] {9, 10, 11, 12}).flip();
        source.position(1).limit(3);
        ByteBuffer view = source.asReadOnlyBuffer();
        new RequestContentListener(request).onContent(null, view);
        source.put(1, (byte) 77);
        assertEquals(1, view.position());
        assertArrayEquals(new byte[] {10, 11}, captured(request).getOriginRequest().readAllBytes());
    }

    @Test void repeatedContentCallbacksPreserveRepeatedPayload() throws Exception {
        HttpServletRequest request = requestWithAttributes();
        RequestContentListener listener = new RequestContentListener(request);
        listener.onContent(null, ByteBuffer.wrap(new byte[] {1, 2}));
        listener.onContent(null, ByteBuffer.wrap(new byte[] {1, 2}));
        assertArrayEquals(new byte[] {1, 2, 1, 2}, captured(request).getOriginRequest().readAllBytes());
    }

    @Test void emptyChunkIsIgnoredButOversizedChunkInvalidatesTheWholeCapture() {
        HttpServletRequest request = requestWithAttributes();
        RequestContentListener listener = new RequestContentListener(request);
        listener.onContent(null, ByteBuffer.allocate(0));
        assertNull(captured(request));
        ByteBuffer oversized = ByteBuffer.allocate(CapturedHttpRequestResponseConst.MAX_POST_FIELD_SIZE + 1);
        listener.onContent(null, oversized);
        assertNotNull(captured(request));
        assertFalse(captured(request).isComplete());
        listener.onContent(null, ByteBuffer.wrap(new byte[] {1, 2}));
        assertFalse(captured(request).isComplete());
        assertThrows(IllegalStateException.class, () -> captured(request).getOriginRequest());
        assertEquals(0, oversized.position());
    }
}
