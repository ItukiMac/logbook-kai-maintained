package logbook.internal.kancolle;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import logbook.bean.MapStartNext;

/** One observed start -> at most one observed goal. No state is restored after a process restart. */
final class PortArrivalTracker {
    private String id;
    private long started;
    private int deck;
    private List<Integer> instances;
    private List<Integer> masters;
    private Integer lastCell;

    void clear() { id = null; instances = masters = null; lastCell = null; }
    void start(MapStartNext node, int deckId, List<Integer> ships, List<Integer> shipMasters, long time) {
        clear();
        if (!map16(node) || node.getNo() == null || node.getNo() < 0
                || Objects.equals(node.getEventId(), 9) || deckId < 1 || deckId > 4
                || time <= 0 || !valid(ships) || !valid(shipMasters)
                || ships.size() != shipMasters.size() || ships.stream().distinct().count() != ships.size()) return;
        id = UUID.randomUUID().toString(); started = time; deck = deckId;
        instances = List.copyOf(ships); masters = List.copyOf(shipMasters); lastCell = node.getNo();
    }
    Optional<PortArrival> next(MapStartNext node, int deckId, List<Integer> ships,
            List<Integer> shipMasters, boolean noRetreat, long time) {
        if (id == null) return Optional.empty();
        if (!map16(node) || node.getNo() == null || node.getNo() < 0 || time < started
                || time - started > 86_400_000L || deck != deckId
                || !instances.equals(ships) || !masters.equals(shipMasters)) {
            clear(); return Optional.empty();
        }
        // If the API supplies a predecessor, do not bridge an observed routing gap.
        if (node.getFromNo() != null && !node.getFromNo().equals(lastCell)) {
            clear(); return Optional.empty();
        }
        lastCell = node.getNo();
        // Primary public implementation identifies map 1-6 event 9 as the goal.
        // Neither B/D battle results nor api_port/port are substituted for this event.
        if (!Objects.equals(node.getEventId(), 9)) return Optional.empty();
        var arrival = new PortArrival(PortArrival.FORMAT, id, started, time, "1-6", deck, masters, noRetreat);
        clear(); // Also consumes duplicate responses, including when persistence subsequently fails.
        return Optional.of(arrival);
    }
    private static boolean map16(MapStartNext node) {
        return node != null && Objects.equals(node.getMapareaId(), 1) && Objects.equals(node.getMapinfoNo(), 6);
    }
    private static boolean valid(List<Integer> ids) {
        return ids != null && !ids.isEmpty() && ids.size() <= 7
                && ids.stream().allMatch(id -> id != null && id > 0);
    }
}
