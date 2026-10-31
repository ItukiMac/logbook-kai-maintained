package logbook.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import logbook.bean.DeckPort;
import logbook.bean.DeckPortCollection;
import logbook.bean.Ship;
import logbook.bean.ShipCollection;
import logbook.bean.ShipLabelCollection;
import logbook.bean.ShipMst;
import logbook.bean.ShipMstCollection;
import logbook.plugin.api.ShipRosterV1.FleetView;
import logbook.plugin.api.ShipRosterV1.ShipView;
import logbook.plugin.api.ShipRosterV1.Snapshot;

/** Host-side copying of the existing mutable ship, fleet and label beans. */
public final class ShipRosterService {
    private ShipRosterService() {
    }

    public static Snapshot snapshot() {
        return LogBookCoreServices.readGameState(() -> captureSnapshot());
    }

    private static Snapshot captureSnapshot() {
        return snapshot(ShipCollection.get().getShipMap(),
                ShipMstCollection.get().getShipMap(),
                DeckPortCollection.get().getDeckPortMap(),
                ShipLabelCollection.get().getLabels());
    }

    /**
     * Exposed to same-package synthetic tests with caller-owned fixtures. Public
     * live capture holds the host state lock. Direct legacy plugin writes that
     * ignore that lock can still race. Never returns a live bean or collection.
     */
    static Snapshot snapshot(Map<Integer, Ship> ships, Map<Integer, ShipMst> masters,
            Map<Integer, DeckPort> fleets, Map<Integer, Set<String>> labels) {
        Map<Integer, ShipView> result = new LinkedHashMap<>();
        List<FleetView> fleetResult = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<Integer, ShipMst> masterCopy = new LinkedHashMap<>(masters);
        Map<Integer, Set<String>> labelCopy = new LinkedHashMap<>(labels);

        for (var entry : new ArrayList<>(ships.entrySet())) {
            Integer key = entry.getKey();
            Ship ship = entry.getValue();
            if (key == null || ship == null || ship.getId() == null
                    || !key.equals(ship.getId()) || ship.getShipId() == null) {
                warnings.add("Incomplete or mismatched ship identity");
                continue;
            }
            ShipMst master = masterCopy.get(ship.getShipId());
            if (master == null)
                warnings.add("Missing ship master: " + ship.getShipId());
            int sort = master == null || master.getSortId() == null
                    ? Integer.MAX_VALUE
                    : master.getSortId();

            List<Integer> equipment = cleanIntegers(ship.getSlot(), warnings, "ship equipment");
            Set<String> ownedLabels = new LinkedHashSet<>();
            Set<String> currentLabels = labelCopy.get(key);
            if (currentLabels != null) {
                for (String label : new ArrayList<>(currentLabels)) {
                    if (label == null)
                        warnings.add("Null manual ship label: " + key);
                    else
                        ownedLabels.add(label);
                }
            }
            result.put(key, new ShipView(key, ship.getShipId(), ship.getLv(),
                    sort, ship.getSallyArea(), equipment, ship.getSlotEx(), ownedLabels));
        }

        for (var entry : new ArrayList<>(fleets.entrySet())) {
            Integer key = entry.getKey();
            DeckPort fleet = entry.getValue();
            if (key == null || fleet == null || fleet.getId() == null
                    || !key.equals(fleet.getId())) {
                warnings.add("Incomplete or mismatched fleet identity");
                continue;
            }
            List<Integer> ids = cleanIntegers(fleet.getShip(), warnings, "fleet members");
            for (Integer id : ids) {
                if (id > 0 && !result.containsKey(id))
                    warnings.add("Unresolved fleet ship: " + id);
            }
            fleetResult.add(new FleetView(key, fleet.getName(), ids));
        }
        return new Snapshot(result, fleetResult, warnings);
    }

    private static List<Integer> cleanIntegers(Collection<Integer> source, List<String> warnings, String field) {
        if (source == null)
            return List.of();
        List<Integer> copy = new ArrayList<>();
        for (Integer id : new ArrayList<>(source)) {
            if (id == null)
                warnings.add("Missing " + field + " entry");
            else
                copy.add(id);
        }
        return List.copyOf(copy);
    }
}
