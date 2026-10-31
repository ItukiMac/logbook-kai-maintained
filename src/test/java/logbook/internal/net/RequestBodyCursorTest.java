package logbook.internal.net;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequestBodyCursorTest {
    @Test
    void parameterDecodePreservesEscapedSeparatorsAndRawBodyCursor() throws Exception {
        String text = "x=a%26b%3Dc&x=%E3%81%82+z";
        var body = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        var request = new RequestMetaDataWrapper();
        request.set(body);
        assertEquals(List.of("a&b=c", "あ z"), request.getParameterMap().get("x"));
        assertEquals(text, new String(request.getRequestBody().orElseThrow().readAllBytes(), StandardCharsets.UTF_8));
    }
}
