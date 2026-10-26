package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.stream.Stream;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import logbook.bean.AppQuestCondition;
import logbook.bean.ShipMst;
import logbook.bean.Stype;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Real bundled predicates. Source statements and remaining limits: r7-progress.md. */
class QuestFleetBoundaryRegressionTest {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.ALLOW_COMMENTS);
    private static AppQuestCondition.FleetCondition fleet(int id) throws Exception {
        try (var in = QuestFleetBoundaryRegressionTest.class.getResourceAsStream("/logbook/quest/" + id + ".json")) {
            assertNotNull(in); return JSON.readValue(in, AppQuestCondition.class).getFilter().getFleet();
        }
    }
    private static ShipMst ship(String name, String type) {
        var s = mock(ShipMst.class); var t = new Stype(); t.setName(type);
        when(s.getName()).thenReturn(name); when(s.asStype()).thenReturn(Optional.of(t)); return s;
    }
    private static ShipMst de() { return ship("占守改", "海防艦"); }
    private static ShipMst dd(String name) { return ship(name, "駆逐艦"); }
    private static ShipMst ukuru(String name) { return ship(name, "海防艦"); }

    @TestFactory Stream<DynamicTest> escortCountsAlreadyExcludeFlagshipWithoutMutatingCaller() {
        var cases = new ArrayList<DynamicTest>();
        for (int id : new int[] {944, 945}) {
            List<String> allowed = id == 944 ? List.of("駆逐艦", "重巡洋艦") : List.of("駆逐艦", "軽巡洋艦", "練習巡洋艦");
            for (String type : allowed) cases.add(DynamicTest.dynamicTest(id + " flag=" + type, () -> {
                var p = fleet(id); var ships = new ArrayList<>(List.of(ship("flag", type), dd("潮"), de()));
                var saved = new ArrayList<>(ships);
                assertFalse(p.test(ships), "only two escorts"); assertEquals(saved, ships);
                ships.add(de()); saved = new ArrayList<>(ships);
                assertTrue(p.test(ships)); assertTrue(p.test(ships), "repeatability"); assertEquals(saved, ships);
                ships.set(0, ship("wrong flag", "航空巡洋艦")); assertFalse(p.test(ships));
            }));
        }
        return cases.stream();
    }
    @TestFactory Stream<DynamicTest> ukuruTwoToFourShipsAndCoastalDefenseBattleships() {
        var cases = new ArrayList<DynamicTest>();
        for (String flag : List.of("鵜来", "鵜来改", "稲木", "稲木改二"))
            for (int size = 1; size <= 6; size++) {
                int n = size;
                cases.add(DynamicTest.dynamicTest("1012 " + flag + " size=" + n, () -> {
                    var ships = new ArrayList<ShipMst>(); ships.add(ukuru(flag));
                    for (int i = 1; i < n; i++) ships.add(i % 2 == 0 ? ship("Vainamoinen", "海防戦艦") : de());
                    var before = new ArrayList<>(ships);
                    assertEquals(n >= 2 && n <= 4, fleet(1012).test(ships)); assertEquals(before, ships);
                }));
            }
        return cases.stream();
    }
    @TestFactory Stream<DynamicTest> ukuruRejectsOtherTypesAndMisplacedFlagship() {
        return List.of("駆逐艦", "軽空母", "軽巡洋艦", "戦艦", "水上機母艦").stream().map(type ->
            DynamicTest.dynamicTest("1012 excludes " + type, () -> {
                var p = fleet(1012);
                assertFalse(p.test(List.of(ukuru("鵜来改"), de(), ship("outside", type))));
                assertFalse(p.test(List.of(de(), ukuru("鵜来改"), de())));
                assertFalse(p.test(List.of(ukuru("other DE"), de())));
                assertFalse(p.test(Arrays.asList(ukuru("鵜来"), de(), null)));
            }));
    }
    private static List<ShipMst> allied() {
        return new ArrayList<>(List.of(ship("Iowa改", "戦艦"), ship("Nelson改", "戦艦"), dd("Fletcher Mk.II")));
    }
    @TestFactory Stream<DynamicTest> alliedFleetRejectsEveryCarrierCategory() {
        return List.of("軽空母", "正規空母", "装甲空母").stream().map(type ->
            DynamicTest.dynamicTest("973 excludes " + type, () -> {
                var p = fleet(973); var ships = allied(); assertTrue(p.test(ships));
                ships.add(ship("carrier", type)); assertFalse(p.test(ships));
                ships.set(3, ship("瑞穂改", "水上機母艦")); assertTrue(p.test(ships));
            }));
    }
    @TestFactory Stream<DynamicTest> explicitlySourcedAlliedNamesCountWithRemodelSuffix() {
        return List.of("Minneapolis", "Phoenix", "General Belgrano", "Richard P.Leary", "Valiant").stream().map(name ->
            DynamicTest.dynamicTest("973 source roster " + name, () -> {
                var p = fleet(973); var ships = allied(); ships.set(2, ship(name + "改", "軽巡洋艦"));
                assertTrue(p.test(ships)); ships.set(2, ship("not sourced", "軽巡洋艦")); assertFalse(p.test(ships));
            }));
    }
    @Test void alliedZeroOneTwoAndThreeThreshold() throws Exception {
        var p = fleet(973); var ships = allied();
        for (int n = 0; n < 3; n++) assertFalse(p.test(ships.subList(0, n)));
        assertTrue(p.test(ships));
    }
    @Test void bothNamedBattlecruisersRequiredNotTwoCopiesOfEither() throws Exception {
        var p = fleet(1019); var h = ship("比叡改二丙", "戦艦"); var k = ship("霧島改二丙", "戦艦");
        assertFalse(p.test(List.of(h, h, dd("夕雲改二"), dd("巻雲改二"))));
        assertFalse(p.test(List.of(k, k, dd("夕雲改二"), dd("巻雲改二"))));
        assertTrue(p.test(List.of(h, k, dd("夕雲改二"), dd("巻雲改二"))));
        assertFalse(p.test(List.of(h, ship("霧島改二", "戦艦"), dd("夕雲"), dd("巻雲"))));
    }
    @Test void sameDestroyerClassRequiredButBattlecruiserFlagshipIsNotRequired() throws Exception {
        var p = fleet(1019); var h = ship("比叡改二丙", "戦艦"); var k = ship("霧島改二丙", "戦艦");
        assertTrue(p.test(List.of(dd("白露改二"), dd("時雨改三"), h, k)));
        assertTrue(p.test(List.of(dd("夕雲改二"), dd("巻雲改二"), h, k)));
        assertFalse(p.test(List.of(dd("夕雲改二"), dd("時雨改三"), h, k)));
        assertFalse(p.test(List.of(dd("夕雲改二"), h, k)));
    }
    @Test void revisedConditionsSurviveJsonRoundTrip() throws Exception {
        for (int id : new int[] {973,1012,1019}) {
            var p = fleet(id);
            var copy = JSON.readValue(JSON.writeValueAsBytes(p), AppQuestCondition.FleetCondition.class);
            List<ShipMst> valid = switch (id) {
                case 973 -> allied(); case 1012 -> List.of(ukuru("鵜来"), de());
                default -> List.of(ship("比叡改二丙", "戦艦"),ship("霧島改二丙", "戦艦"),dd("夕雲"),dd("巻雲"));
            };
            assertTrue(p.test(valid)); assertTrue(copy.test(valid));
        }
    }
}
