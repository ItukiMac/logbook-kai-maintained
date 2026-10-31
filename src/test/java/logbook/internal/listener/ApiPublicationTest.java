package logbook.internal.listener;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.stream.Stream;
import jakarta.json.*;
import logbook.api.*;
import logbook.core.*;
import logbook.net.*;
import org.junit.jupiter.api.Test;

class ApiPublicationTest {
    static final List<String> ORDER = new ArrayList<>();

    @API("/kcsapi/fixture")
    static class Specific implements APIListenerSpi {
        public void accept(JsonObject j, RequestMetaData q, ResponseMetaData r) {
            ORDER.add("specific");
        }
    }

    static class All implements APIListenerSpi {
        public void accept(JsonObject j, RequestMetaData q, ResponseMetaData r) {
            ORDER.add("all");
        }
    }

    @API("/kcsapi/fixture")
    static class Broken implements APIListenerSpi {
        public void accept(JsonObject j, RequestMetaData q, ResponseMetaData r) {
            throw new IllegalArgumentException("fixture");
        }
    }

    private RequestMetaData request(String path) {
        var req = mock(RequestMetaData.class);
        when(req.getRequestURI()).thenReturn(path);
        return req;
    }

    @Test
    void allApiSubscriberIsRegisteredAndHandlersFinishBeforePublication() throws Exception {
        ORDER.clear();
        var api = new APIListener(Stream.of(new All(), new Specific()));
        assertTrue(api.test(request("/kcsapi/unlisted")));
        assertFalse(api.test(request("/not-api")));
        try (var s = LogBookCoreServices.gameStateView().subscribe(v -> {
            ORDER.add("published");
            assertTrue(v.updateSucceeded());
        })) {
            api.send(request("/kcsapi/fixture"), mock(ResponseMetaData.class), Json.createObjectBuilder().build());
        }
        assertEquals(List.of("specific", "all", "published"), ORDER);
    }

    @Test
    void brokenHandlerDoesNotSuppressFollowingHandlersAndMarksPartial() {
        ORDER.clear();
        var api = new APIListener(Stream.of(new Broken(), new Specific(), new All()));
        api.send(request("/kcsapi/fixture"), mock(ResponseMetaData.class), Json.createObjectBuilder().build());
        assertEquals(List.of("specific", "all"), ORDER);
        assertFalse(LogBookCoreServices.gameStateView().snapshot().updateSucceeded());
    }
}
