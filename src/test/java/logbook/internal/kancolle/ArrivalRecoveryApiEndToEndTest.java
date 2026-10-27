package logbook.internal.kancolle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import logbook.api.APIListenerSpi;
import logbook.api.ApiGetMemberShipDeck;
import logbook.api.ApiReqCombinedBattleGobackPort;
import logbook.api.ApiReqMapNext;
import logbook.api.ApiReqMapStart;
import logbook.api.ApiReqSortieBattleresult;
import logbook.bean.AppBouyomiConfig;
import logbook.bean.AppCondition;
import logbook.bean.AppConfig;
import logbook.bean.AppQuestCollection;
import logbook.bean.BattleLog;
import logbook.bean.BattleTypes.IFormation;
import logbook.bean.DeckPort;
import logbook.bean.DeckPortCollection;
import logbook.bean.MapStartNext;
import logbook.bean.Ship;
import logbook.bean.ShipCollection;
import logbook.bean.SlotItemCollection;
import logbook.internal.Config;
import logbook.internal.listener.APIListener;
import logbook.internal.log.LogWriter;
import logbook.net.RequestMetaData;
import logbook.net.ResponseMetaData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

/**
 * Synthetic JSON through the real decoder, map/ship_deck/result/retreat handlers and durable observer.
 * Host services and unrelated battle/report/config persistence are fixtures. This is not live game traffic.
 */
class ArrivalRecoveryApiEndToEndTest {
    @TempDir Path temp;
    private static final long T = 1_700_000_000_000L;
    private static final String START = "/kcsapi/api_req_map/start";
    private static final String NEXT = "/kcsapi/api_req_map/next";
    private static final String FLEET = "/kcsapi/api_get_member/ship_deck";
    private static final String RESULT = "/kcsapi/api_req_sortie/battleresult";
    private static final String RETREAT = "/kcsapi/api_req_sortie/goback_port";
    private static final List<Integer> INSTANCES = List.of(11, 12, 13, 14);
    private static final List<Integer> MASTERS = List.of(101, 102, 103, 104);

    private static final class MovingClock extends Clock {
        long now = T;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
    }

    private static JsonObject node(int cell, int event, Integer from) {
        var data = Json.createObjectBuilder().add("api_maparea_id", 1).add("api_mapinfo_no", 6)
                .add("api_no", cell).add("api_event_id", event);
        if (from != null) data.add("api_from_no", from);
        return Json.createObjectBuilder().add("api_result", 1).add("api_data", data).build();
    }

    private static JsonArrayBuilder integers(List<Integer> values) {
        var array = Json.createArrayBuilder();
        values.forEach(array::add);
        return array;
    }

    private static JsonObject fleet(int deck, List<Integer> instances, List<Integer> masters) {
        var ships = Json.createArrayBuilder();
        for (int i = 0; i < masters.size(); i++) {
            ships.add(Json.createObjectBuilder().add("api_id", instances.get(i)).add("api_ship_id", masters.get(i))
                    .add("api_nowhp", 10).add("api_maxhp", 10));
        }
        var members = new ArrayList<>(instances);
        while (members.size() < 6) members.add(-1);
        return Json.createObjectBuilder().add("api_result", 1).add("api_data", Json.createObjectBuilder()
                .add("api_ship_data", ships)
                .add("api_deck_data", Json.createArrayBuilder().add(Json.createObjectBuilder().add("api_id", deck)
                        .add("api_ship", integers(members))))).build();
    }

    private static JsonObject offer(List<Integer> escaped, List<Integer> escorts) {
        var escape = Json.createObjectBuilder().add("api_escape_idx", integers(escaped));
        if (escorts != null) escape.add("api_tow_idx", integers(escorts));
        return Json.createObjectBuilder().add("api_result", 1).add("api_data", Json.createObjectBuilder()
                .add("api_win_rank", "A").add("api_escape_flag", 1).add("api_escape", escape)).build();
    }

    private final class Fixture implements AutoCloseable {
        final int deckId;
        final MovingClock clock = new MovingClock();
        final AppCondition state = new AppCondition();
        final DeckPortCollection decks = new DeckPortCollection();
        final ShipCollection ships = new ShipCollection();
        final PortArrivalStore store;
        final APIListener api;
        final List<MockedStatic<?>> services = new ArrayList<>();
        PortArrivalRuntime observer;

        Fixture(int deck, boolean combined) throws Exception {
            deckId = deck;
            state.setCombinedFlag(combined);
            var port = new DeckPort(); port.setId(deck);
            port.setShip(List.of(11, 12, 13, 14, -1, -1));
            decks.getDeckPortMap().put(deck, port);
            for (int i = 0; i < INSTANCES.size(); i++) {
                var ship = new Ship(); ship.setId(INSTANCES.get(i)); ship.setShipId(MASTERS.get(i));
                ship.setNowhp(10); ship.setMaxhp(10);
                ships.getShipMap().put(ship.getId(), ship);
            }
            statics(AppCondition.class).when(AppCondition::get).thenReturn(state);
            statics(DeckPortCollection.class).when(DeckPortCollection::get).thenReturn(decks);
            statics(ShipCollection.class).when(ShipCollection::get).thenReturn(ships);
            statics(SlotItemCollection.class).when(SlotItemCollection::get).thenReturn(new SlotItemCollection());
            statics(AppQuestCollection.class).when(AppQuestCollection::get).thenReturn(new AppQuestCollection());
            statics(AppConfig.class).when(AppConfig::get).thenReturn(mock(AppConfig.class));
            statics(AppBouyomiConfig.class).when(AppBouyomiConfig::get).thenReturn(mock(AppBouyomiConfig.class));
            statics(Config.class).when(Config::getDefault).thenReturn(mock(Config.class));
            statics(BattleLogs.class); // Preserve real BattleLog.snapshot; suppress only the unrelated log writer.
            statics(LogWriter.class).when(() -> LogWriter.getInstance(any())).thenReturn(mock(LogWriter.class));
            store = new PortArrivalStore(temp.resolve("deck-" + deck));
            restart();
            var entry = statics(PortArrivalRuntime.class);
            entry.when(() -> PortArrivalRuntime.observe(anyString(), any(), any(), anyBoolean())).thenAnswer(call -> {
                observer.accept(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3));
                return null;
            });
            Method invalidation = PortArrivalRuntime.class.getDeclaredMethod("invalidateSession");
            invalidation.setAccessible(true);
            entry.when(PortArrivalRuntime::invalidate).thenAnswer(call -> {
                invalidation.invoke(observer);
                return null;
            });
            var constructor = APIListener.class.getDeclaredConstructor(Stream.class);
            constructor.setAccessible(true);
            api = constructor.newInstance(Stream.<APIListenerSpi>of(new ApiReqMapStart(), new ApiReqMapNext(),
                    new ApiGetMemberShipDeck(), new ApiReqSortieBattleresult(), new ApiReqCombinedBattleGobackPort()));
        }

        private <T> MockedStatic<T> statics(Class<T> type) {
            var mock = mockStatic(type); services.add(mock); return mock;
        }

        void restart() { observer = new PortArrivalRuntime(() -> store, clock); }
        long send(String uri, JsonObject json) { return sendCaptured(uri, json, ++clock.now); }
        long sendCaptured(String uri, JsonObject json, Long capture) {
            decode(uri, "svdata=" + json, capture);
            return capture == null ? 0 : capture;
        }
        void decode(String uri, String body, Long capture) {
            var request = mock(RequestMetaData.class);
            when(request.getRequestURI()).thenReturn(uri);
            when(request.getParameter("api_deck_id")).thenReturn(Integer.toString(deckId));
            when(request.getHeader("x-koukainissikai-requestat"))
                    .thenReturn(capture == null ? Optional.empty() : Optional.of(capture.toString()));
            var response = mock(ResponseMetaData.class);
            when(response.getResponseBody()).thenReturn(Optional.of(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))));
            assertTrue(api.test(request));
            api.accept(request, response);
        }
        long start() { return send(START, node(2, 4, null)); }
        void freshFleet() { send(FLEET, fleet(deckId, INSTANCES, MASTERS)); }
        void goal() { send(NEXT, node(0, 9, 2)); }
        List<PortArrival> arrivals() throws Exception { return store.readRange(T, clock.now + 1000); }
        void assertNoArrival() throws Exception { assertTrue(arrivals().isEmpty(), observer.statusReason()); }

        void battleOffer(List<Integer> escaped, List<Integer> escorts) {
            // Battle phase context is synthetic; decoding, result transfer, snapshot and retreat handlers are real.
            var battle = mock(IFormation.class);
            when(battle.getDockId()).thenReturn(deckId);
            when(battle.copy()).thenReturn(mock(IFormation.class));
            state.getBattleResult().setBattle(battle);
            send(RESULT, offer(escaped, escorts));
        }
        void retreat() { send(RETREAT, Json.createObjectBuilder().add("api_result", 1).build()); }
        void confirmedRetreat() {
            start();
            battleOffer(List.of(2), List.of(3));
            assertEquals("RETREAT_OFFER_OBSERVED", observer.statusReason());
            assertNotNull(state.getBattleResultConfirm().getDeckMap());
            retreat();
            assertEquals(Set.of(12, 13), state.getEscape());
            assertEquals("RETREAT_CONFIRMED", observer.statusReason());
        }
        @Override public void close() {
            for (int i = services.size() - 1; i >= 0; i--) services.get(i).close();
        }
    }

    @Test void decodedFleetAndConnectedMapCannotProveContinuityAcrossRestarts() throws Exception {
        try (var f = new Fixture(1, false)) {
            long departure = f.start();
            f.restart(); f.freshFleet();
            long goal = f.send(NEXT, node(0, 9, 2));
            assertEquals("RESTART_CONTINUITY_UNPROVEN", f.observer.statusReason());
            f.assertNoArrival();
            f.restart();
            f.sendCaptured(START, node(2, 4, null), departure);
            f.sendCaptured(NEXT, node(0, 9, 2), goal);
            f.restart(); f.goal();
            f.assertNoArrival();
        }
    }
    @Test void cachedFleetAloneCannotRecoverAfterRestart() throws Exception {
        try (var f = new Fixture(1, false)) {
            f.start(); f.restart(); f.goal();
            assertEquals("RECOVERY_EVIDENCE_INCOMPLETE", f.observer.statusReason());
            f.assertNoArrival();
        }
    }

    @Test void freshFleetCannotRecoverWithoutObservedPredecessor() throws Exception {
        try (var f = new Fixture(1, false)) {
            f.start(); f.restart(); f.freshFleet(); f.send(NEXT, node(0, 9, null));
            assertEquals("RECOVERY_EVIDENCE_INCOMPLETE", f.observer.statusReason());
            f.assertNoArrival();
        }
    }

    @Test void missingRoutingPacketCannotBeBridgedAfterRestart() throws Exception {
        try (var f = new Fixture(1, false)) {
            f.start(); f.restart(); f.freshFleet(); f.send(NEXT, node(0, 9, 4));
            assertEquals("RECOVERY_EVIDENCE_INCOMPLETE", f.observer.statusReason());
            f.assertNoArrival();
        }
    }

    @Test void departureWithoutCaptureTimestampRemainsNonRecoverable() throws Exception {
        try (var f = new Fixture(1, false)) {
            f.sendCaptured(START, node(2, 4, null), null);
            f.restart(); f.freshFleet(); f.goal();
            assertEquals("RECOVERY_EVIDENCE_INCOMPLETE", f.observer.statusReason());
            f.assertNoArrival();
        }
    }

    @Test void staleFleetCaptureCannotEstablishFreshRecoveryEvidence() throws Exception {
        try (var f = new Fixture(1, false)) {
            long departure = f.start(); f.restart();
            f.sendCaptured(FLEET, fleet(1, INSTANCES, MASTERS), departure);
            assertEquals("FRESH_FLEET_HAS_NO_NEW_CAPTURE_ID", f.observer.statusReason());
            f.goal(); f.assertNoArrival();
        }
    }

    @Test void freshPacketMustContainEveryInstanceAndMasterRatherThanRelyOnCachedShips() throws Exception {
        try (var f = new Fixture(1, false)) {
            f.start(); f.restart();
            f.send(FLEET, fleet(1, INSTANCES, MASTERS.subList(0, 3)));
            assertTrue(f.ships.getShipMap().containsKey(14), "The cached fourth ship still exists");
            assertEquals("FRESH_FLEET_MISMATCH", f.observer.statusReason());
            f.goal(); f.assertNoArrival();
        }
    }

    @Test void decodedMasterChangeInvalidatesRecovery() throws Exception {
        try (var f = new Fixture(1, false)) {
            f.start(); f.restart();
            f.send(FLEET, fleet(1, INSTANCES, List.of(101, 102, 103, 999)));
            assertEquals(999, f.ships.getShipMap().get(14).getShipId());
            assertEquals("FRESH_FLEET_MISMATCH", f.observer.statusReason());
            f.goal(); f.assertNoArrival();
        }
    }

    @Test void malformedJsonInvalidatesTrackingDurablyThroughActualDecoder() throws Exception {
        try (var f = new Fixture(1, false)) {
            f.start();
            f.decode(NEXT, "svdata={broken", ++f.clock.now);
            assertEquals("JSON_DECODE_FAILED", f.observer.statusReason());
            f.restart(); f.freshFleet(); f.goal(); f.assertNoArrival();
        }
    }

    @Test void realMapHandlerFailureInvalidatesBeforeObserverCanCount() throws Exception {
        try (var f = new Fixture(1, false)) {
            f.start();
            // The real map handler requires api_maparea_id while building the route.
            f.send(NEXT, Json.createObjectBuilder().add("api_result", 1).add("api_data", Json.createObjectBuilder()
                    .add("api_mapinfo_no", 6).add("api_no", 0).add("api_event_id", 9).add("api_from_no", 2)).build());
            assertEquals("API_HANDLER_FAILED", f.observer.statusReason());
            f.restart(); f.freshFleet(); f.goal(); f.assertNoArrival();
        }
    }

    private void combinedFleetCannotRecover(int deck) throws Exception {
        try (var f = new Fixture(deck, true)) {
            f.start(); f.restart(); f.freshFleet(); f.goal(); f.assertNoArrival();
        }
    }
    @Test void combinedMainFleetDoesNotRecoverAsIndependentFleet() throws Exception { combinedFleetCannotRecover(1); }
    @Test void combinedEscortFleetDoesNotRecoverAsIndependentFleet() throws Exception { combinedFleetCannotRecover(2); }

    private void independentFleetStillRequiresContinuity(int deck) throws Exception {
        try (var f = new Fixture(deck, true)) {
            f.start(); f.restart(); f.freshFleet(); f.goal();
            f.assertNoArrival();
            assertEquals("RESTART_CONTINUITY_UNPROVEN", f.observer.statusReason());
        }
    }
    @Test void independentThirdFleetRequiresContinuityWhileFirstTwoStayCombined() throws Exception { independentFleetStillRequiresContinuity(3); }
    @Test void independentFourthFleetRequiresContinuityWhileFirstTwoStayCombined() throws Exception { independentFleetStillRequiresContinuity(4); }

    private void retreatRetainsSlots(boolean restart) throws Exception {
        try (var f = new Fixture(1, false)) {
            f.confirmedRetreat();
            if (restart) { f.restart(); f.freshFleet(); }
            f.goal();
            if (restart) {
                f.assertNoArrival();
                assertEquals("RESTART_CONTINUITY_UNPROVEN", f.observer.statusReason());
                assertTrue(f.store.journal().readLatest().stream().anyMatch(e -> e.kind().equals("RETREAT")),
                        "Confirmed retreat evidence remains available for review");
                return;
            }
            assertEquals(1, f.arrivals().size());
            var arrival = f.arrivals().getFirst();
            assertFalse(arrival.noRetreat());
            assertEquals(MASTERS, arrival.shipMasterIds(), "Legacy departure evidence is never rewritten or compacted");
            var evidence = f.store.readFleet(arrival).orElseThrow();
            assertEquals(List.of(new PortArrivalFleet.Member(1, 11, 101, false),
                    new PortArrivalFleet.Member(2, 12, 102, true), new PortArrivalFleet.Member(3, 13, 103, true),
                    new PortArrivalFleet.Member(4, 14, 104, false)), evidence.members());
        }
    }
    @Test void realBattleResultAndRetreatHandlersPreserveMiddleSlotsAndNoRetreatFalse() throws Exception { retreatRetainsSlots(false); }
    @Test void confirmedRetreatEvidenceIsRetainedButDoesNotProveRestartContinuity() throws Exception { retreatRetainsSlots(true); }

    @Test void ambiguousRetreatIndicesAreNotReducedToTheirFirstElement() throws Exception {
        try (var f = new Fixture(1, false)) {
            f.start(); f.battleOffer(List.of(2, 3), null);
            f.retreat();
            assertEquals(Set.of(12), f.state.getEscape(), "The legacy handler alone would accept the first index");
            f.goal(); f.assertNoArrival();
        }
    }

    @Test void outOfRangeRetreatIndexDoesNotEstablishAnArrival() throws Exception {
        try (var f = new Fixture(1, false)) {
            f.start(); f.battleOffer(List.of(7), null);
            assertEquals("RETREAT_BATTLE_MISMATCH", f.observer.statusReason());
            f.goal(); f.assertNoArrival();
        }
    }

    @Test void oldBattleAtAnotherCellCannotSupplyRetreatEvidence() throws Exception {
        try (var f = new Fixture(1, false)) {
            f.start();
            f.state.getBattleResult().getNext().set(0, MapStartNext.toMapStartNext(node(1, 4, null).getJsonObject("api_data")));
            f.battleOffer(List.of(2), List.of(3));
            assertEquals("RETREAT_BATTLE_MISMATCH", f.observer.statusReason());
            f.retreat(); f.goal(); f.assertNoArrival();
        }
    }

    @Test void replacingConfirmedBattleWithCachedObjectCannotConfirmRetreat() throws Exception {
        try (var f = new Fixture(1, false)) {
            f.start(); f.battleOffer(List.of(2), List.of(3));
            BattleLog observed = f.state.getBattleResultConfirm();
            var cached = new BattleLog(); cached.setResult(observed.getResult());
            cached.setNext(observed.getNext()); cached.setDeckMap(observed.getDeckMap());
            f.state.setBattleResultConfirm(cached);
            f.retreat();
            assertEquals("RETREAT_NOT_CONFIRMED", f.observer.statusReason());
            f.goal(); f.assertNoArrival();
        }
    }

    @Test void reorderedRemainingFleetCannotReuseConfirmedRetreatSlots() throws Exception {
        try (var f = new Fixture(1, false)) {
            f.confirmedRetreat();
            f.send(FLEET, fleet(1, List.of(11, 14, 12, 13), List.of(101, 104, 102, 103)));
            assertEquals("FRESH_FLEET_MISMATCH", f.observer.statusReason());
            f.goal(); f.assertNoArrival();
        }
    }

    @Test void combinedRetreatEndpointRemainsUnsupportedEvenWithAValidNormalOffer() throws Exception {
        try (var f = new Fixture(1, false)) {
            f.start(); f.battleOffer(List.of(2), List.of(3));
            f.send("/kcsapi/api_req_combined_battle/goback_port", Json.createObjectBuilder().add("api_result", 1).build());
            assertEquals("UNSUPPORTED_RETREAT_PATH", f.observer.statusReason());
            f.goal(); f.assertNoArrival();
        }
    }
}
