package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.JsonParser;
import logbook.bean.AppQuestCondition;
import logbook.bean.ShipMst;
import logbook.bean.Stype;
import org.junit.jupiter.api.Test;

/** Two designated sources specify DD3..5; this does not infer an exact total fleet size. */
class MonthlyDestroyerUpperBoundTest {
    private static AppQuestCondition condition() throws Exception {
        try (var stream = MonthlyDestroyerUpperBoundTest.class.getResourceAsStream("/logbook/quest/257.json")) {
            return logbook.internal.json.JsonMappers.builder().enable(tools.jackson.core.json.JsonReadFeature.ALLOW_JAVA_COMMENTS).build().readValue(stream, AppQuestCondition.class);
        }
    }
    private static ShipMst ship(String type) {
        var ship = mock(ShipMst.class);
        var stype = new Stype(); stype.setName(type);
        when(ship.asStype()).thenReturn(Optional.of(stype));
        return ship;
    }
    private static List<ShipMst> fleet(int cruisers, int destroyers) {
        var ships = new ArrayList<ShipMst>();
        for (int n=0; n<cruisers; n++) ships.add(ship("軽巡洋艦"));
        for (int n=0; n<destroyers; n++) ships.add(ship("駆逐艦"));
        return List.copyOf(ships);
    }
    @Test void sixDestroyersExceedExplicitMaximumEvenWithACruiserFlagship() throws Exception {
        assertFalse(condition().getFilter().getFleet().test(fleet(1,6)));
    }
    @Test void fiveDestroyersAndCruiserFlagshipStillPass() throws Exception {
        assertTrue(condition().getFilter().getFleet().test(fleet(1,5)));
    }
    @Test void twoCruiserFourDestroyerBoundaryRemainsValid() throws Exception {
        assertTrue(condition().getFilter().getFleet().test(fleet(2,4)));
    }
    @Test void threeCruiserThreeDestroyerBoundaryRemainsValid() throws Exception {
        assertTrue(condition().getFilter().getFleet().test(fleet(3,3)));
    }
    @Test void existingDestroyerMinimumAndCruiserMaximumAreRetained() throws Exception {
        assertFalse(condition().getFilter().getFleet().test(fleet(1,2)));
        assertFalse(condition().getFilter().getFleet().test(fleet(4,3)));
    }
    @Test void flagshipAndUnrelatedShipTypeRulesAreRetainedWithoutMutatingInput() throws Exception {
        var good = fleet(1,5);
        var swapped = new ArrayList<>(good);
        java.util.Collections.swap(swapped,0,1);
        assertFalse(condition().getFilter().getFleet().test(swapped));
        var other = new ArrayList<>(good); other.set(5,ship("重巡洋艦"));
        assertFalse(condition().getFilter().getFleet().test(other));
        assertTrue(condition().getFilter().getFleet().test(good));
        assertEquals(6,good.size()); assertTrue(good.stream().noneMatch(java.util.Objects::isNull));
    }
    @Test void battlePeriodAreaAndRetreatGuardAreUnchanged() throws Exception {
        var c=condition();
        assertEquals("マンスリー",c.getResetType());
        assertEquals(java.util.Set.of("1-4"),java.util.Set.copyOf(c.getFilter().getArea()));
        assertEquals(Boolean.TRUE,c.getFilter().getNoRetreat());
        assertEquals(1,c.getConditions().size());
        var rule=c.getConditions().getFirst();
        assertEquals(java.util.Set.of("1-4"),java.util.Set.copyOf(rule.getArea()));
        assertEquals(1,rule.getCount());
        assertEquals(java.util.Set.of("S"),java.util.Set.copyOf(rule.getRank()));
        assertTrue(rule.isBoss());
    }
}
