package logbook.bean;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import logbook.bean.AppQuestCondition.FleetCondition;
import org.junit.jupiter.api.Test;

/** Proven retreat vacancies retain their original positions and never stand for unknown ships. */
class ObservedFleetSlotsTest {
    private static FleetCondition rule(String json) throws Exception {
        return logbook.internal.json.JsonMappers.builder().build().readValue(json, FleetCondition.class);
    }

    private static ShipMst ship(String name) {
        ShipMst ship = new ShipMst();
        ship.setName(name);
        return ship;
    }

    private static boolean observed(FleetCondition condition, List<ShipMst> slots,
            Set<Integer> absentSlots) throws Exception {
        // Reflection lets the regression compile against the pre-implementation baseline.
        Method method;
        try {
            method = FleetCondition.class.getMethod("testObservedSlots", List.class, Set.class);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("Proven retreat slots need a separate position-preserving predicate", missing);
        }
        return (Boolean) method.invoke(condition, slots, absentSlots);
    }

    @Test
    void provenMiddleVacancyDoesNotPromoteThirdShipIntoSecondPosition() throws Exception {
        var slots = Arrays.asList(ship("flag"), null, ship("required"));
        assertFalse(observed(rule("{\"name\":[\"required\"],\"order\":2}"), slots, Set.of(2)));
        assertTrue(observed(rule("{\"name\":[\"required\"],\"order\":3}"), slots, Set.of(2)));
    }

    @Test
    void provenFlagshipVacancyDoesNotPromoteAnEscort() throws Exception {
        var slots = Arrays.asList(null, ship("required"));
        assertFalse(observed(rule("{\"name\":[\"required\"],\"order\":1}"), slots, Set.of(1)));
        assertTrue(observed(rule("{\"name\":[\"required\"],\"order\":2}"), slots, Set.of(1)));
    }

    @Test
    void survivorCountsExcludeVacantPositions() throws Exception {
        var slots = Arrays.asList(ship("escort"), null, ship("escort"));
        assertTrue(observed(rule("{\"name\":[\"escort\"],\"count\":2,\"operator\":\"EQ\"}"), slots, Set.of(2)));
        assertFalse(observed(rule("{\"name\":[\"escort\"],\"count\":3,\"operator\":\"GE\"}"), slots, Set.of(2)));
    }

    @Test
    void aProvenVacancyIsNotAnExcludedShip() throws Exception {
        var slots = Arrays.asList(ship("escort"), null);
        assertFalse(observed(rule("{\"name\":[\"escort\"],\"difference\":true}"), slots, Set.of(2)));
        assertTrue(observed(rule("{\"name\":[\"escort\"],\"difference\":true,\"count\":0,\"operator\":\"EQ\"}"), slots,
                Set.of(2)));
    }

    @Test
    void unknownSlotCannotBeHiddenByAProvenVacancyElsewhere() throws Exception {
        var condition = rule("{\"name\":[\"flag\"],\"order\":1}");
        assertFalse(observed(condition, Arrays.asList(ship("flag"), null, null), Set.of(2)));
        assertFalse(observed(condition, Arrays.asList(ship("flag"), null), Set.of()));
    }

    @Test
    void occupiedSlotCannotBeDeclaredAbsent() throws Exception {
        var condition = rule("{\"name\":[\"flag\"],\"order\":1}");
        assertFalse(observed(condition, List.of(ship("flag"), ship("escort")), Set.of(2)));
    }

    @Test
    void absencePositionsMustBeOneBasedAndWithinTheRecordedFleet() throws Exception {
        var condition = rule("{\"name\":[\"flag\"],\"order\":1}");
        var slots = Arrays.asList(ship("flag"), null);
        for (Set<Integer> positions : List.of(Set.of(0, 2), Set.of(-1, 2), Set.of(2, 3), Set.of(2, 8))) {
            assertFalse(observed(condition, slots, positions));
        }
        var nullPosition = new HashSet<>(Set.of(2));
        nullPosition.add(null);
        assertFalse(observed(condition, slots, nullPosition));
    }

    @Test
    void noSurvivorCannotSatisfyZeroCountOrNegativeConditions() throws Exception {
        var zero = rule("{\"name\":[\"required\"],\"count\":0,\"operator\":\"EQ\"}");
        assertFalse(observed(zero, Arrays.asList(null, null), Set.of(1, 2)));
        assertFalse(observed(zero, List.of(), Set.of()));
        var negative = rule("{\"operator\":\"NOR\",\"conditions\":[{\"name\":[\"required\"]}]}");
        assertFalse(observed(negative, Arrays.asList(null, null), Set.of(1, 2)));
    }

    @Test
    void absentFleetOrAbsentEvidenceFailsClosed() throws Exception {
        var condition = rule("{\"name\":[\"flag\"],\"order\":1}");
        assertFalse(observed(condition, null, Set.of()));
        assertFalse(observed(condition, List.of(ship("flag")), null));
    }

    @Test
    void observedFleetIsBoundedToSevenPositions() throws Exception {
        var condition = rule("{\"name\":[\"escort\"],\"count\":7,\"operator\":\"GE\"}");
        assertTrue(observed(condition, Collections.nCopies(7, ship("escort")), Set.of()));
        assertFalse(observed(condition, Collections.nCopies(8, ship("escort")), Set.of()));
    }

    @Test
    void nestedPredicatesNeverMutateTheObservedSlotsOrVacancyEvidence() throws Exception {
        var condition = rule("""
                {"operator":"AND","conditions":[
                  {"name":["flag"],"order":1},
                  {"operator":"OR","conditions":[
                    {"name":["escort"],"order":2},
                    {"name":["escort"],"order":3}]}]}
                """);
        var slots = new ArrayList<>(Arrays.asList(ship("flag"), null, ship("escort")));
        var original = new ArrayList<>(slots);
        var absent = new HashSet<>(Set.of(2));
        assertTrue(observed(condition, Collections.unmodifiableList(slots), Collections.unmodifiableSet(absent)));
        assertTrue(observed(condition, slots, absent));
        assertEquals(original, slots);
        assertEquals(Set.of(2), absent);
    }

    @Test
    void ordinaryPredicateStillRejectsNullAndAcceptsCompleteEvidence() throws Exception {
        var condition = rule("{\"name\":[\"flag\"],\"order\":1}");
        assertFalse(condition.test(Arrays.asList(ship("flag"), null)));
        assertTrue(condition.test(List.of(ship("flag"), ship("escort"))));
    }
}
