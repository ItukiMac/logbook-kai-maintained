package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import logbook.bean.DeckPort;
import logbook.bean.Ship;
import logbook.bean.ShipMst;
import logbook.plugin.api.ShipRosterV1;
import org.junit.jupiter.api.Test;

/** Synthetic in-memory fixtures. No Config/default runtime or external storage. */
class ShipRosterContractTest {
    private static Ship ship(int id, int master, int lv, List<Integer> slots, Integer extra) {
        Ship s = new Ship();
        s.setId(id);
        s.setShipId(master);
        s.setLv(lv);
        s.setSlot(slots);
        s.setSlotEx(extra);
        s.setSallyArea(4);
        return s;
    }

    private static ShipMst master(int id, Integer sort) {
        ShipMst s = new ShipMst();
        s.setId(id);
        s.setSortId(sort);
        s.setName("fixture");
        return s;
    }

    private static DeckPort fleet(int id, String name, List<Integer> ids) {
        DeckPort d = new DeckPort();
        d.setId(id);
        d.setName(name);
        d.setShip(ids);
        return d;
    }

    private static ShipRosterV1.Snapshot make(Map<Integer, Ship> ships,
            Map<Integer, ShipMst> masters, Map<Integer, DeckPort> fleets,
            Map<Integer, Set<String>> labels) {
        return ShipRosterService.snapshot(ships, masters, fleets, labels);
    }

    @Test
    void versionAndPublicSignaturesDoNotExposeBeansOrJavafx() {
        assertEquals(1, ShipRosterV1.version());
        for (var method : ShipRosterV1.class.getDeclaredMethods()) {
            String signature = method.toGenericString();
            assertFalse(signature.contains("logbook.bean"), signature);
            assertFalse(signature.contains("javafx"), signature);
        }
        for (var type : List.of(ShipRosterV1.ShipView.class,
                ShipRosterV1.FleetView.class, ShipRosterV1.Snapshot.class)) {
            assertTrue(type.isRecord());
            assertTrue(Modifier.isFinal(type.getModifiers()));
        }
    }

    @Test
    void snapshotDoesNotShareMutableSlotsLabelsOrFleetMembers() {
        List<Integer> slots = new ArrayList<>(List.of(101, 102));
        Set<String> labels = new LinkedHashSet<>(List.of("orange", "blue"));
        List<Integer> members = new ArrayList<>(List.of(5, -1, 5));
        Ship owned = ship(5, 11, 42, slots, 201);
        DeckPort port = fleet(1, "first", members);
        var snapshot = make(Map.of(5, owned), Map.of(11, master(11, 10)),
                Map.of(1, port), Map.of(5, labels));
        slots.clear();
        labels.clear();
        members.clear();
        owned.setLv(99);
        owned.setSlotEx(300);
        port.setName("changed");
        var row = snapshot.ships().get(5);
        assertEquals(42, row.level());
        assertEquals(List.of(101, 102), row.equipmentIds());
        assertEquals(201, row.expansionEquipmentId());
        assertEquals(Set.of("orange", "blue"), row.labels());
        assertEquals(List.of(5, -1, 5), snapshot.allDeckShipIds());
        assertEquals("first", snapshot.fleets().getFirst().name());
        assertThrows(UnsupportedOperationException.class, () -> row.equipmentIds().clear());
        assertThrows(UnsupportedOperationException.class, () -> row.labels().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.ships().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.fleets().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.warnings().clear());
    }

    @Test
    void sortedRosterUsesMasterOrderThenInstanceId() {
        Map<Integer, Ship> ships = new LinkedHashMap<>();
        ships.put(3, ship(3, 10, 1, List.of(), 0));
        ships.put(2, ship(2, 20, 1, List.of(), 0));
        ships.put(1, ship(1, 10, 1, List.of(), 0));
        var result = make(ships, Map.of(10, master(10, 5), 20, master(20, 2)),
                Map.of(), Map.of());
        assertEquals(List.of(2, 1, 3), result.sortedShips().stream()
                .map(ShipRosterV1.ShipView::id).toList());
    }

    @Test
    void missingMasterIsRetainedWithUnknownSortOrder() {
        var result = make(Map.of(3, ship(3, 999, 50, List.of(), null)),
                Map.of(), Map.of(), Map.of());
        assertEquals(Integer.MAX_VALUE, result.ships().get(3).sortOrder());
        assertTrue(result.warnings().contains("Missing ship master: 999"));
    }

    @Test
    void nullOrMismatchedShipIdentityFailsClosedWithWarning() {
        Map<Integer, Ship> ships = new LinkedHashMap<>();
        ships.put(1, ship(2, 20, 1, List.of(), 0));
        ships.put(3, null);
        var result = make(ships, Map.of(), Map.of(), Map.of());
        assertTrue(result.ships().isEmpty());
        assertEquals(2, result.warnings().size());
    }

    @Test
    void nullSlotAndLabelEntriesAreFilteredNotFabricated() {
        var row = ship(1, 10, 1, Arrays.asList(12, null, -1), null);
        Set<String> labels = new LinkedHashSet<>(Arrays.asList("real", null));
        var result = make(Map.of(1, row), Map.of(10, master(10, 1)),
                Map.of(), Map.of(1, labels));
        assertEquals(List.of(12, -1), result.ships().get(1).equipmentIds());
        assertEquals(Set.of("real"), result.ships().get(1).labels());
        assertTrue(result.warnings().stream().anyMatch(v -> v.contains("Missing ship equipment")));
        assertTrue(result.warnings().stream().anyMatch(v -> v.contains("Null manual ship label")));
    }

    @Test
    void missingSlotsAndLabelsYieldEmptyImmutableCopies() {
        var result = make(Map.of(1, ship(1, 10, 1, null, null)),
                Map.of(10, master(10, null)), Map.of(), Map.of());
        assertTrue(result.ships().get(1).equipmentIds().isEmpty());
        assertTrue(result.ships().get(1).labels().isEmpty());
        assertEquals(Integer.MAX_VALUE, result.ships().get(1).sortOrder());
    }

    @Test
    void fleetOrderDuplicatesAndVacanciesArePreserved() {
        Map<Integer, DeckPort> fleets = new LinkedHashMap<>();
        fleets.put(2, fleet(2, "second", Arrays.asList(2, -1, 1, null)));
        fleets.put(1, fleet(1, "first", List.of(1, 2)));
        var result = make(Map.of(1, ship(1, 10, 1, List.of(), 0), 2, ship(2, 10, 1, List.of(), 0)),
                Map.of(10, master(10, 1)), fleets, Map.of());
        assertEquals(List.of(2, -1, 1, 1, 2), result.allDeckShipIds());
        assertTrue(result.warnings().stream().anyMatch(v -> v.contains("Missing fleet members")));
    }

    @Test
    void unresolvedFleetMembersAreRetainedAsEvidenceNotInventedShips() {
        var result = make(Map.of(), Map.of(), Map.of(1, fleet(1, "one", List.of(404, -1))), Map.of());
        assertEquals(List.of(404, -1), result.allDeckShipIds());
        assertTrue(result.ships().isEmpty());
        assertTrue(result.warnings().contains("Unresolved fleet ship: 404"));
    }

    @Test
    void badFleetIdentityIsNotSilentlyRemapped() {
        var result = make(Map.of(), Map.of(), Map.of(2, fleet(3, "wrong", List.of(1))), Map.of());
        assertTrue(result.fleets().isEmpty());
        assertTrue(result.warnings().contains("Incomplete or mismatched fleet identity"));
    }

    @Test
    void directRecordConstructorsAlsoDefensivelyCopy() {
        List<Integer> slots = new ArrayList<>(List.of(1));
        Set<String> labels = new LinkedHashSet<>(Set.of("x"));
        var row = new ShipRosterV1.ShipView(1, 2, 3, 4, 5, slots, 6, labels);
        var map = new LinkedHashMap<>(Map.of(1, row));
        List<ShipRosterV1.FleetView> fleets = new ArrayList<>(List.of(
                new ShipRosterV1.FleetView(1, "n", List.of(1))));
        var snapshot = new ShipRosterV1.Snapshot(map, fleets, List.of());
        slots.add(9);
        labels.add("y");
        map.clear();
        fleets.clear();
        assertEquals(List.of(1), snapshot.ships().get(1).equipmentIds());
        assertEquals(Set.of("x"), snapshot.ships().get(1).labels());
        assertEquals(1, snapshot.fleets().size());
    }
}
