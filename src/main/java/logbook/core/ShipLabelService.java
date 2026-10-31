package logbook.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import logbook.bean.Ship;
import logbook.bean.ShipCollection;
import logbook.bean.ShipLabelCollection;
import logbook.internal.kancolle.SeaAreas;
import logbook.plugin.api.ShipLabelsV1.Target;
import logbook.plugin.api.ShipLabelsV1.Result;
import logbook.plugin.api.ShipLabelsV1.Outcome;

/** Host-owned manual-label updates; all validation finishes before the first change. */
public final class ShipLabelService {
    private ShipLabelService() {
    }

    public static List<Target> capture(List<Integer> shipIds) {
        return GameStateService.shared().read(
                () -> captureFrom(ShipCollection.get().getShipMap(), ShipLabelCollection.get().getLabels(), shipIds));
    }

    public static Result apply(List<Target> targets, String label, boolean add) {
        return GameStateService.shared().applyLocalUpdate("ship-labels",
                () -> applyTo(ShipCollection.get().getShipMap(), ShipLabelCollection.get().getLabels(), targets, label,
                        add,
                        ship -> {
                            var area = SeaAreas.fromArea(ship.getSallyArea());
                            return area == null ? null : area.toString();
                        }),
                result -> result.outcome() == Outcome.APPLIED);
    }

    static List<Target> captureFrom(Map<Integer, Ship> ships, Map<Integer, Set<String>> labels,
            List<Integer> shipIds) {
        return GameStateService.shared().read(() -> captureLocked(ships, labels, shipIds));
    }

    private static List<Target> captureLocked(Map<Integer, Ship> ships, Map<Integer, Set<String>> labels,
            List<Integer> shipIds) {
        List<Target> result = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (Integer id : List.copyOf(shipIds)) {
            if (id <= 0 || !seen.add(id))
                throw new IllegalArgumentException("Invalid or repeated ship selection");
            Ship ship = ships.get(id);
            if (!validIdentity(ship, id))
                throw new IllegalStateException("Selected ship is unavailable: " + id);
            result.add(new Target(id, ship.getShipId(), copyLabels(labels.get(id))));
        }
        return List.copyOf(result);
    }

    /** Same host state lock as the public commands; package scope enables data-only tests. */
    static Result applyTo(Map<Integer, Ship> ships, Map<Integer, Set<String>> labels,
            List<Target> request, String label, boolean add, Function<Ship, String> eventLabel) {
        return GameStateService.shared().read(() -> applyLocked(ships, labels, request, label, add, eventLabel));
    }

    private static Result applyLocked(Map<Integer, Ship> ships, Map<Integer, Set<String>> labels,
            List<Target> request, String label, boolean add, Function<Ship, String> eventLabel) {
        Objects.requireNonNull(label);
        if (label.isEmpty())
            throw new IllegalArgumentException("Empty manual label");
        Objects.requireNonNull(eventLabel);
        List<Target> targets = List.copyOf(request);
        Set<Integer> seen = new HashSet<>();
        Map<Integer, Set<String>> plan = new LinkedHashMap<>();
        Map<Integer, Set<String>> display = new LinkedHashMap<>();
        Set<Integer> changed = new LinkedHashSet<>();
        for (Target target : targets) {
            if (!seen.add(target.shipId()))
                throw new IllegalArgumentException("Repeated ship selection");
            Ship ship = ships.get(target.shipId());
            if (!validIdentity(ship, target.shipId()) || ship.getShipId() != target.masterId())
                return rejected(Outcome.MISSING_SHIP);
            Set<String> current;
            try {
                current = copyLabels(labels.get(target.shipId()));
            } catch (IllegalStateException invalid) {
                return rejected(Outcome.INVALID_STATE);
            }
            if (!current.equals(target.expectedLabels()))
                return rejected(Outcome.CONFLICT);
            Set<String> next = new LinkedHashSet<>(current);
            boolean modified = add ? next.add(label) : next.remove(label);
            if (modified) {
                changed.add(target.shipId());
                plan.put(target.shipId(), next);
            }
            Set<String> visible = new LinkedHashSet<>();
            String area = eventLabel.apply(ship);
            if (area != null)
                visible.add(area);
            visible.addAll(next);
            display.put(target.shipId(), visible);
        }
        // Build the immutable result before modifying the existing in-memory map.
        Result result = new Result(changed.isEmpty() ? Outcome.UNCHANGED : Outcome.APPLIED, changed, display);
        for (var entry : plan.entrySet()) {
            if (entry.getValue().isEmpty())
                labels.remove(entry.getKey());
            else
                labels.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
        }
        return result;
    }

    private static boolean validIdentity(Ship ship, int id) {
        return ship != null && Objects.equals(ship.getId(), id) && ship.getShipId() != null && ship.getShipId() > 0;
    }

    private static Set<String> copyLabels(Set<String> labels) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (labels != null)
            for (String label : new ArrayList<>(labels)) {
                if (label == null)
                    throw new IllegalStateException("Malformed stored manual labels");
                result.add(label);
            }
        return result;
    }

    private static Result rejected(Outcome outcome) {
        return new Result(outcome, Set.of(), Map.of());
    }
}
