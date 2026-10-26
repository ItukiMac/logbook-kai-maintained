package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import logbook.bean.AppQuestCondition.FleetCondition;
import logbook.bean.ShipMst;
import logbook.bean.Stype;
import org.junit.jupiter.api.Test;

/** Null from an unresolved input is unknown; null consumed inside AND is not a ship. */
class FleetPredicateIsolationTest {
    private static FleetCondition rule(String json) throws Exception {
        return new ObjectMapper().readValue(json, FleetCondition.class);
    }
    private static ShipMst ship(String name, String type) {
        var ship = mock(ShipMst.class); var stype = new Stype(); stype.setName(type);
        when(ship.getName()).thenReturn(name); when(ship.asStype()).thenReturn(Optional.of(stype));
        return ship;
    }
    @Test void consumedFlagshipDoesNotBecomeAnExcludedShip() throws Exception {
        var p = rule("""
            {"operator":"AND","conditions":[
             {"name":["flag"],"order":1},
             {"stype":["駆逐艦"],"difference":true,"count":0,"operator":"EQ"}]}
            """);
        var input = List.of(ship("flag", "軽巡洋艦"), ship("escort", "駆逐艦"));
        assertTrue(p.test(input)); assertTrue(p.test(input)); assertNotNull(input.get(0));
    }
    @Test void consumedSlotCannotSatisfyAnExclusionPresence() throws Exception {
        var p = rule("""
            {"operator":"AND","conditions":[
             {"name":["flag"],"order":1}, {"stype":["駆逐艦"],"difference":true}]}
            """);
        assertFalse(p.test(List.of(ship("flag", "軽巡洋艦"), ship("escort", "駆逐艦"))));
    }
    @Test void consumedPositionCannotSatisfyAnExclusionAtThatPosition() throws Exception {
        var p = rule("""
            {"operator":"AND","conditions":[
             {"name":["flag"],"order":1}, {"stype":["駆逐艦"],"difference":true,"order":1}]}
            """);
        assertFalse(p.test(List.of(ship("flag", "軽巡洋艦"), ship("escort", "駆逐艦"))));
    }
    @Test void anActualExcludedEscortStillFails() throws Exception {
        var p = rule("""
            {"operator":"AND","conditions":[
             {"name":["flag"],"order":1},
             {"stype":["駆逐艦"],"difference":true,"count":0,"operator":"EQ"}]}
            """);
        assertFalse(p.test(List.of(ship("flag", "軽巡洋艦"), ship("escort", "戦艦"))));
    }
    @Test void directPositionalPredicateAcceptsImmutableInputAndNeverConsumesCaller() throws Exception {
        var p = rule("{\"name\":[\"flag\"],\"order\":1}");
        var input = List.of(ship("flag", "軽巡洋艦"));
        assertTrue(p.test(input)); assertTrue(p.test(input)); assertNotNull(input.get(0));
    }
    @Test void directUnorderedPredicateDoesNotMutateMutableInput() throws Exception {
        var p = rule("{\"name\":[\"escort\"]}");
        var input = new ArrayList<>(List.of(ship("flag", "軽巡洋艦"), ship("escort", "駆逐艦")));
        var before = new ArrayList<>(input);
        assertTrue(p.test(input)); assertEquals(before, input); assertTrue(p.test(input));
    }
    @Test void unknownInputSlotIsRejectedEvenWhenRemainingShipsMatch() throws Exception {
        var p = rule("{\"name\":[\"flag\"],\"count\":1,\"operator\":\"GE\"}");
        assertFalse(p.test(Arrays.asList(ship("flag", "軽巡洋艦"), null)));
        assertFalse(p.test(null));
    }
    @Test void nestedLogicalAlternativeKeepsItsOwnConsumptionScope() throws Exception {
        var p = rule("""
            {"operator":"AND","conditions":[
              {"name":["flag"],"order":1},
              {"operator":"OR","conditions":[
                {"stype":["戦艦"],"count":1,"operator":"GE"},
                {"stype":["駆逐艦"],"difference":true,"count":0,"operator":"EQ"}]}]}
            """);
        assertTrue(p.test(List.of(ship("flag", "軽巡洋艦"), ship("escort", "駆逐艦"))));
    }
}
