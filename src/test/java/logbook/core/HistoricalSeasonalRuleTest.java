package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.ObjectMapper;
import logbook.bean.AppQuestCondition;
import logbook.bean.ShipMst;
import logbook.bean.Stype;
import logbook.internal.kancolle.QuestCollect;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Published historical requirements, exercised through the actual fleet/count predicates. */
class HistoricalSeasonalRuleTest {
    private static final ObjectMapper JSON = logbook.internal.json.JsonMappers.builder().enable(tools.jackson.core.json.JsonReadFeature.ALLOW_JAVA_COMMENTS).build();

    private static AppQuestCondition rule(int id) throws Exception {
        String edition = switch (id) {
            case 840, 841, 843 -> "2025-setsubun-" + id;
            case 906 -> "2021-peach-906";
            case 954 -> "2025-rainy-954";
            default -> throw new IllegalArgumentException("Unreviewed historical edition: " + id);
        };
        String path = "/logbook/seasonal/editions/" + edition + ".json";
        try (var in = HistoricalSeasonalRuleTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return JSON.readValue(in, AppQuestCondition.class);
        }
    }

    private static ShipMst ship(String name, String type) {
        ShipMst ship = mock(ShipMst.class);
        Stype stype = new Stype(); stype.setName(type);
        when(ship.getName()).thenReturn(name);
        when(ship.asStype()).thenReturn(Optional.of(stype));
        return ship;
    }

    private static QuestCollect battle(String area, String cell, int s, int a, boolean boss) {
        var result = new QuestCollect();
        var count = new QuestCollect.Count();
        count.getAll().setS(s); count.getAll().setA(a);
        if (boss) { count.getBoss().setS(s); count.getBoss().setA(a); }
        if (cell != null) {
            var ranks = new QuestCollect.Rank(); ranks.setS(s); ranks.setA(a);
            count.getCell().put(area + '-' + cell, ranks);
        }
        result.getArea().put(area, count);
        return result;
    }

    @TestFactory Stream<DynamicTest> bothNamedPositionsAreRequiredInThe2025SetsubunEditions() {
        return Stream.of(840, 841).map(id -> DynamicTest.dynamicTest("named first and second: " + id, () -> {
            var filter = rule(id).getFilter().getFleet();
            var first = ship(id == 840 ? "鳳翔改二" : "Gloire改", id == 840 ? "軽空母" : "軽巡洋艦");
            var second = ship(id == 840 ? "潮改二" : "Ranger改", id == 840 ? "駆逐艦" : "正規空母");
            var unrelated = ship("吹雪改二", "駆逐艦");
            assertTrue(filter.test(List.of(first, second, unrelated)));
            assertTrue(filter.test(List.of(second, first, unrelated)));
            assertFalse(filter.test(List.of(unrelated, first, second)), "Two eligible ships in later slots are insufficient");
            assertFalse(filter.test(List.of(first, unrelated, second)), "The second slot is independently required");
            assertFalse(filter.test(List.of(first)), "A missing second slot is not a match");
        }));
    }

    @TestFactory Stream<DynamicTest> the2026RosterCannotChangeThe2025NamedEditions() {
        return Stream.of(840, 841).map(id -> DynamicTest.dynamicTest("historical roster: " + id, () -> {
            var first = ship(id == 840 ? "大淀改" : "Jean Bart改", id == 840 ? "軽巡洋艦" : "戦艦");
            var second = ship(id == 840 ? "潮改二" : "Ranger改", id == 840 ? "駆逐艦" : "正規空母");
            assertFalse(rule(id).getFilter().getFleet().test(List.of(first, second)));
        }));
    }

    @Test void allFiveHistoricalEditionsDeclareSortieAndWeeklyPeriod() throws Exception {
        for (int id : List.of(840, 841, 843, 906, 954)) {
            assertEquals(AppQuestCondition.Type.出撃, rule(id).getType(), "Explicit sortie type: " + id);
            assertEquals("ウィークリー", rule(id).getResetType(), "Historical period: " + id);
        }
    }

    @Test void setsubun843UsesTheFinalTCellNotTheEarlierBosses() throws Exception {
        var condition = rule(843).getConditions().stream().filter(c -> c.getArea().contains("7-5")).findFirst().orElseThrow();
        assertFalse(condition.test(battle("7-5", "M", 1, 0, true)));
        assertFalse(condition.test(battle("7-5", "Q", 1, 0, true)));
        assertFalse(condition.test(battle("7-5", "T", 0, 1, true)));
        assertTrue(condition.test(battle("7-5", "T", 1, 0, true)));
        assertFalse(condition.test(battle("7-4", "T", 9, 0, true)));
    }

    @Test void setsubun843AllowsOnlyConfirmed2025LightCarrierSubstitutes() throws Exception {
        var filter = rule(843).getFilter().getFleet();
        var first = ship("妙高改二", "重巡洋艦");
        var second = ship("羽黒改二", "重巡洋艦");
        for (var substitute : List.of(ship("龍鳳改二", "軽空母"), ship("あきつ丸改", "揚陸艦"),
                ship("山汐丸改", "補給艦"), ship("熊野丸改", "揚陸艦"))) {
            assertTrue(filter.test(List.of(first, second, substitute)), substitute.getName());
        }
        assertFalse(filter.test(List.of(first, second, ship("速吸改", "補給艦"))));
        assertFalse(filter.test(List.of(first, second, ship("しまね丸改", "補給艦"))), "Later-year addition is not a 2025 source fact");
    }

    @Test void setsubun843NeedsTwoHeavyCruisersAndDoesNotImposeThe2026FlagshipRule() throws Exception {
        var filter = rule(843).getFilter().getFleet();
        var cvl = ship("龍鳳改二", "軽空母"); var ca = ship("妙高改二", "重巡洋艦");
        var ca2 = ship("羽黒改二", "重巡洋艦"); var cav = ship("利根改二", "航空巡洋艦");
        assertTrue(filter.test(List.of(cvl, ca, ca2)));
        assertFalse(filter.test(List.of(cvl, ca, cav)));
        assertFalse(filter.test(List.of(cvl, ca)));
        assertFalse(filter.test(List.of(ca, ca2)));
    }

    @Test void setsubun843RequiresAllFourIndependentMaps() throws Exception {
        var rule = rule(843); var collected = new QuestCollect();
        for (String area : List.of("2-3", "4-1", "5-1", "7-5"))
            collected.getArea().putAll(battle(area, area.equals("7-5") ? "T" : null, 1, 0, true).getArea());
        assertTrue(rule.test(collected));
        for (String area : List.of("2-3", "4-1", "5-1", "7-5")) {
            var saved = collected.getArea().remove(area);
            assertFalse(rule.test(collected), area); collected.getArea().put(area, saved);
        }
    }

    @TestFactory Stream<DynamicTest> portArrivalCannotBeSatisfiedByTheLastBattle() {
        return Stream.of(906, 954).map(id -> DynamicTest.dynamicTest("actual arrival: " + id, () -> {
            var condition = rule(id).getConditions().stream().filter(c -> c.getArea().contains("1-6")).findFirst().orElseThrow();
            int target = id == 906 ? 1 : 2;
            var collected = battle("1-6", "B", 99, 99, true);
            assertFalse(condition.test(collected), "B/D battle evidence does not prove port arrival");
            collected.getArea().get("1-6").setArrivals(target - 1); assertFalse(condition.test(collected));
            collected.getArea().get("1-6").setArrivals(target); assertTrue(condition.test(collected));
            collected.setArrivalEvidenceUnavailable(true); assertFalse(condition.test(collected));
        }));
    }

    @Test void peach2021NeedsThreeDestroyersOrEscortShipsAndNoFlagshipRestriction() throws Exception {
        var filter = rule(906).getFilter().getFleet();
        var destroyer = ship("曙改二", "駆逐艦"); var escort = ship("択捉改", "海防艦");
        var cruiser = ship("能代改二", "軽巡洋艦");
        assertTrue(filter.test(List.of(cruiser, destroyer, escort, destroyer)));
        assertFalse(filter.test(List.of(cruiser, destroyer, escort)));
    }

    @Test void rainy2025RequiresDestroyerFlagshipAndTwoEscortShips() throws Exception {
        var filter = rule(954).getFilter().getFleet();
        var d = ship("吹雪改二", "駆逐艦"); var e = ship("択捉改", "海防艦");
        var e2 = ship("松輪改", "海防艦"); var cvl = ship("龍鳳改二", "軽空母");
        assertTrue(filter.test(List.of(d, e, e2, cvl)));
        assertFalse(filter.test(List.of(cvl, d, e, e2)));
        assertFalse(filter.test(List.of(d, e, cvl)));
        assertFalse(filter.test(List.of(cvl, d, d)), "The 2026 fleet does not satisfy the 2025 edition");
    }

    @Test void rainy2025RequiresSForFourHistoricalMapsAndTwoArrivalObservations() throws Exception {
        var rule = rule(954); var collected = new QuestCollect();
        assertEquals(Set.of("1-2", "1-3", "1-4", "1-5", "1-6"), rule.getFilter().getArea());
        for (String area : List.of("1-2", "1-3", "1-4", "1-5")) {
            var condition = rule.getConditions().stream().filter(c -> c.getArea().contains(area)).findFirst().orElseThrow();
            assertFalse(condition.test(battle(area, null, 0, 1, true)), area + " requires S");
            assertTrue(condition.test(battle(area, null, 1, 0, true)));
            collected.getArea().putAll(battle(area, null, 1, 0, true).getArea());
        }
        var arrivals = new QuestCollect.Count(); arrivals.setArrivals(1); collected.getArea().put("1-6", arrivals);
        assertFalse(rule.test(collected)); arrivals.setArrivals(2); assertTrue(rule.test(collected));
    }
}
