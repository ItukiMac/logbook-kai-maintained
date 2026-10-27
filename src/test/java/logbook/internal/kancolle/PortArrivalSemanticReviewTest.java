package logbook.internal.kancolle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import tools.jackson.databind.node.ObjectNode;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.math.BigDecimal;
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

/** Malformed and contradictory synthetic evidence must not be promoted into an observed goal. */
class PortArrivalSemanticReviewTest {
    @TempDir Path temp;
    private static final long T = Instant.parse("2026-10-27T01:00:00Z").toEpochMilli();
    private static final String START = "/kcsapi/api_req_map/start";
    private static final String NEXT = "/kcsapi/api_req_map/next";
    private static final String FLEET = "/kcsapi/api_get_member/ship_deck";

    private static final class MovingClock extends Clock {
        long now = T;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
    }

    private static JsonObject node(int cell, BigDecimal event, Integer from, BigDecimal success) {
        var data = Json.createObjectBuilder().add("api_maparea_id", 1).add("api_mapinfo_no", 6)
                .add("api_no", cell).add("api_event_id", event);
        if (from != null) data.add("api_from_no", from);
        return Json.createObjectBuilder().add("api_result", success).add("api_data", data).build();
    }

    private static JsonObject node(int cell, int event, Integer from) {
        return node(cell, BigDecimal.valueOf(event), from, BigDecimal.ONE);
    }

    private static JsonObject fleet() {
        return Json.createObjectBuilder().add("api_result", 1).add("api_data", Json.createObjectBuilder()
                .add("api_ship_data", Json.createArrayBuilder()
                        .add(Json.createObjectBuilder().add("api_id", 11).add("api_ship_id", 101))
                        .add(Json.createObjectBuilder().add("api_id", 12).add("api_ship_id", 102)))
                .add("api_deck_data", Json.createArrayBuilder().add(Json.createObjectBuilder().add("api_id", 1)
                        .add("api_ship", Json.createArrayBuilder().add(11).add(12).add(-1).add(-1).add(-1).add(-1))))).build();
    }

    private static RequestMetaData request(long capture, Integer from) {
        var req = mock(RequestMetaData.class);
        when(req.getHeader("x-koukainissikai-requestat")).thenReturn(Optional.of(Long.toString(capture)));
        when(req.getParameter("api_deck_id")).thenReturn("1");
        when(req.getParameter("api_maparea_id")).thenReturn("1");
        when(req.getParameter("api_mapinfo_no")).thenReturn("6");
        when(req.getParameter("api_no")).thenReturn(from == null ? null : from.toString());
        return req;
    }

    private interface Scenario { void run(PortArrivalStore store, MovingClock clock) throws Exception; }

    private void scenario(Scenario action) throws Exception {
        var condition = new AppCondition(); condition.setMapStart(true); condition.setDeckId(1);
        var decks = new DeckPortCollection(); var deck = new DeckPort(); deck.setId(1);
        deck.setShip(List.of(11, 12, -1, -1, -1, -1)); decks.getDeckPortMap().put(1, deck);
        var ships = new ShipCollection();
        for (int i = 1; i <= 2; i++) {
            var ship = new Ship(); ship.setId(10 + i); ship.setShipId(100 + i); ships.getShipMap().put(10 + i, ship);
        }
        try (var state = mockStatic(AppCondition.class); var ports = mockStatic(DeckPortCollection.class);
             var members = mockStatic(ShipCollection.class)) {
            state.when(AppCondition::get).thenReturn(condition);
            ports.when(DeckPortCollection::get).thenReturn(decks);
            members.when(ShipCollection::get).thenReturn(ships);
            action.run(new PortArrivalStore(temp.resolve("arrivals")), new MovingClock());
        }
    }

    @Test void repeatedStartIdentityInAValidChainMustBeRejectedBeforeResume() throws Exception {
        scenario((store, clock) -> {
            var runtime = new PortArrivalRuntime(() -> store, clock);
            runtime.accept(START, node(2, 4, null), request(T, null), true);
            var initial = store.journal().readLatest().getFirst();
            clock.now++;
            runtime.accept(NEXT, node(0, 9, 2), request(clock.now, 2), true);
            clock.now++;
            runtime.accept("/kcsapi/api_port/port", node(2, 4, null), request(clock.now, null), true);
            var boundary = store.journal().readLatest().getLast();
            var payload = (ObjectNode) initial.payload();
            ((ObjectNode) payload.get("route")).put("sortieId", java.util.UUID.randomUUID().toString())
                    .put("startedAt", T + 3).put("observedAt", T + 3);
            payload.put("captureAt", T + 3);
            store.journal().append(boundary, "START", T + 3, payload, "duplicate start key, distinct local UUID");
            clock.now = T + 4;
            var restarted = new PortArrivalRuntime(() -> store, clock);
            restarted.accept(FLEET, fleet(), request(clock.now, null), true);
            clock.now++;
            restarted.accept(NEXT, node(0, 9, 2), request(clock.now, 2), true);
            assertEquals(1, store.readRange(T, T + 100).size(), "The same observed departure identity was credited twice");
        });
    }
    @Test void repeatedSortieIdMustBeRejectedBeforeResume() throws Exception {
        scenario((store, clock) -> {
            var runtime = new PortArrivalRuntime(() -> store, clock);
            runtime.accept(START, node(2, 4, null), request(T, null), true);
            var initial = store.journal().readLatest().getFirst();
            clock.now++;
            runtime.accept("/kcsapi/api_port/port", node(2, 4, null), request(clock.now, null), true);
            var boundary = store.journal().readLatest().getLast();
            var payload = (ObjectNode) initial.payload();
            ((ObjectNode) payload.get("route")).put("startedAt", T + 2).put("observedAt", T + 2);
            payload.put("startKey", "b".repeat(64)).put("captureAt", T + 2);
            payload.putArray("eventKeys").add("b".repeat(64));
            store.journal().append(boundary, "START", T + 2, payload, "same local UUID for distinct departure");
            clock.now = T + 3;
            var restarted = new PortArrivalRuntime(() -> store, clock);
            restarted.accept(FLEET, fleet(), request(clock.now, null), true);
            clock.now++;
            restarted.accept(NEXT, node(0, 9, 2), request(clock.now, 2), true);
            assertTrue(store.readRange(T, T + 100).isEmpty(), "Two distinct departures reused one identity");
        });
    }
    @Test void impossibleFirstBoundaryCannotHideFutureObservationTime() throws Exception {
        scenario((store, clock) -> {
            var source = new PortArrivalStore(temp.resolve("source"));
            var runtime = new PortArrivalRuntime(() -> source, clock);
            runtime.accept(START, node(2, 4, null), request(T, null), true);
            var payload = (ObjectNode) source.journal().readLatest().getFirst().payload();
            payload.put("captureAt", 0).put("recoverable", false);
            ((ObjectNode) payload.get("route")).put("startedAt", T + 100).put("observedAt", T + 100);
            store.journal().append(null, "BOUNDARY", T, payload, "boundary precedes embedded observation");
            var restarted = new PortArrivalRuntime(() -> store, clock);
            clock.now++;
            restarted.accept(START, node(2, 4, null), request(clock.now, null), true);
            clock.now++;
            restarted.accept(NEXT, node(0, 9, 2), request(clock.now, 2), true);
            assertTrue(store.readRange(T, T + 100).isEmpty(), "Inconsistent first-entry evidence must fail closed");
        });
    }

    @Test void strippedOldGoalCannotCompleteTheNextObservedSortie() throws Exception {
        scenario((store, clock) -> {
            var runtime = new PortArrivalRuntime(() -> store, clock);
            runtime.accept(START, node(2, 4, null), request(T, null), true);
            clock.now++;
            runtime.accept(NEXT, node(0, 9, 2), request(clock.now, 2), true);
            clock.now++;
            runtime.accept("/kcsapi/api_port/port", node(2, 4, null), request(clock.now, null), true);
            clock.now++;
            runtime.accept(START, node(2, 4, null), request(clock.now, null), true);
            var oldGoal = request(T + 1, 2);
            when(oldGoal.getHeader("x-koukainissikai-requestat")).thenReturn(Optional.empty());
            clock.now++;
            runtime.accept(NEXT, node(0, 9, 2), oldGoal, true);
            assertEquals(1, store.readRange(T, T + 100).size(), "An unidentified old goal cannot prove arrival of the new sortie");
        });
    }
}
