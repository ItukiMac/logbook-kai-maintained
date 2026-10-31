package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.ObjectMapper;
import logbook.bean.MissionCondition;
import logbook.bean.Ship;
import logbook.bean.ShipMst;
import logbook.bean.ShipMstCollection;
import logbook.bean.SlotItemCollection;
import logbook.bean.Stype;
import logbook.bean.StypeCollection;
import logbook.constants.ShipType;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/** Real expedition predicates with fixture-only master/state collections. */
class ReviewedExpeditionBoundaryTest {
    private static final ObjectMapper JSON = logbook.internal.json.JsonMappers.builder()
            .enable(tools.jackson.core.json.JsonReadFeature.ALLOW_JAVA_COMMENTS).build();

    private static MissionCondition a5() throws Exception {
        try (var in = ReviewedExpeditionBoundaryTest.class.getResourceAsStream("/logbook/mission/1/A5.json")) {
            assertNotNull(in);
            return JSON.readValue(in, MissionCondition.class);
        }
    }

    private static MissionCondition escortCarrier() throws Exception {
        return JSON.readValue("{\"type\":\"艦娘\",\"ship_type\":[\"護衛空母\"]}", MissionCondition.class);
    }

    private static Ship ship(int master, int level) {
        var result = new Ship();
        result.setId(master);
        result.setShipId(master);
        result.setLv(level);
        result.setKaryoku(List.of(60, 60));
        result.setTaiku(List.of(50, 50));
        result.setTaisen(List.of(60, 60));
        result.setSakuteki(List.of(40, 40));
        result.setKyouka(List.of(0, 0, 0, 0, 0, 0, 0));
        result.setSlot(List.of());
        result.setSlotEx(-1);
        result.setOnslot(List.of());
        return result;
    }

    private static List<Ship> fleet(int first, int total) {
        return new ArrayList<>(List.of(ship(first, 50), ship(990002, 45),
                ship(990002, 45), ship(990002, 45), ship(990002, total - 185)));
    }

    @Test
    void a5RejectsFleetLevel229AndAccepts230WithAllOtherRequirementsMet() throws Exception {
        try (var data = new Fixture()) {
            // Both disputed flagship minima (45 and 50) are met, isolating the agreed sum.
            var below = fleet(990003, 229);
            assertFalse(a5().test(below));
            var boundary = fleet(990003, 230);
            var before = List.copyOf(boundary);
            assertTrue(a5().test(boundary));
            assertEquals(before, boundary, "The full predicate must retain the caller's fleet");
            assertTrue(a5().test(fleet(990003, 231)));
        }
    }

    @Test
    void a5CannotUseAnAswDestroyerAsTheRequiredEscortCarrier() throws Exception {
        try (var data = new Fixture()) {
            assertFalse(a5().test(fleet(990002, 230)), "Five destroyers match no sourced A5 alternative");
            assertTrue(a5().test(fleet(990007, 230)), "A genuine escort carrier still satisfies its branch");
        }
    }

    @Test
    void escortCarrierPredicateRejectsOtherShipTypesEvenWithNativeAsw() throws Exception {
        try (var data = new Fixture()) {
            for (int type : List.of(1, 2, 3, 4, 5, 10, 11, 16, 18, 21, 22)) {
                data.add(991000 + type, type, List.of(60));
                assertFalse(escortCarrier().test(new ArrayList<>(List.of(ship(991000 + type, 99)))),
                        "ASW alone does not make stype " + type + " an escort carrier");
            }
        }
    }

    @Test
    void escortCarrierPredicateRequiresPresentPositiveNativeAsw() throws Exception {
        try (var data = new Fixture()) {
            for (List<Integer> asw : Arrays.asList(null, List.<Integer> of(), List.of(0), Arrays.asList(null, 0))) {
                data.add(992007, 7, asw);
                assertFalse(escortCarrier().test(new ArrayList<>(List.of(ship(992007, 99)))), String.valueOf(asw));
            }
            data.add(992007, 7, List.of(1));
            assertTrue(escortCarrier().test(new ArrayList<>(List.of(ship(992007, 1)))));
            assertFalse(escortCarrier().test(new ArrayList<>(List.of(ship(999999, 99)))), "Missing master");
        }
    }

    @Test
    void publishedMasterFixtureKeepsRemodelDependentEscortCarrierEligibility() throws Exception {
        try (var data = new Fixture();
                var in = getClass().getResourceAsStream(
                        "/logbook/config/logbook.bean.ShipMstCollection.json")) {
            assertNotNull(in);
            var published = JSON.readValue(in, ShipMstCollection.class).getShipMap();
            // Only these checked remodels, not certification of every ship/remodel in the game.
            for (int id : List.of(185, 318, 555, 89, 285, 521, 522)) {
                data.masters.getShipMap().put(id, published.get(id));
                assertFalse(escortCarrier().test(new ArrayList<>(List.of(ship(id, 99)))), "master " + id);
            }
            for (int id : List.of(883, 888, 560, 894, 899, 526, 529, 544, 396, 707, 925, 930)) {
                data.masters.getShipMap().put(id, published.get(id));
                assertTrue(escortCarrier().test(new ArrayList<>(List.of(ship(id, 99)))), "master " + id);
            }
        }
    }

    @Test
    void explicitAlternativeShipTypeStillWorks() throws Exception {
        try (var data = new Fixture()) {
            var condition = escortCarrier();
            condition.setShipType(Set.of("護衛空母", "軽巡洋艦"));
            assertTrue(condition.test(new ArrayList<>(List.of(ship(990003, 50)))));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ShipMstCollection masters = new ShipMstCollection();
        final MockedStatic<ShipMstCollection> masterAccess = mockStatic(ShipMstCollection.class);
        final MockedStatic<StypeCollection> typeAccess = mockStatic(StypeCollection.class);
        final MockedStatic<SlotItemCollection> itemAccess = mockStatic(SlotItemCollection.class);

        Fixture() {
            var types = new StypeCollection();
            for (ShipType type : ShipType.values()) {
                var value = new Stype();
                value.setId(type.getStype());
                value.setName(type.name());
                types.getStypeMap().put(type.getStype(), value);
            }
            masterAccess.when(ShipMstCollection::get).thenReturn(masters);
            typeAccess.when(StypeCollection::get).thenReturn(types);
            itemAccess.when(SlotItemCollection::get).thenReturn(new SlotItemCollection());
            add(990002, 2, List.of(60));
            add(990003, 3, List.of(60));
            add(990007, 7, List.of(60));
        }

        void add(int id, int type, List<Integer> asw) {
            var master = new ShipMst();
            master.setId(id);
            master.setName("synthetic-" + id);
            master.setStype(type);
            master.setTais(asw);
            masters.getShipMap().put(id, master);
        }

        @Override
        public void close() {
            itemAccess.close();
            typeAccess.close();
            masterAccess.close();
        }
    }
}
