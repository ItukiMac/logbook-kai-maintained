package logbook.internal.listener;

import static org.mockito.Mockito.*;
import java.util.stream.Stream;
import jakarta.json.Json;
import logbook.api.*;
import logbook.net.*;
import logbook.internal.kancolle.PortArrivalRuntime;
import org.junit.jupiter.api.Test;

class ArrivalApiDispatchTest {
    @API("/kcsapi/fixture-arrival")
    static class Good implements APIListenerSpi {
        public void accept(jakarta.json.JsonObject j, RequestMetaData q, ResponseMetaData r) {
        }
    }

    @API("/kcsapi/fixture-arrival")
    static class Bad implements APIListenerSpi {
        public void accept(jakarta.json.JsonObject j, RequestMetaData q, ResponseMetaData r) {
            throw new IllegalArgumentException("fixture");
        }
    }

    @Test
    void actualDispatcherNotifiesObserverAfterAllSuccessfulHandlers() {
        var req = mock(RequestMetaData.class);
        when(req.getRequestURI()).thenReturn("/kcsapi/fixture-arrival");
        var json = Json.createObjectBuilder().add("api_result", 1).build();
        try (var observed = mockStatic(PortArrivalRuntime.class)) {
            new APIListener(Stream.of(new Good())).send(req, mock(ResponseMetaData.class), json);
            observed.verify(() -> PortArrivalRuntime.observe("/kcsapi/fixture-arrival", json, req, true));
        }
    }

    @Test
    void actualDispatcherPropagatesPartialHandlerFailure() {
        var req = mock(RequestMetaData.class);
        when(req.getRequestURI()).thenReturn("/kcsapi/fixture-arrival");
        var json = Json.createObjectBuilder().build();
        try (var observed = mockStatic(PortArrivalRuntime.class)) {
            new APIListener(Stream.of(new Bad())).send(req, mock(ResponseMetaData.class), json);
            observed.verify(() -> PortArrivalRuntime.observe("/kcsapi/fixture-arrival", json, req, false));
        }
    }

    @Test
    void malformedApiResponseInvalidatesObservedDeparture() {
        var req = mock(RequestMetaData.class);
        var res = mock(ResponseMetaData.class);
        when(res.getResponseBody())
                .thenReturn(java.util.Optional.of(new java.io.ByteArrayInputStream("svdata={bad".getBytes())));
        try (var observed = mockStatic(PortArrivalRuntime.class)) {
            new APIListener(Stream.of(new Good())).accept(req, res);
            observed.verify(PortArrivalRuntime::invalidate);
        }
    }

}
