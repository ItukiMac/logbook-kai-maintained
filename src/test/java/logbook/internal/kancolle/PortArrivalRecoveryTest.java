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

/** Synthetic traffic only. The fresh ship/deck observation is deliberately distinct from cached state. */
class PortArrivalRecoveryTest {
    @TempDir Path temp;
    private static final long T = 1_700_000_000_000L;
    private static final String START = "/kcsapi/api_req_map/start", NEXT = "/kcsapi/api_req_map/next";
    private static final class MovingClock extends Clock {
        long now = T;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
    }
    private static JsonObject node(int cell, int event, Integer from) {
        var data = Json.createObjectBuilder().add("api_maparea_id", 1).add("api_mapinfo_no", 6)
                .add("api_no", cell).add("api_event_id", event);
        if (from != null) data.add("api_from_no", from);
        return Json.createObjectBuilder().add("api_result", 1).add("api_data", data).build();
    }
    private static JsonObject fleet() {
        return Json.createObjectBuilder().add("api_result", 1).add("api_data", Json.createObjectBuilder()
                .add("api_ship_data", Json.createArrayBuilder()
                        .add(Json.createObjectBuilder().add("api_id", 11).add("api_ship_id", 101))
                        .add(Json.createObjectBuilder().add("api_id", 12).add("api_ship_id", 102)))
                .add("api_deck_data", Json.createArrayBuilder().add(Json.createObjectBuilder().add("api_id", 1)
                        .add("api_ship", Json.createArrayBuilder().add(11).add(12).add(-1).add(-1).add(-1).add(-1))))).build();
    }
    private static RequestMetaData req(long capture, Integer from) {
        var req = mock(RequestMetaData.class);
        when(req.getParameter("api_deck_id")).thenReturn("1");
        when(req.getParameter("api_maparea_id")).thenReturn("1");
        when(req.getParameter("api_mapinfo_no")).thenReturn("6");
        when(req.getParameter("api_no")).thenReturn(from == null ? null : from.toString());
        when(req.getHeader("x-koukainissikai-requestat")).thenReturn(Optional.of(Long.toString(capture)));
        return req;
    }
    private interface Scenario { void run(PortArrivalStore store, MovingClock clock, AppCondition condition) throws Exception; }
    private void scenario(Scenario action) throws Exception {
        var condition = new AppCondition(); condition.setMapStart(true); condition.setDeckId(1);
        var decks = new DeckPortCollection(); var deck = new DeckPort(); deck.setId(1);
        deck.setShip(List.of(11, 12, -1, -1, -1, -1)); decks.getDeckPortMap().put(1, deck);
        var ships = new ShipCollection();
        for (int i = 1; i <= 2; i++) { var ship = new Ship(); ship.setId(10 + i); ship.setShipId(100 + i); ships.getShipMap().put(10 + i, ship); }
        try (var a = mockStatic(AppCondition.class); var d = mockStatic(DeckPortCollection.class); var s = mockStatic(ShipCollection.class)) {
            a.when(AppCondition::get).thenReturn(condition); d.when(DeckPortCollection::get).thenReturn(decks); s.when(ShipCollection::get).thenReturn(ships);
            action.run(new PortArrivalStore(temp.resolve("arrivals")), new MovingClock(), condition);
            assertEquals(List.of(11, 12, -1, -1, -1, -1), deck.getShip());
        }
    }
    @Test void unobservedReturnAndAnotherDepartureCannotBorrowPriorAcceptance() throws Exception {
        scenario((store, clock, condition) -> {
            var first = new PortArrivalRuntime(() -> store, clock);
            first.accept(START, node(2, 4, null), req(T, null), true);
            // While the application was stopped, the player could have returned
            // to port and started a different sortie with precisely the same fleet.
            // No input available to this reader proves that did NOT happen.
            clock.now += 30_000;
            var restarted = new PortArrivalRuntime(() -> store, clock);
            restarted.accept("/kcsapi/api_get_member/ship_deck", fleet(), req(clock.now, null), true);
            clock.now++;
            restarted.accept(NEXT, node(0, 9, 2), req(clock.now, 2), true);
            assertTrue(store.readRange(T, clock.now + 1).isEmpty(),
                    "Matching fleet/cell/timestamps cannot prove the original departure continued");
            assertEquals("RESTART_CONTINUITY_UNPROVEN", restarted.statusReason());
        });
    }
    @Test void restartPreservesEvidenceButRequiresANewObservedDeparture() throws Exception {
        scenario((store, clock, condition) -> {
            var first = new PortArrivalRuntime(() -> store, clock);
            first.accept(START, node(2, 4, null), req(T, null), true);
            var original = store.journal().readLatest().getFirst();
            clock.now++;
            var restarted = new PortArrivalRuntime(() -> store, clock);
            restarted.accept("/kcsapi/api_get_member/ship_deck", fleet(), req(clock.now, null), true);
            clock.now++;
            restarted.accept(NEXT, node(0, 9, 2), req(clock.now, 2), true);
            assertTrue(store.readRange(T, T + 100).isEmpty());
            assertEquals(original, store.journal().readLatest().getFirst(), "Original evidence is retained");
            assertEquals("INVALID", store.journal().readLatest().getLast().kind());
            clock.now++;
            restarted.accept("/kcsapi/api_port/port", node(0, 9, 2), req(clock.now, null), true);
            long freshDeparture = ++clock.now;
            restarted.accept(START, node(2, 4, null), req(clock.now, null), true);
            clock.now++;
            restarted.accept(NEXT, node(0, 9, 2), req(clock.now, 2), true);
            var record = store.readRange(T, T + 100).getFirst();
            assertEquals(freshDeparture, record.startedAt(), "No old acceptance interval is borrowed");
            new PortArrivalRuntime(() -> store, clock).accept(NEXT, node(0, 9, 2), req(clock.now, 2), true);
            assertEquals(List.of(record), store.readRange(T, T + 100));
        });
    }
    @Test void completedStartGoalReplayCannotAllocateANewIdentity() throws Exception {
        scenario((store, clock, condition) -> {
            var runtime = new PortArrivalRuntime(() -> store, clock);
            var start = req(T, null); var goal = req(T + 1, 2);
            runtime.accept(START, node(2, 4, null), start, true);
            clock.now++;
            runtime.accept(NEXT, node(0, 9, 2), goal, true);
            clock.now++;
            runtime.accept(START, node(2, 4, null), start, true);
            runtime.accept(NEXT, node(0, 9, 2), goal, true);
            assertEquals(1, store.readRange(T, T + 100).size());
        });
    }
    @Test void clockReversalAfterAnIntermediateObservationInvalidates() throws Exception {
        scenario((store, clock, condition) -> {
            var runtime = new PortArrivalRuntime(() -> store, clock);
            runtime.accept(START, node(2, 4, null), req(T, null), true);
            clock.now = T + 10;
            runtime.accept(NEXT, node(4, 4, 2), req(T + 10, 2), true);
            clock.now = T + 5;
            runtime.accept(NEXT, node(0, 9, 4), req(T + 5, 4), true);
            assertTrue(store.readRange(T, T + 100).isEmpty());
        });
    }
    @Test void jstDateBoundaryDoesNotReuseYesterdayTracking() throws Exception {
        scenario((store, clock, condition) -> {
            clock.now = ZonedDateTime.of(2026, 10, 26, 23, 59, 59, 0, ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli();
            long begin = clock.now;
            var runtime = new PortArrivalRuntime(() -> store, clock);
            runtime.accept(START, node(2, 4, null), req(clock.now, null), true);
            clock.now += 2000;
            runtime.accept(NEXT, node(0, 9, 2), req(clock.now, 2), true);
            assertTrue(store.readRange(begin, clock.now + 1).isEmpty());
        });
    }
    @Test void unobservedRetreatIsNotPromotedFromTheCachedEscapeSet() throws Exception {
        scenario((store, clock, condition) -> {
            var runtime = new PortArrivalRuntime(() -> store, clock);
            runtime.accept(START, node(2, 4, null), req(T, null), true);
            condition.getEscape().add(12);
            clock.now++;
            runtime.accept(NEXT, node(0, 9, 2), req(clock.now, 2), true);
            assertTrue(store.readRange(T, T + 100).isEmpty());
        });
    }
    @Test void observedCompositionChangeThenReversionStillInvalidates() throws Exception {
        scenario((store, clock, condition) -> {
            var runtime = new PortArrivalRuntime(() -> store, clock);
            runtime.accept(START, node(2, 4, null), req(T, null), true);
            runtime.accept("/kcsapi/api_req_hensei/change", node(2, 4, null), req(T, null), true);
            clock.now++;
            runtime.accept(NEXT, node(0, 9, 2), req(clock.now, 2), true);
            assertTrue(store.readRange(T, T + 100).isEmpty());
        });
    }

    @Test void restartCannotUseOnlyPersistedHostFleet() throws Exception {
        scenario((store, clock, condition) -> {
            new PortArrivalRuntime(() -> store, clock).accept(START, node(2, 4, null), req(T, null), true);
            clock.now++;
            var restarted = new PortArrivalRuntime(() -> store, clock);
            restarted.accept(NEXT, node(0, 9, 2), req(clock.now, 2), true);
            assertTrue(store.readRange(T, T + 100).isEmpty());
            assertEquals("RECOVERY_EVIDENCE_INCOMPLETE", restarted.statusReason());
        });
    }
    @Test void freshFleetCannotBridgeMissingOrWrongPredecessor() throws Exception {
        for (Integer from : Arrays.asList(null, 7)) {
            Path root = temp.resolve("from-" + from);
            scenario((unused, clock, condition) -> {
                var store = new PortArrivalStore(root);
                new PortArrivalRuntime(() -> store, clock).accept(START, node(2, 4, null), req(T, null), true);
                clock.now++;
                var restarted = new PortArrivalRuntime(() -> store, clock);
                restarted.accept("/kcsapi/api_get_member/ship_deck", fleet(), req(clock.now, null), true);
                clock.now++;
                restarted.accept(NEXT, node(0, 9, from), req(clock.now, from), true);
                assertTrue(store.readRange(T, T + 100).isEmpty());
            });
        }
    }
    @Test void completedStartGoalReplayAfterRestartCannotMintAnotherIdentity() throws Exception {
        scenario((store, clock, condition) -> {
            var first = new PortArrivalRuntime(() -> store, clock);
            first.accept(START, node(2, 4, null), req(T, null), true);
            clock.now++;
            first.accept(NEXT, node(0, 9, 2), req(clock.now, 2), true);
            clock.now++;
            var restarted = new PortArrivalRuntime(() -> store, clock);
            restarted.accept(START, node(2, 4, null), req(T, null), true);
            restarted.accept(NEXT, node(0, 9, 2), req(T + 1, 2), true);
            assertEquals(1, store.readRange(T, T + 100).size());
        });
    }
    @Test void repeatedStartDuringTrackingPreservesOriginalIdentityAndTime() throws Exception {
        scenario((store, clock, condition) -> {
            var runtime = new PortArrivalRuntime(() -> store, clock);
            runtime.accept(START, node(2, 4, null), req(T, null), true);
            clock.now++;
            runtime.accept(START, node(2, 4, null), req(T, null), true);
            clock.now++;
            runtime.accept(NEXT, node(0, 9, 2), req(clock.now, 2), true);
            assertEquals(T, store.readRange(T, T + 100).getFirst().startedAt());
        });
    }
    @Test void differentDepartureWithoutObservedPortCannotReplaceAnActiveSortie() throws Exception {
        scenario((store, clock, condition) -> {
            var runtime = new PortArrivalRuntime(() -> store, clock);
            runtime.accept(START, node(2, 4, null), req(T, null), true);
            clock.now++;
            runtime.accept(START, node(3, 4, null), req(clock.now, null), true);
            clock.now++;
            runtime.accept(NEXT, node(0, 9, 3), req(clock.now, 3), true);
            assertTrue(store.readRange(T, T + 100).isEmpty());
        });
    }
    @Test void observedPortAndLoginPersistInvalidationAcrossRestart() throws Exception {
        for (String boundary : List.of("/kcsapi/api_port/port", "/kcsapi/api_start2/getData")) {
            scenario((unused, clock, condition) -> {
                var store = new PortArrivalStore(temp.resolve(boundary.endsWith("port") ? "port" : "login"));
                var first = new PortArrivalRuntime(() -> store, clock);
                first.accept(START, node(2, 4, null), req(T, null), true);
                clock.now++;
                first.accept(boundary, node(2, 4, null), req(clock.now, null), true);
                var restarted = new PortArrivalRuntime(() -> store, clock);
                clock.now++;
                restarted.accept("/kcsapi/api_get_member/ship_deck", fleet(), req(clock.now, null), true);
                clock.now++;
                restarted.accept(NEXT, node(0, 9, 2), req(clock.now, 2), true);
                assertTrue(store.readRange(T, T + 100).isEmpty());
            });
        }
    }
    @Test void newObservedDepartureAfterPortIsIndependentAndKeepsBothRecords() throws Exception {
        scenario((store, clock, condition) -> {
            var runtime = new PortArrivalRuntime(() -> store, clock);
            runtime.accept(START, node(2, 4, null), req(T, null), true);
            clock.now++;
            runtime.accept(NEXT, node(0, 9, 2), req(clock.now, 2), true);
            clock.now++;
            runtime.accept("/kcsapi/api_port/port", node(2, 4, null), req(clock.now, null), true);
            clock.now++;
            runtime.accept(START, node(2, 4, null), req(clock.now, null), true);
            clock.now++;
            runtime.accept(NEXT, node(0, 9, 2), req(clock.now, 2), true);
            var rows = store.readRange(T, T + 100);
            assertEquals(2, rows.size()); assertNotEquals(rows.get(0).sortieId(), rows.get(1).sortieId());
        });
    }
    @Test void duplicateIntermediateAfterRestartCannotProveContinuity() throws Exception {
        scenario((store, clock, condition) -> {
            var runtime = new PortArrivalRuntime(() -> store, clock);
            runtime.accept(START, node(2, 4, null), req(T, null), true);
            clock.now++;
            runtime.accept(NEXT, node(4, 4, 2), req(clock.now, 2), true);
            var restarted = new PortArrivalRuntime(() -> store, clock);
            restarted.accept(NEXT, node(4, 4, 2), req(clock.now, 2), true);
            clock.now++;
            restarted.accept("/kcsapi/api_get_member/ship_deck", fleet(), req(clock.now, null), true);
            clock.now++;
            restarted.accept(NEXT, node(0, 9, 4), req(clock.now, 4), true);
            assertTrue(store.readRange(T, T + 100).isEmpty());
            assertEquals("RESTART_CONTINUITY_UNPROVEN", restarted.statusReason());
        });
    }
    @Test void fiveAmResetInvalidatesEvenWithinTheSameCivilDate() throws Exception {
        scenario((store, clock, condition) -> {
            clock.now = ZonedDateTime.of(2026, 10, 27, 4, 59, 59, 0, ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli();
            long begin = clock.now;
            var runtime = new PortArrivalRuntime(() -> store, clock);
            runtime.accept(START, node(2, 4, null), req(clock.now, null), true);
            clock.now += 2000;
            runtime.accept(NEXT, node(0, 9, 2), req(clock.now, 2), true);
            assertTrue(store.readRange(begin, clock.now + 1).isEmpty());
        });
    }
    @Test void interruptedPublicationConsumesIdentityWithoutInventingCreditOnRestart() throws Exception {
        scenario((store, clock, condition) -> {
            var failing = spy(store);
            doThrow(new java.io.IOException("synthetic publication interruption")).when(failing).append(any());
            var runtime = new PortArrivalRuntime(() -> failing, clock);
            runtime.accept(START, node(2, 4, null), req(T, null), true);
            clock.now++;
            runtime.accept(NEXT, node(0, 9, 2), req(clock.now, 2), true);
            assertTrue(store.readRange(T, T + 100).isEmpty());
            var restarted = new PortArrivalRuntime(() -> store, clock);
            clock.now++;
            restarted.accept(START, node(2, 4, null), req(T, null), true);
            restarted.accept(NEXT, node(0, 9, 2), req(T + 1, 2), true);
            assertTrue(store.readRange(T, T + 100).isEmpty());
        });
    }
    @Test void corruptedTrackingIsRetainedAndNeverBecomesFreshStart() throws Exception {
        scenario((store, clock, condition) -> {
            var runtime = new PortArrivalRuntime(() -> store, clock);
            runtime.accept(START, node(2, 4, null), req(T, null), true);
            var corrupt = temp.resolve("arrivals/tracking-v1/2023-11-15/00000002.json");
            java.nio.file.Files.writeString(corrupt, "{partial");
            var restarted = new PortArrivalRuntime(() -> store, clock);
            clock.now++;
            restarted.accept("/kcsapi/api_get_member/ship_deck", fleet(), req(clock.now, null), true);
            clock.now++;
            restarted.accept(NEXT, node(0, 9, 2), req(clock.now, 2), true);
            restarted.accept(START, node(2, 4, null), req(clock.now, null), true);
            assertTrue(store.readRange(T, T + 100).isEmpty());
            assertEquals("{partial", java.nio.file.Files.readString(corrupt));
            assertEquals("CORRUPT_OR_CONTRADICTORY_TRACKING", restarted.statusReason());
        });
    }
    @Test void strippedCaptureMetadataCannotTurnAnOldDepartureIntoANewOne() throws Exception {
        scenario((store, clock, condition) -> {
            var runtime = new PortArrivalRuntime(() -> store, clock);
            runtime.accept(START, node(2, 4, null), req(T, null), true);
            clock.now++;
            runtime.accept(NEXT, node(0, 9, 2), req(clock.now, 2), true);
            clock.now++;
            runtime.accept("/kcsapi/api_port/port", node(2, 4, null), req(clock.now, null), true);
            var stripped = req(T, null);
            when(stripped.getHeader("x-koukainissikai-requestat")).thenReturn(Optional.empty());
            runtime.accept(START, node(2, 4, null), stripped, true);
            runtime.accept(NEXT, node(0, 9, 2), req(T + 1, 2), true);
            assertEquals(1, store.readRange(T, T + 100).size());
        });
    }
}
