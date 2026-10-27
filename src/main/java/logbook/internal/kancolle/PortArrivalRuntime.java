package logbook.internal.kancolle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import jakarta.json.*;
import logbook.bean.*;
import logbook.internal.logger.LoggerHolder;
import logbook.net.RequestMetaData;

/** Runs after synchronous host handlers under the existing API-state transaction. */
public final class PortArrivalRuntime {
    private static final PortArrivalRuntime SHARED = new PortArrivalRuntime(
            () -> new PortArrivalStore(Path.of("sortie-arrivals-v1")), Clock.systemUTC());
    private static final ObjectMapper JSON = tools.jackson.databind.json.JsonMapper.builderWithJackson2Defaults()
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private static final String START = "/kcsapi/api_req_map/start", NEXT = "/kcsapi/api_req_map/next";
    private static final String FLEET = "/kcsapi/api_get_member/ship_deck";
    private static final String RESULT = "/kcsapi/api_req_sortie/battleresult";
    private static final String RETREAT = "/kcsapi/api_req_sortie/goback_port";
    private static final Set<String> ACTIVE = Set.of("START", "STEP", "OFFER", "RETREAT");
    private static final Set<String> KINDS = Set.of("START", "STEP", "OFFER", "RETREAT", "GOAL", "INVALID", "BOUNDARY");
    private final PortArrivalTracker tracker = new PortArrivalTracker();
    private final Supplier<PortArrivalStore> store;
    private final Clock clock;
    private PortArrivalStore storage;
    private PortArrivalJournal journal;
    private PortArrivalJournal.Entry last;
    private Observation observation;
    private boolean initialized, blocked, recovering, freshFleet, mayStart;
    private long lastClock, freshCapture;
    private String reason = "NO_OBSERVED_START";
    private BattleLog liveOffer;
    private final Set<String> startKeys = new HashSet<>();

    /** Contains only bounded routing/identity evidence, never raw traffic or account credentials. */
    record Observation(PortArrivalTracker.Snapshot route, String startKey, List<String> eventKeys,
            long captureAt, boolean recoverable, List<Integer> offeredSlots) {
        Observation {
            if (route == null || startKey == null || !startKey.matches("[0-9a-f]{64}") || eventKeys == null
                    || eventKeys.isEmpty() || eventKeys.size() > 128 || !eventKeys.getFirst().equals(startKey)
                    || eventKeys.stream().anyMatch(k -> k == null || !k.matches("[0-9a-f]{64}"))
                    || eventKeys.stream().distinct().count() != eventKeys.size() || captureAt < 0
                    || (recoverable && captureAt == 0) || offeredSlots == null
                    || offeredSlots.size() > 2 || offeredSlots.stream().distinct().count() != offeredSlots.size()
                    || offeredSlots.stream().anyMatch(s -> s == null || s < 1 || s > route.instances().size()
                            || route.escapedSlots().contains(s))) throw new IllegalArgumentException("Invalid arrival observation");
            eventKeys = List.copyOf(eventKeys); offeredSlots = List.copyOf(offeredSlots);
        }
    }

    PortArrivalRuntime(Supplier<PortArrivalStore> store, Clock clock) { this.store = store; this.clock = clock; }
    public static void observe(String uri, JsonObject json, RequestMetaData request, boolean handlersSucceeded) {
        SHARED.accept(uri, json, request, handlersSucceeded);
    }
    public static void invalidate() { SHARED.invalidateSession(); }
    private synchronized void invalidateSession() {
        try { initialize(); invalidate("JSON_DECODE_FAILED", clock.millis()); }
        catch (Exception error) { block("TRACKING_IO_FAILURE", error); }
    }
    String statusReason() { return reason; }

    synchronized void accept(String uri, JsonObject json, RequestMetaData request, boolean handlersSucceeded) {
        try {
            initialize();
            if (blocked) return;
            long now = clock.millis();
            if (now < lastClock) { invalidate("CLOCK_REVERSED", lastClock); return; }
            lastClock = now;
            if (uri == null || !handlersSucceeded) { invalidate("API_HANDLER_FAILED", now); return; }
            if (uri.equals("/kcsapi/api_port/port") || uri.startsWith("/kcsapi/api_start2/")) {
                if (!successful(json)) { invalidate("SESSION_BOUNDARY_FAILED", now); return; }
                checkpoint("BOUNDARY", observation, "SESSION_BOUNDARY", now);
                tracker.clear(); recovering = freshFleet = false; liveOffer = null; mayStart = true;
                return;
            }
            if (active() && (!PortArrivalTracker.sameDay(observation.route().startedAt(), now)
                    || now - observation.route().startedAt() > 86_400_000L)) {
                invalidate("STALE_OR_DATE_BOUNDARY", now); return;
            }
            if (uri.startsWith("/kcsapi/api_req_hensei/") || uri.startsWith("/kcsapi/api_req_kaisou/")
                    || uri.startsWith("/kcsapi/api_req_kousyou/")) {
                if (active()) invalidate("COMPOSITION_CHANGED", now);
                return;
            }
            if (uri.endsWith("/goback_port") && !uri.equals(RETREAT)) {
                if (active()) invalidate("UNSUPPORTED_RETREAT_PATH", now);
                return;
            }
            boolean start = uri.equals(START), next = uri.equals(NEXT);
            if (!start && !next && !uri.equals(FLEET) && !uri.equals(RESULT) && !uri.equals(RETREAT)) return;
            if (!successful(json)) { invalidate("FAILED_GAME_RESPONSE", now); return; }
            if (uri.equals(FLEET)) { observeFleet(json, request, now); return; }
            if (!start && !active()) { reason = "NO_ACTIVE_SORTIE"; return; }
            long capture = captureAt(request, now);
            if (!start && active() && capture == 0 && observation.captureAt() > 0) {
                invalidate("CAPTURE_ID_DISAPPEARED", now); return;
            }
            String key = eventKey(uri, json, request, capture);
            if (start && (startKeys.contains(key) || (observation != null && observation.startKey().equals(key)))) {
                reason = "DUPLICATE_OR_UNIDENTIFIABLE_START"; return;
            }
            if (active() && observation.eventKeys().contains(key)) { reason = "DUPLICATE_EVENT"; return; }
            if (capture > 0 && observation != null && capture < observation.captureAt()) {
                invalidate("CAPTURE_ORDER_REVERSED", now); return;
            }
            if (start) { start(json, request, key, capture, now); return; }
            if (uri.equals(RESULT)) { observeOffer(json, key, capture, now); return; }
            if (uri.equals(RETREAT)) { retreat(key, capture, now); return; }
            next(json, request, key, capture, now);
        } catch (Exception error) {
            try { if (initialized && !blocked) invalidate("INVALID_OBSERVATION", Math.max(lastClock, clock.millis())); }
            catch (Exception ignored) { /* Preserve both original failure and the incomplete journal. */ }
            block("TRACKING_IO_OR_FORMAT_FAILURE", error);
        }
    }

    private void initialize() throws IOException {
        if (initialized) return;
        storage = store.get(); journal = storage.journal();
        try {
            List<PortArrivalJournal.Entry> entries = journal.readLatest();
            PortArrivalJournal.Entry prior = null; Observation previous = null;
            Set<String> sortieIds = new HashSet<>();
            for (var entry : entries) {
                Observation value = entry.payload().isNull() ? null : JSON.treeToValue(entry.payload(), Observation.class);
                validateTransition(prior, previous, entry, value);
                if (entry.kind().equals("START") && (!startKeys.add(value.startKey()) || !sortieIds.add(value.route().sortieId())))
                    throw new IOException("Repeated departure identity in tracking journal");
                prior = entry; previous = value;
            }
            last = prior; observation = previous; initialized = true;
            lastClock = last == null ? 0 : last.observedAt();
            recovering = active(); mayStart = last == null || last.kind().equals("BOUNDARY");
            reason = recovering ? "AWAITING_FRESH_FLEET" : last == null ? "NO_OBSERVED_START" : last.reason();
        } catch (Exception error) {
            initialized = true; block("CORRUPT_OR_CONTRADICTORY_TRACKING", error);
        }
    }

    private static void validateTransition(PortArrivalJournal.Entry prior, Observation before,
            PortArrivalJournal.Entry entry, Observation value) throws IOException {
        String kind = entry.kind();
        if (!KINDS.contains(kind)) throw new IOException("Unknown tracking event kind");
        if (value != null && (value.route().observedAt() > entry.observedAt()
                || value.captureAt() > value.route().observedAt())) throw new IOException("Future observation in checkpoint");
        if (kind.equals("INVALID") || kind.equals("BOUNDARY")) {
            if (prior != null && !Objects.equals(before, value)) throw new IOException("Boundary replaced route evidence");
            return;
        }
        if (value == null || value.route().observedAt() != entry.observedAt()) throw new IOException("Missing or mistimed route evidence");
        if (kind.equals("START")) {
            if (prior != null && !prior.kind().equals("BOUNDARY")) throw new IOException("Departure lacks a session boundary");
            if (before != null && (value.captureAt() == 0 || value.captureAt() <= before.captureAt()))
                throw new IOException("Departure has no fresh capture identity");
            if (value.route().startedAt() != entry.observedAt() || value.route().eventId() == 9
                    || !value.route().escapedSlots().isEmpty() || !value.offeredSlots().isEmpty()
                    || value.eventKeys().size() != 1) throw new IOException("Invalid departure checkpoint");
            return;
        }
        if (prior == null || !ACTIVE.contains(prior.kind()) || before == null)
            throw new IOException("Continuation lacks an active departure");
        var a = before.route(); var b = value.route();
        if (!a.sortieId().equals(b.sortieId()) || a.startedAt() != b.startedAt() || a.deckId() != b.deckId()
                || !a.instances().equals(b.instances()) || !a.masters().equals(b.masters())
                || b.observedAt() < a.observedAt() || value.captureAt() < before.captureAt()
                || !value.startKey().equals(before.startKey()) || value.eventKeys().size() != before.eventKeys().size() + 1
                || !value.eventKeys().subList(0, before.eventKeys().size()).equals(before.eventKeys())
                || (!before.recoverable() && value.recoverable())) throw new IOException("Conflicting route continuation");
        if (kind.equals("RETREAT")) {
            Set<Integer> expected = new HashSet<>(a.escapedSlots()); expected.addAll(before.offeredSlots());
            if (before.offeredSlots().isEmpty() || !expected.equals(new HashSet<>(b.escapedSlots()))
                    || !value.offeredSlots().isEmpty() || a.cell() != b.cell() || a.eventId() != b.eventId())
                throw new IOException("Unconfirmed retreat checkpoint");
        } else if (kind.equals("OFFER")) {
            if (!a.escapedSlots().equals(b.escapedSlots()) || value.offeredSlots().isEmpty()
                    || !before.offeredSlots().isEmpty() || a.cell() != b.cell() || a.eventId() != b.eventId())
                throw new IOException("Invalid retreat offer checkpoint");
        } else {
            if (!a.escapedSlots().equals(b.escapedSlots()) || !before.offeredSlots().isEmpty()
                    || !value.offeredSlots().isEmpty() || (b.fromCell() != null && b.fromCell() != a.cell())
                    || kind.equals("GOAL") != (b.eventId() == 9)) throw new IOException("Invalid routing/goal checkpoint");
        }
    }

    private void start(JsonObject json, RequestMetaData request, String key, long capture, long now) throws IOException {
        if (!mayStart) { invalidate("START_WITHOUT_SESSION_BOUNDARY", now); return; }
        if (observation != null && capture == 0) {
            invalidate("START_WITHOUT_CAPTURE_ID_AFTER_HISTORY", now); return;
        }
        var condition = AppCondition.get(); int deck = condition.getDeckId();
        if (!condition.isMapStart() || combined(condition, deck) || request == null
                || !Integer.toString(deck).equals(request.getParameter("api_deck_id"))
                || condition.getEscape() == null || !condition.getEscape().isEmpty()) {
            invalidate("INVALID_DEPARTURE_STATE", now); return;
        }
        var fleet = fleet(deck);
        MapStartNext node = observedNode(json);
        if (!requestAgrees(request, "api_maparea_id", node.getMapareaId())
                || !requestAgrees(request, "api_mapinfo_no", node.getMapinfoNo())) {
            invalidate("REQUEST_ROUTE_MISMATCH", now); return;
        }
        tracker.start(node, deck, fleet.get(0), fleet.get(1), now);
        if (tracker.snapshot() == null) { invalidate(tracker.reason(), now); return; }
        Observation value = new Observation(tracker.snapshot(), key, List.of(key), capture, capture > 0, List.of());
        checkpoint("START", value, "TRACKING", now); startKeys.add(key);
        mayStart = recovering = freshFleet = false; liveOffer = null;
    }

    private void next(JsonObject json, RequestMetaData request, String key, long capture, long now) throws IOException {
        var condition = AppCondition.get(); var saved = observation.route();
        if (!validHost(condition, saved) || !currentFleetMatches(saved)
                || !saved.escapedInstances().equals(condition.getEscape())) { invalidate("FLEET_OR_RETREAT_MISMATCH", now); return; }
        MapStartNext node = observedNode(json);
        if (!requestAgrees(request, "api_deck_id", saved.deckId())
                || !requestAgrees(request, "api_maparea_id", node.getMapareaId())
                || !requestAgrees(request, "api_mapinfo_no", node.getMapinfoNo())
                || !requestAgrees(request, "api_no", saved.cell())) {
            invalidate("REQUEST_ROUTE_MISMATCH", now); return;
        }
        if (!observation.offeredSlots().isEmpty()) { invalidate("RETREAT_DECISION_NOT_OBSERVED", now); return; }
        if (recovering) {
            if (!freshFleet || !observation.recoverable() || capture <= observation.captureAt()
                    || freshCapture <= observation.captureAt() || capture < freshCapture
                    || node.getFromNo() == null || node.getFromNo() != saved.cell()) {
                invalidate("RECOVERY_EVIDENCE_INCOMPLETE", now); return;
            }
            // A matching fleet/cell and a later timestamp do not prove continuity:
            // the player may have returned to port and departed again while this
            // process was stopped. Preserve the evidence, but never credit that
            // ambiguous continuation using the earlier acceptance/departure time.
            invalidate("RESTART_CONTINUITY_UNPROVEN", now); return;
        }
        var arrived = tracker.next(node, saved.deckId(), saved.instances(), saved.masters(), saved.escapedSlots().isEmpty(), now);
        var route = arrived.isPresent() ? tracker.completed() : tracker.snapshot();
        if (route == null) { invalidate(tracker.reason(), now); return; }
        Observation value = continued(route, key, capture, List.of());
        if (arrived.isEmpty()) { checkpoint("STEP", value, "TRACKING", now); return; }
        // Persist consumption before either publication. Failure here never causes a replay to mint another UUID.
        checkpoint("GOAL", value, "GOAL_CONSUMED", now); mayStart = false;
        List<PortArrivalFleet.Member> members = new ArrayList<>();
        for (int index = 0; index < route.instances().size(); index++) members.add(new PortArrivalFleet.Member(index + 1,
                route.instances().get(index), route.masters().get(index), route.escapedSlots().contains(index + 1)));
        storage.appendFleet(new PortArrivalFleet(PortArrivalFleet.FORMAT, arrived.get(), members));
        storage.append(arrived.get()); reason = "ARRIVAL_RECORDED";
    }

    private void observeFleet(JsonObject json, RequestMetaData request, long now) throws IOException {
        if (!active()) return;
        var saved = observation.route();
        if (!validHost(AppCondition.get(), saved) || !currentFleetMatches(saved) || !freshFleetMatches(json, saved)
                || !saved.escapedInstances().equals(AppCondition.get().getEscape())) {
            invalidate("FRESH_FLEET_MISMATCH", now); return;
        }
        freshCapture = captureAt(request, now);
        freshFleet = freshCapture > observation.captureAt();
        if (recovering) reason = freshFleet ? "AWAITING_CONNECTED_MAP_OBSERVATION" : "FRESH_FLEET_HAS_NO_NEW_CAPTURE_ID";
    }

    private void observeOffer(JsonObject json, String key, long capture, long now) throws IOException {
        var data = json.getJsonObject("api_data");
        if (data == null) { invalidate("INVALID_BATTLE_RESULT", now); return; }
        if (!data.containsKey("api_escape_flag") || exactInt(data, "api_escape_flag") != 1) return;
        if (recovering || !observation.offeredSlots().isEmpty() || !validHost(AppCondition.get(), observation.route())
                || !currentFleetMatches(observation.route())
                || !observation.route().escapedInstances().equals(AppCondition.get().getEscape())) {
            invalidate("RETREAT_OFFER_UNRECONCILED", now); return;
        }
        JsonObject escape = data.getJsonObject("api_escape");
        List<Integer> slots = new ArrayList<>();
        slots.add(singleIndex(escape == null ? null : escape.getJsonArray("api_escape_idx")));
        if (escape.containsKey("api_tow_idx")) slots.add(singleIndex(escape.getJsonArray("api_tow_idx")));
        var log = AppCondition.get().getBattleResultConfirm();
        if (!confirmedBattleMatches(log, observation.route(), slots)) { invalidate("RETREAT_BATTLE_MISMATCH", now); return; }
        var a = observation.route();
        var touched = new PortArrivalTracker.Snapshot(a.sortieId(), a.startedAt(), now, a.deckId(), a.instances(),
                a.masters(), a.escapedSlots(), a.cell(), a.eventId(), a.fromCell());
        tracker.restore(touched);
        checkpoint("OFFER", continued(touched, key, capture, slots), "RETREAT_OFFER_OBSERVED", now);
        liveOffer = log;
    }

    private void retreat(String key, long capture, long now) throws IOException {
        var saved = observation.route(); var slots = observation.offeredSlots();
        Set<Integer> expected = new HashSet<>(saved.escapedInstances());
        for (int slot : slots) expected.add(saved.instances().get(slot - 1));
        if (recovering || slots.isEmpty() || liveOffer == null || liveOffer != AppCondition.get().getBattleResultConfirm()
                || !validHost(AppCondition.get(), saved) || !currentFleetMatches(saved)
                || !confirmedBattleMatches(liveOffer, saved, slots) || !expected.equals(AppCondition.get().getEscape())) {
            invalidate("RETREAT_NOT_CONFIRMED", now); return;
        }
        if (!tracker.retreat(slots, now)) { invalidate(tracker.reason(), now); return; }
        checkpoint("RETREAT", continued(tracker.snapshot(), key, capture, List.of()), "RETREAT_CONFIRMED", now);
        liveOffer = null;
    }

    private static boolean confirmedBattleMatches(BattleLog log, PortArrivalTracker.Snapshot saved, List<Integer> slots) {
        if (log == null || log.getResult() == null || log.getResult().getEscape() == null
                || log.getNext() == null || log.getNext().isEmpty() || log.getDeckMap() == null
                || !log.getDeckMap().keySet().equals(Set.of(saved.deckId())) || slots.isEmpty()
                || slots.stream().distinct().count() != slots.size()
                || slots.stream().anyMatch(s -> s < 1 || s > saved.instances().size() || saved.escapedSlots().contains(s))) return false;
        var node = log.getNext().getLast();
        if (!Objects.equals(node.getMapareaId(), 1) || !Objects.equals(node.getMapinfoNo(), 6)
                || !Objects.equals(node.getNo(), saved.cell())) return false;
        var resultEscape = log.getResult().getEscape();
        List<Integer> resultSlots = new ArrayList<>();
        if (resultEscape.getEscapeIdx() == null || resultEscape.getEscapeIdx().size() != 1) return false;
        resultSlots.addAll(resultEscape.getEscapeIdx());
        if (resultEscape.getTowIdx() != null) {
            if (resultEscape.getTowIdx().size() != 1) return false;
            resultSlots.addAll(resultEscape.getTowIdx());
        }
        if (!slots.equals(resultSlots)) return false;
        List<Ship> members = log.getDeckMap().get(saved.deckId());
        if (members == null || members.size() < saved.instances().size()) return false;
        for (int i = 0; i < members.size(); i++) {
            Ship ship = members.get(i);
            if (i >= saved.instances().size()) { if (ship != null) return false; }
            else if (ship == null || !Objects.equals(ship.getId(), saved.instances().get(i))
                    || !Objects.equals(ship.getShipId(), saved.masters().get(i))) return false;
        }
        return true;
    }

    private static int singleIndex(JsonArray values) {
        if (values == null || values.size() != 1) throw new IllegalArgumentException("Ambiguous retreat index");
        return values.getJsonNumber(0).intValueExact();
    }
    private static boolean validHost(AppCondition state, PortArrivalTracker.Snapshot saved) {
        return state.isMapStart() && state.getDeckId() == saved.deckId() && !combined(state, saved.deckId());
    }
    private static boolean combined(AppCondition state, int deck) { return state.isCombinedFlag() && (deck == 1 || deck == 2); }
    private static boolean currentFleetMatches(PortArrivalTracker.Snapshot saved) {
        var current = fleet(saved.deckId());
        return saved.instances().equals(current.get(0)) && saved.masters().equals(current.get(1));
    }
    private static List<List<Integer>> fleet(int deck) {
        var port = DeckPortCollection.get().getDeckPortMap().get(deck);
        if (port == null || port.getShip() == null) throw new IllegalArgumentException("Missing observed fleet");
        List<Integer> members = new ArrayList<>(port.getShip());
        while (!members.isEmpty() && Objects.equals(members.getLast(), -1)) members.removeLast();
        List<Integer> masters = new ArrayList<>();
        for (Integer id : members) {
            Ship ship = id == null ? null : ShipCollection.get().getShipMap().get(id);
            if (ship == null || ship.getShipId() == null || ship.getShipId() <= 0) throw new IllegalArgumentException("Unknown fleet member");
            masters.add(ship.getShipId());
        }
        return List.of(members, masters);
    }
    private static boolean freshFleetMatches(JsonObject json, PortArrivalTracker.Snapshot saved) {
        JsonObject data = json.getJsonObject("api_data");
        if (data == null) return false;
        JsonArray decks = data.getJsonArray("api_deck_data"), ships = data.getJsonArray("api_ship_data");
        if (decks == null || ships == null) return false;
        List<Integer> observed = null;
        for (var item : decks) {
            JsonObject deck = item.asJsonObject();
            if (exactInt(deck, "api_id") != saved.deckId()) continue;
            if (observed != null) return false;
            observed = new ArrayList<>();
            for (var ship : deck.getJsonArray("api_ship")) observed.add(((JsonNumber) ship).intValueExact());
            while (!observed.isEmpty() && observed.getLast() == -1) observed.removeLast();
        }
        if (!saved.instances().equals(observed)) return false;
        Map<Integer, Integer> masters = new HashMap<>();
        for (var item : ships) {
            var ship = item.asJsonObject(); int id = exactInt(ship, "api_id"), master = exactInt(ship, "api_ship_id");
            if (id <= 0 || master <= 0 || masters.putIfAbsent(id, master) != null) return false;
        }
        for (int i = 0; i < saved.instances().size(); i++)
            if (!saved.masters().get(i).equals(masters.get(saved.instances().get(i)))) return false;
        return true;
    }

    private Observation continued(PortArrivalTracker.Snapshot route, String key, long capture, List<Integer> offered) {
        List<String> keys = new ArrayList<>(observation.eventKeys()); keys.add(key);
        return new Observation(route, observation.startKey(), keys, Math.max(capture, observation.captureAt()),
                observation.recoverable() && capture > 0, offered);
    }
    private boolean active() { return last != null && ACTIVE.contains(last.kind()) && observation != null; }
    private void checkpoint(String kind, Observation value, String why, long now) throws IOException {
        var payload = value == null ? JSON.nullNode() : JSON.valueToTree(value);
        last = journal.append(last, kind, now, payload, why); observation = value; reason = why;
    }
    private void invalidate(String why, long now) throws IOException {
        tracker.clear(); recovering = freshFleet = mayStart = false; liveOffer = null; reason = why;
        if (blocked) return;
        if (active() || (last != null && last.kind().equals("BOUNDARY")))
            checkpoint("INVALID", observation, why, Math.max(now, last.observedAt()));
        LoggerHolder.get().warn("港到達の追跡を保留しました: {}", why);
    }
    private void block(String why, Exception error) {
        tracker.clear(); blocked = true; recovering = freshFleet = mayStart = false; liveOffer = null; reason = why;
        LoggerHolder.get().warn("港到達の追跡を保留しました: " + why + "。戦闘ログからの補完は行いません。", error);
    }
    private static boolean successful(JsonObject json) { return json != null && exactInt(json, "api_result") == 1; }
    private static long captureAt(RequestMetaData request, long now) {
        if (request == null) return 0;
        var header = request.getHeader("x-koukainissikai-requestat");
        if (header == null || header.isEmpty()) return 0;
        long value = Long.parseLong(header.get());
        if (value <= 0 || value > now || now - value > 86_400_000L)
            throw new IllegalArgumentException("Invalid or stale capture timestamp");
        return value;
    }
    private static int exactInt(JsonObject object, String name) {
        if (!(object.get(name) instanceof JsonNumber number)) throw new IllegalArgumentException("Missing numeric observation field");
        return number.intValueExact();
    }
    private static MapStartNext observedNode(JsonObject json) {
        JsonObject data = json.getJsonObject("api_data");
        if (data == null) throw new IllegalArgumentException("Missing map observation");
        var node = new MapStartNext();
        node.setMapareaId(exactInt(data, "api_maparea_id")); node.setMapinfoNo(exactInt(data, "api_mapinfo_no"));
        node.setNo(exactInt(data, "api_no")); node.setEventId(exactInt(data, "api_event_id"));
        if (data.containsKey("api_from_no")) node.setFromNo(exactInt(data, "api_from_no"));
        return node;
    }
    private static boolean requestAgrees(RequestMetaData request, String name, int observed) {
        if (request == null) return true;
        String value = request.getParameter(name);
        return value == null || value.equals(Integer.toString(observed));
    }
    private static String eventKey(String uri, JsonObject json, RequestMetaData request, long capture) throws Exception {
        StringBuilder canonical = new StringBuilder(uri).append('|').append(capture);
        if (request != null) canonical.append('|').append(request.getParameter("api_deck_id"));
        JsonObject data = json.get("api_data") instanceof JsonObject value ? value : null;
        if (data != null) {
            for (String name : List.of("api_maparea_id", "api_mapinfo_no", "api_no", "api_event_id", "api_from_no", "api_escape_flag"))
                canonical.append('|').append(name).append('=').append(data.get(name));
            JsonObject escape = data.getJsonObject("api_escape");
            if (escape != null) canonical.append('|').append(escape.get("api_escape_idx")).append('|').append(escape.get("api_tow_idx"));
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    }
    public static List<PortArrival> read(long from, long to) throws IOException { return SHARED.store.get().readRange(from, to); }
    public static Optional<PortArrivalFleet> readFleet(PortArrival arrival) throws IOException { return SHARED.store.get().readFleet(arrival); }
}
