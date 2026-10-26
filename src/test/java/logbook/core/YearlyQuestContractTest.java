package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import logbook.bean.AppQuestCondition;
import logbook.bean.ShipMst;
import logbook.bean.Stype;
import logbook.internal.kancolle.QuestCollect;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Source-transcribed yearly map/count/month expectations; not full live-game certification. */
class YearlyQuestContractTest {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.ALLOW_COMMENTS);
    private static byte[] resource(String name) throws Exception {
        try (InputStream in = YearlyQuestContractTest.class.getResourceAsStream('/' + name)) {
            assertNotNull(in, name); return in.readAllBytes();
        }
    }
    private static AppQuestCondition quest(int id) throws Exception {
        return JSON.readValue(resource("logbook/quest/" + id + ".json"), AppQuestCondition.class);
    }
    private static void rank(QuestCollect.Rank value, char rank, int n) {
        switch (rank) {
            case 'S' -> value.setS(n); case 'A' -> value.setA(n); case 'B' -> value.setB(n);
            case 'C' -> value.setC(n); case 'D' -> value.setD(n); case 'E' -> value.setE(n);
            default -> throw new IllegalArgumentException();
        }
    }
    private static QuestCollect battle(String area, String cell, char result, int n, boolean boss) {
        var collect = new QuestCollect(); var count = new QuestCollect.Count();
        rank(count.getAll(), result, n); if (boss) rank(count.getBoss(), result, n);
        if (cell != null) { var v = new QuestCollect.Rank(); rank(v, result, n); count.getCell().put(area + '-' + cell, v); }
        collect.getArea().put(area, count); return collect;
    }
    @TestFactory Stream<DynamicTest> sixteenManuallyComparedYearlyQuests() throws Exception {
        var facts = JSON.readTree(resource("maintenance/r6-yearly-contracts.json"));
        assertEquals(16, facts.path("definitions").size());
        var cases = new ArrayList<DynamicTest>();
        for (var record : facts.path("definitions")) {
            int id = record.path("id").asInt();
            cases.add(DynamicTest.dynamicTest("yearly source comparison " + id, () -> {
                byte[] raw = resource("logbook/quest/" + id + ".json");
                assertEquals(record.path("definition_sha256").asText(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
                var actual = JSON.readValue(raw, AppQuestCondition.class);
                assertEquals("イヤリー", actual.getResetType()); assertEquals(record.path("month").asInt(), actual.getYearlyResetMonth());
                assertEquals(record.path("rules").size(), actual.getConditions().size());
                var complete = new QuestCollect();
                for (var rule : record.path("rules")) {
                    String area = rule.path("area").asText();
                    var matching = actual.getConditions().stream().filter(c -> c.getArea() != null && c.getArea().equals(Set.of(area))).toList();
                    assertEquals(1, matching.size(), area); var condition = matching.get(0);
                    int target = rule.path("count").asInt(); assertEquals(target, condition.getCount());
                    assertFalse(condition.isStart()); assertNull(condition.getStype()); assertNull(condition.getMissions());
                    boolean goal = rule.path("kind").asText().equals("goal");
                    String cell = rule.path("cell").isTextual() ? rule.path("cell").asText() : null;
                    assertEquals(!goal, condition.isBoss());
                    if (goal) {
                        assertEquals("arrival-v1", rule.path("encoding").asText());
                        assertTrue(condition.isArrival()); assertNull(condition.getCells()); assertNull(condition.getCell());
                        assertNull(condition.getRank()); assertTrue(rule.has("unresolved"));
                        for (char rank : "SABCDE".toCharArray())
                            assertFalse(condition.test(battle(area, "B", rank, target, false)), "last battle is not a goal");
                        var arrival = new QuestCollect(); var arrived = new QuestCollect.Count();
                        arrival.getArea().put(area, arrived); arrived.setArrivals(target - 1); assertFalse(condition.test(arrival));
                        arrived.setArrivals(target); assertTrue(condition.test(arrival)); complete.getArea().putAll(arrival.getArea());
                        continue;
                    } else { assertEquals(cell, condition.getCell()); assertNull(condition.getCells()); }
                    String allowed = goal ? "SABCDE" : rule.path("ranks").asText();
                    for (char result : "SABCDE".toCharArray()) {
                        assertFalse(condition.test(battle(area, cell, result, target - 1, !goal)));
                        assertEquals(allowed.indexOf(result) >= 0, condition.test(battle(area, cell, result, target, !goal)), area + result);
                    }
                    assertFalse(condition.test(battle("99-99", cell, 'S', target, !goal)));
                    if (cell != null) assertFalse(condition.test(battle(area, "wrong", 'S', target, !goal)));
                    else assertFalse(condition.test(battle(area, null, 'S', target, false)));
                    var passing = battle(area, cell, 'S', target, !goal); complete.getArea().putAll(passing.getArea());
                }
                assertTrue(actual.test(complete));
                for (String area : new ArrayList<>(complete.getArea().keySet())) {
                    var saved = complete.getArea().remove(area); assertFalse(actual.test(complete), "all independent maps required: " + area); complete.getArea().put(area, saved);
                }
                assertFalse(record.path("whole_rule_verified").asBoolean());
            }));
        }
        return cases.stream();
    }
    private static ShipMst ship(String type) {
        var s = mock(ShipMst.class); var t = new Stype(); t.setName(type);
        when(s.getName()).thenReturn(type); when(s.asStype()).thenReturn(Optional.of(t)); return s;
    }
    @Test void carrierBattleRequiresFirstFleetAndActualCarrierFlagship() throws Exception {
        var c = quest(948); assertEquals(Set.of(1), c.getFilter().getDeckIds());
        for (String type : List.of("軽空母", "正規空母", "装甲空母"))
            assertTrue(c.getFilter().getFleet().test(new ArrayList<>(List.of(ship(type)))));
        assertFalse(c.getFilter().getFleet().test(new ArrayList<>(List.of(ship("戦艦"), ship("正規空母")))));
    }
    @Test void alAlreadyContains35AndNeedsTwoLightCarriers() throws Exception {
        var c = quest(947);
        assertTrue(c.getConditions().stream().anyMatch(v -> v.getArea().equals(Set.of("3-5"))));
        assertTrue(c.getFilter().getFleet().test(List.of(ship("軽空母"), ship("軽空母"))));
        assertFalse(c.getFilter().getFleet().test(List.of(ship("軽空母"), ship("正規空母"))));
    }
    @Test void heavyCruiserWestDoesNotSubstituteAviationCruisers() throws Exception {
        var c = quest(914).getFilter().getFleet(); var ca = ship("重巡洋艦"); var dd = ship("駆逐艦");
        assertTrue(c.test(List.of(ca, ca, ca, dd)));
        assertFalse(c.test(List.of(ca, ca, ship("航空巡洋艦"), dd)));
        assertFalse(c.test(List.of(ca, ca, ca)));
    }
}
