package logbook.plugin.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import logbook.core.ShipRosterService;

/**
 * Versioned, read-only ship and fleet membership contract.
 *
 * Values are detached after capture under the host API/local-command lock.
 * Uncooperative legacy plugin writes are not intercepted; the UI still
 * resolves legacy ships for its existing editor and rendering path.
 */
public final class ShipRosterV1 {
    private ShipRosterV1() {
    }

    public static int version() {
        return 1;
    }

    public static Snapshot snapshot() {
        return ShipRosterService.snapshot();
    }

    public record ShipView(int id, int masterId, Integer level, int sortOrder,
            Integer sallyArea, List<Integer> equipmentIds, Integer expansionEquipmentId,
            Set<String> labels) {
        public ShipView {
            equipmentIds = List.copyOf(equipmentIds);
            labels = Collections.unmodifiableSet(new LinkedHashSet<>(labels));
        }
    }

    public record FleetView(int id, String name, List<Integer> shipIds) {
        public FleetView {
            shipIds = List.copyOf(shipIds);
        }
    }

    public record Snapshot(Map<Integer, ShipView> ships, List<FleetView> fleets,
            List<String> warnings) {
        public Snapshot {
            ships = Collections.unmodifiableMap(new LinkedHashMap<>(ships));
            fleets = List.copyOf(fleets);
            warnings = List.copyOf(warnings);
        }

        /** Existing ship-list ordering: master sort position, then instance ID. */
        public List<ShipView> sortedShips() {
            return ships.values().stream()
                    .sorted(Comparator.comparingInt(ShipView::sortOrder)
                            .thenComparingInt(ShipView::id))
                    .toList();
        }

        /** Fleet order and duplicate membership are deliberately retained. */
        public List<Integer> allDeckShipIds() {
            return fleets.stream().flatMap(fleet -> fleet.shipIds().stream()).toList();
        }
    }
}
