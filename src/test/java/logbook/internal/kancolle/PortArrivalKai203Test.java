package logbook.internal.kancolle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import logbook.bean.AppCondition;
import logbook.bean.DeckPort;
import logbook.bean.DeckPortCollection;
import logbook.bean.Ship;
import logbook.bean.ShipCollection;
import logbook.net.RequestMetaData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

/**
 * KAI2-03: diagnostic reproductions for null-payload boundary/invalid journals.
 * These tests use synthetic observations and do not read or modify live data.
 */
class PortArrivalKai203Test {
    @TempDir Path temp;
    private static final long T = Instant.parse("2026-10-31T09:00:00Z").toEpochMilli();
    private static final String START = "/kcsapi/api_req_map/start";
    private static final String NEXT = "/kcsapi/api_req_map/next";
    private static final String PORT = "/kcsapi/api_port/port";
    private static final String MASTER = "/kcsapi/api_start2/getData";

    private static final class MovingClock extends Clock {
        long now = T;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
    }

    private static JsonObject response(int map, int cell, int event, Integer predecessor) {
        var node = Json.createObjectBuilder()
                .add("api_maparea_id", 1).add("api_mapinfo_no", map)
                .add("api_no", cell).add("api_event_id", event);
        if (predecessor != null) node.add("api_from_no", predecessor);
        return Json.createObjectBuilder().add("api_result", 1).add("api_data", node).build();
    }

    private static RequestMetaData request(long capturedAt) {
        var request = mock(RequestMetaData.class);
        when(request.getParameter("api_deck_id")).thenReturn("1");
        when(request.getHeader("x-koukainissikai-requestat"))
                .thenReturn(Optional.of(Long.toString(capturedAt)));
        return request;
    }

    private final class Fixture implements AutoCloseable {
        final MovingClock clock = new MovingClock();
        final PortArrivalStore store = new PortArrivalStore(temp.resolve("arrivals"));
        final PortArrivalRuntime runtime = new PortArrivalRuntime(() -> store, clock);
        final MockedStatic<AppCondition> stateMock = mockStatic(AppCondition.class);
        final MockedStatic<DeckPortCollection> deckMock = mockStatic(DeckPortCollection.class);
        final MockedStatic<ShipCollection> shipMock = mockStatic(ShipCollection.class);

        Fixture() {
            var state = new AppCondition();
            state.setMapStart(true);
            state.setDeckId(1);
            stateMock.when(AppCondition::get).thenReturn(state);
            var deck = new DeckPort();
            deck.setId(1);
            deck.setShip(List.of(11, 12, -1, -1, -1, -1));
            var decks = new DeckPortCollection();
            decks.getDeckPortMap().put(1, deck);
            deckMock.when(DeckPortCollection::get).thenReturn(decks);
            var ships = new ShipCollection();
            for (int index = 1; index <= 2; index++) {
                var ship = new Ship();
                ship.setId(10 + index);
                ship.setShipId(100 + index);
                ships.getShipMap().put(ship.getId(), ship);
            }
            shipMock.when(ShipCollection::get).thenReturn(ships);
        }

        void send(String uri, JsonObject data) {
            clock.now++;
            runtime.accept(uri, data, request(clock.now), true);
        }

        @Override public void close() {
            shipMock.close();
            deckMock.close();
            stateMock.close();
        }
    }

    @Test void nonTargetMapProducesBoundaryThenInvalidWithNullPayloadAndNoArrival() throws Exception {
        try (var f = new Fixture()) {
            f.send(PORT, response(6, 2, 4, null));
            f.send(START, response(5, 2, 4, null));
            var entries = f.store.journal().readLatest();
            assertEquals(List.of("BOUNDARY", "INVALID"),
                    entries.stream().map(PortArrivalJournal.Entry::kind).toList());
            assertEquals(List.of("SESSION_BOUNDARY", "INVALID_DEPARTURE"),
                    entries.stream().map(PortArrivalJournal.Entry::reason).toList());
            assertTrue(entries.stream().allMatch(e -> e.payload().isNull()));
            assertTrue(f.store.readRange(T, T + 100).isEmpty());
        }
    }

    @Test void observedTargetMapStartAndConnectedGoalCreateExactlyOneArrival() throws Exception {
        try (var f = new Fixture()) {
            f.send(PORT, response(6, 2, 4, null));
            f.send(START, response(6, 2, 4, null));
            f.send(NEXT, response(6, 0, 9, 2));
            f.send(NEXT, response(6, 0, 9, 2));
            var entries = f.store.journal().readLatest();
            assertEquals(List.of("BOUNDARY", "START", "GOAL"),
                    entries.stream().map(PortArrivalJournal.Entry::kind).toList());
            assertTrue(entries.getFirst().payload().isNull());
            assertFalse(entries.get(1).payload().isNull());
            assertFalse(entries.get(2).payload().isNull());
            assertEquals(1, f.store.readRange(T, T + 100).size());
        }
    }

    @Test void aSessionBoundaryBetweenDepartureAndGoalCannotCreateArrival() throws Exception {
        for (String boundary : List.of(PORT, MASTER)) {
            try (var f = new Fixture()) {
                f.send(START, response(6, 2, 4, null));
                f.send(boundary, response(6, 2, 4, null));
                f.send(NEXT, response(6, 0, 9, 2));
                var entries = f.store.journal().readLatest();
                assertEquals(List.of("START", "BOUNDARY"),
                        entries.stream().map(PortArrivalJournal.Entry::kind).toList());
                assertEquals("NO_ACTIVE_SORTIE", f.runtime.statusReason());
                assertTrue(f.store.readRange(T, T + 100).isEmpty());
            }
        }
    }
}
