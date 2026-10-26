package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import logbook.bean.MissionCondition;
import logbook.bean.Ship;
import org.junit.jupiter.api.Test;

/** Listed numerical fields only. This is NOT a certification of full expedition semantics. */
class ReviewedMissionScalarTest {
    private MissionCondition load(String id) throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream("logbook/mission/" + id + ".json")) {
            assertNotNull(in); return PreparedGameDefinitions.JSON.readValue(in, MissionCondition.class);
        }
    }
    private List<MissionCondition> nodes(MissionCondition root) {
        List<MissionCondition> found = new ArrayList<>(); found.add(root);
        if (root.getConditions() != null) for (var child : root.getConditions()) found.addAll(nodes(child));
        return found;
    }
    private Ship ship(int level) { Ship s = new Ship(); s.setLv(level); return s; }
    @Test void correctedFlagshipLevelsRejectBelowAndAcceptAtBoundary() throws Exception {
        for (var e : Map.of("1/A1",5,"2/15",8,"2/B2",45,"2/B6",75,"7/42",45,"7/43",55).entrySet()) {
            var matching = nodes(load(e.getKey())).stream().filter(c -> Objects.equals(c.getOrder(),1) && c.getLevel()!=null).toList();
            assertEquals(1, matching.size(), e.getKey());
            var c = matching.get(0); assertEquals(e.getValue(), c.getLevel());
            assertNull(c.getShipType(), "This fixture tests an independent numeric-only clause");
            assertFalse(c.test(new ArrayList<>(List.of(ship(e.getValue()-1)))), e.getKey());
            assertTrue(c.test(new ArrayList<>(List.of(ship(e.getValue())))), e.getKey());
        }
    }
    @Test void fleetLevelSumBoundariesAreEnforcedByTheActualPredicate() throws Exception {
        for (var e : Map.of("1/A1",10,"2/B2",220).entrySet()) {
            var c = nodes(load(e.getKey())).stream().filter(n -> "レベル".equals(n.getCountType())).findFirst().orElseThrow();
            assertEquals(e.getValue(), c.getValue());
            assertFalse(c.test(new ArrayList<>(List.of(ship(e.getValue()-2),ship(1)))));
            assertTrue(c.test(new ArrayList<>(List.of(ship(e.getValue()-1),ship(1)))));
        }
    }
    @Test void newlyRequiredFleetStatsArePresentInTheTypedConsumer() throws Exception {
        for (var e : Map.of("1/A3", Map.of("索敵",60), "2/B2", Map.of("対空",160,"対潜",160,"索敵",140)).entrySet()) {
            for (var field : e.getValue().entrySet()) {
                var matches = nodes(load(e.getKey())).stream().filter(n -> field.getKey().equals(n.getCountType())).toList();
                assertEquals(1,matches.size()); assertEquals(field.getValue(),matches.get(0).getValue());
                assertEquals("艦隊",matches.get(0).getType());
            }
        }
    }
    @Test void conflictingA5SourceIsNotSilentlyOverwritten() throws Exception {
        assertEquals(45,nodes(load("1/A5")).stream().filter(n -> n.getLevel()!=null).findFirst().orElseThrow().getLevel());
    }
}
