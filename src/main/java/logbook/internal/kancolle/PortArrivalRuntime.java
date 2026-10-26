package logbook.internal.kancolle;

import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import jakarta.json.JsonObject;
import logbook.bean.*;
import logbook.internal.logger.LoggerHolder;
import logbook.net.RequestMetaData;

/** Runs after synchronous host handlers under the existing API-state transaction. */
public final class PortArrivalRuntime {
    private static final PortArrivalRuntime SHARED = new PortArrivalRuntime(
            () -> new PortArrivalStore(Path.of("sortie-arrivals-v1")), Clock.systemUTC());
    private final PortArrivalTracker tracker = new PortArrivalTracker();
    private final Supplier<PortArrivalStore> store;
    private final Clock clock;
    PortArrivalRuntime(Supplier<PortArrivalStore> store, Clock clock) { this.store = store; this.clock = clock; }
    public static void observe(String uri, JsonObject json, RequestMetaData request, boolean handlersSucceeded) {
        SHARED.accept(uri, json, request, handlersSucceeded);
    }
    public static void invalidate() { SHARED.invalidateSession(); }
    private synchronized void invalidateSession() { tracker.clear(); }
    synchronized void accept(String uri, JsonObject json, RequestMetaData request, boolean handlersSucceeded) {
        if (uri == null || uri.equals("/kcsapi/api_port/port") || uri.startsWith("/kcsapi/api_start2/")
                || uri.endsWith("/goback_port")) { tracker.clear(); return; }
        boolean start = uri.equals("/kcsapi/api_req_map/start");
        boolean next = uri.equals("/kcsapi/api_req_map/next");
        if (!handlersSucceeded) { tracker.clear(); return; }
        if (!start && !next) return;
        try {
            if (json == null || json.getInt("api_result", 0) != 1 || json.getJsonObject("api_data") == null) {
                tracker.clear(); return;
            }
            var condition = AppCondition.get();
            if (condition.isCombinedFlag() || !condition.isMapStart()) { tracker.clear(); return; }
            int deck = condition.getDeckId();
            if (start && !Integer.toString(deck).equals(request.getParameter("api_deck_id"))) { tracker.clear(); return; }
            var fleet = DeckPortCollection.get().getDeckPortMap().get(deck);
            if (fleet == null || fleet.getShip() == null) { tracker.clear(); return; }
            List<Integer> members = new ArrayList<>(fleet.getShip());
            while (!members.isEmpty() && Objects.equals(members.getLast(), -1)) members.removeLast();
            List<Integer> masters = new ArrayList<>();
            for (Integer id : members) {
                var ship = id == null ? null : ShipCollection.get().getShipMap().get(id);
                if (ship == null || ship.getShipId() == null || ship.getShipId() <= 0) { tracker.clear(); return; }
                masters.add(ship.getShipId());
            }
            MapStartNext node = MapStartNext.toMapStartNext(json.getJsonObject("api_data"));
            if (start) tracker.start(node, deck, members, masters, clock.millis());
            else {
                boolean noRetreat = condition.getEscape() != null && condition.getEscape().isEmpty();
                var observed = tracker.next(node, deck, members, masters, noRetreat, clock.millis());
                if (observed.isPresent()) store.get().append(observed.get());
            }
        } catch (Exception error) {
            tracker.clear();
            LoggerHolder.get().warn("港到達の記録に失敗しました。戦闘ログからの推定は行いません。", error);
        }
    }
    public static List<PortArrival> read(long from, long to) throws java.io.IOException {
        return SHARED.store.get().readRange(from, to);
    }
}
