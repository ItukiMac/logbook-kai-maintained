package logbook.internal.kancolle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import logbook.bean.*;
import logbook.net.RequestMetaData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PortArrivalRuntimeTest {
    @TempDir
    Path temp;
    private static final long T = 1_700_000_000_000L;

    private static JsonObject event(int result, int event) {
        return Json.createObjectBuilder().add("api_result", result).add("api_data", Json.createObjectBuilder()
                .add("api_maparea_id", 1).add("api_mapinfo_no", 6).add("api_no", event == 9 ? 0 : 2)
                .add("api_event_id", event)).build();
    }

    private void scenario(java.util.function.BiConsumer<PortArrivalRuntime, RequestMetaData> action) throws Exception {
        var store = new PortArrivalStore(temp);
        var runtime = new PortArrivalRuntime(() -> store, Clock.fixed(Instant.ofEpochMilli(T), ZoneOffset.UTC));
        var condition = new AppCondition();
        condition.setMapStart(true);
        condition.setDeckId(1);
        var decks = new DeckPortCollection();
        var deck = new DeckPort();
        deck.setShip(List.of(11, 12, -1, -1, -1, -1));
        decks.getDeckPortMap().put(1, deck);
        var ships = new ShipCollection();
        var s1 = new Ship();
        s1.setShipId(101);
        var s2 = new Ship();
        s2.setShipId(102);
        ships.getShipMap().put(11, s1);
        ships.getShipMap().put(12, s2);
        var req = mock(RequestMetaData.class);
        when(req.getParameter("api_deck_id")).thenReturn("1");
        try (var a = mockStatic(AppCondition.class);
                var d = mockStatic(DeckPortCollection.class);
                var s = mockStatic(ShipCollection.class)) {
            a.when(AppCondition::get).thenReturn(condition);
            d.when(DeckPortCollection::get).thenReturn(decks);
            s.when(ShipCollection::get).thenReturn(ships);
            action.accept(runtime, req);
            assertEquals(List.of(11, 12, -1, -1, -1, -1), deck.getShip());
        }
    }

    @Test
    void normalApiSequenceWritesOnceWithoutRawTraffic() throws Exception {
        scenario((r, q) -> {
            r.accept("/kcsapi/api_req_map/start", event(1, 4), q, true);
            r.accept("/kcsapi/api_req_map/next", event(1, 4), q, true);
            r.accept("/kcsapi/api_req_map/next", event(1, 9), q, true);
            r.accept("/kcsapi/api_req_map/next", event(1, 9), q, true);
        });
        var records = new PortArrivalStore(temp).readRange(T - 1, T + 1);
        assertEquals(1, records.size());
        assertEquals(List.of(101, 102), records.getFirst().shipMasterIds());
    }

    @Test
    void returningToPortAfterBattleIsNotGoalEvidence() throws Exception {
        scenario((r, q) -> {
            r.accept("/kcsapi/api_req_map/start", event(1, 4), q, true);
            r.accept("/kcsapi/api_port/port", event(1, 4), q, true);
            r.accept("/kcsapi/api_req_map/next", event(1, 9), q, true);
        });
        assertTrue(new PortArrivalStore(temp).readRange(T - 1, T + 1).isEmpty());
    }

    @Test
    void failedGameResponseInvalidatesObservedSession() throws Exception {
        scenario((r, q) -> {
            r.accept("/kcsapi/api_req_map/start", event(1, 4), q, true);
            r.accept("/kcsapi/api_req_map/next", event(0, 9), q, true);
            r.accept("/kcsapi/api_req_map/next", event(1, 9), q, true);
        });
        assertTrue(new PortArrivalStore(temp).readRange(T - 1, T + 1).isEmpty());
    }

    @Test
    void partiallyFailedHandlersCannotPublishEvidence() throws Exception {
        scenario((r, q) -> {
            r.accept("/kcsapi/api_req_map/start", event(1, 4), q, true);
            r.accept("/kcsapi/api_req_sortie/battle", event(1, 4), q, false);
            r.accept("/kcsapi/api_req_map/next", event(1, 9), q, true);
        });
        assertTrue(new PortArrivalStore(temp).readRange(T - 1, T + 1).isEmpty());
    }

    @Test
    void freshRuntimeCannotBackfillAnUnobservedDeparture() throws Exception {
        scenario((r, q) -> r.accept("/kcsapi/api_req_map/next", event(1, 9), q, true));
        assertTrue(new PortArrivalStore(temp).readRange(T - 1, T + 1).isEmpty());
    }

    @Test
    void retreatApiInvalidatesRatherThanInventingSurvivorPositions() throws Exception {
        scenario((r, q) -> {
            r.accept("/kcsapi/api_req_map/start", event(1, 4), q, true);
            r.accept("/kcsapi/api_req_sortie/goback_port", event(1, 4), q, true);
            r.accept("/kcsapi/api_req_map/next", event(1, 9), q, true);
        });
        assertTrue(new PortArrivalStore(temp).readRange(T - 1, T + 1).isEmpty());
    }

}
