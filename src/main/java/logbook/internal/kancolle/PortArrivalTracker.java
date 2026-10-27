package logbook.internal.kancolle;

import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import logbook.bean.MapStartNext;

/** Pure route/slot validation. Only the runtime may restore a checkpoint after live reconciliation. */
final class PortArrivalTracker {
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    private Snapshot current;
    private Snapshot completed;
    private String reason = "NO_OBSERVED_START";

    /** Departure positions remain stable; escapedSlots are explicitly known, one-based vacancies. */
    record Snapshot(String sortieId, long startedAt, long observedAt, int deckId,
            List<Integer> instances, List<Integer> masters, List<Integer> escapedSlots,
            int cell, int eventId, Integer fromCell) {
        Snapshot {
            int size = instances == null ? 0 : instances.size();
            if (sortieId == null || !UUID.fromString(sortieId).toString().equals(sortieId)
                    || startedAt <= 0 || observedAt < startedAt || observedAt - startedAt > 86_400_000L
                    || !sameDay(startedAt, observedAt) || deckId < 1 || deckId > 4
                    || !valid(instances) || !valid(masters) || instances.size() != masters.size()
                    || instances.stream().distinct().count() != instances.size()
                    || escapedSlots == null || escapedSlots.stream().anyMatch(i -> i == null || i < 1 || i > size)
                    || escapedSlots.stream().distinct().count() != escapedSlots.size()
                    || escapedSlots.size() >= instances.size() || cell < 0 || eventId < 0
                    || (fromCell != null && fromCell < 0)) throw new IllegalArgumentException("Invalid arrival tracking snapshot");
            instances = List.copyOf(instances); masters = List.copyOf(masters);
            escapedSlots = escapedSlots.stream().sorted().toList();
        }
        Set<Integer> escapedInstances() {
            Set<Integer> result = new HashSet<>();
            for (int slot : escapedSlots) result.add(instances.get(slot - 1));
            return Set.copyOf(result);
        }
    }

    void clear() { current = completed = null; }
    Snapshot snapshot() { return current; }
    Snapshot completed() { return completed; }
    String reason() { return reason; }
    void restore(Snapshot snapshot) {
        if (snapshot == null || snapshot.eventId() == 9) throw new IllegalArgumentException("Cannot restore a completed route");
        current = snapshot; completed = null; reason = "RESTORED_AFTER_LIVE_RECONCILIATION";
    }
    void start(MapStartNext node, int deckId, List<Integer> ships, List<Integer> shipMasters, long time) {
        clear();
        if (!map16(node) || node.getNo() == null || node.getNo() < 0
                || node.getEventId() == null || node.getEventId() < 0
                || Objects.equals(node.getEventId(), 9) || deckId < 1 || deckId > 4
                || time <= 0 || !valid(ships) || !valid(shipMasters)
                || ships.size() != shipMasters.size() || ships.stream().distinct().count() != ships.size()) {
            reason = "INVALID_DEPARTURE"; return;
        }
        current = new Snapshot(UUID.randomUUID().toString(), time, time, deckId, ships, shipMasters,
                List.of(), node.getNo(), node.getEventId(), node.getFromNo());
        reason = "TRACKING";
    }
    Optional<PortArrival> next(MapStartNext node, int deckId, List<Integer> ships,
            List<Integer> shipMasters, boolean noRetreat, long time) {
        if (current == null) return Optional.empty();
        if (!timeValid(time)) { clear(); return Optional.empty(); }
        if (!map16(node) || node.getNo() == null || node.getNo() < 0
                || node.getEventId() == null || node.getEventId() < 0 || current.deckId() != deckId
                || !current.instances().equals(ships) || !current.masters().equals(shipMasters)) {
            reason = "MAP_OR_FLEET_MISMATCH"; clear(); return Optional.empty();
        }
        // If the API supplies a predecessor, do not bridge an observed routing gap.
        if (node.getFromNo() != null && node.getFromNo() != current.cell()) {
            reason = "ROUTING_GAP"; clear(); return Optional.empty();
        }
        current = new Snapshot(current.sortieId(), current.startedAt(), time, current.deckId(), current.instances(),
                current.masters(), current.escapedSlots(), node.getNo(), node.getEventId(), node.getFromNo());
        // Primary public implementation identifies map 1-6 event 9 as the goal.
        // Neither B/D battle results nor api_port/port are substituted for this event.
        if (!Objects.equals(node.getEventId(), 9)) return Optional.empty();
        var arrival = new PortArrival(PortArrival.FORMAT, current.sortieId(), current.startedAt(), time, "1-6",
                current.deckId(), current.masters(), noRetreat && current.escapedSlots().isEmpty());
        completed = current; current = null;
        reason = "OBSERVED_GOAL";
        return Optional.of(arrival);
    }

    boolean retreat(List<Integer> slots, long time) {
        if (current == null || !timeValid(time) || slots == null || slots.isEmpty()
                || slots.stream().distinct().count() != slots.size()
                || slots.stream().anyMatch(slot -> slot == null || slot < 1 || slot > current.instances().size()
                        || current.escapedSlots().contains(slot))) {
            reason = "UNCONFIRMED_RETREAT"; clear(); return false;
        }
        List<Integer> escaped = new ArrayList<>(current.escapedSlots()); escaped.addAll(slots);
        if (escaped.size() >= current.instances().size()) { reason = "NO_SURVIVING_SHIP"; clear(); return false; }
        current = new Snapshot(current.sortieId(), current.startedAt(), time, current.deckId(), current.instances(),
                current.masters(), escaped, current.cell(), current.eventId(), current.fromCell());
        reason = "CONFIRMED_RETREAT"; return true;
    }

    private boolean timeValid(long time) {
        if (time < current.observedAt()) { reason = "CLOCK_REVERSED"; return false; }
        if (time - current.startedAt() > 86_400_000L) { reason = "STALE_SORTIE"; return false; }
        if (!sameDay(current.startedAt(), time)) { reason = "DATE_BOUNDARY"; return false; }
        return true;
    }
    static boolean sameDay(long first, long next) {
        var a = Instant.ofEpochMilli(first).atZone(JST); var b = Instant.ofEpochMilli(next).atZone(JST);
        // Both civil midnight and the game's daily 05:00 boundary invalidate recovery.
        return a.toLocalDate().equals(b.toLocalDate()) && a.minusHours(5).toLocalDate().equals(b.minusHours(5).toLocalDate());
    }
    private static boolean map16(MapStartNext node) {
        return node != null && Objects.equals(node.getMapareaId(), 1) && Objects.equals(node.getMapinfoNo(), 6);
    }
    private static boolean valid(List<Integer> ids) {
        return ids != null && !ids.isEmpty() && ids.size() <= 7
                && ids.stream().allMatch(id -> id != null && id > 0);
    }
}
