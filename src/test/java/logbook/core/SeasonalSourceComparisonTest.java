package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
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

/** Distinguishes historical source comparison from current-season applicability. */
class SeasonalSourceComparisonTest {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.ALLOW_COMMENTS);
    private static byte[] bytes(String path) throws Exception {
        try (var in = SeasonalSourceComparisonTest.class.getResourceAsStream('/' + path)) {
            assertNotNull(in, path); return in.readAllBytes();
        }
    }
    private static JsonNode facts() throws Exception { return JSON.readTree(bytes("maintenance/r7-final-cohort-contracts.json")); }
    private static AppQuestCondition quest(String group, int id) throws Exception {
        return JSON.readValue(bytes("logbook/" + group + '/' + id + ".json"), AppQuestCondition.class);
    }
    private static void rank(QuestCollect.Rank r, char c, int n) {
        switch(c) {
            case 'S' -> r.setS(n); case 'A' -> r.setA(n); case 'B' -> r.setB(n);
            case 'C' -> r.setC(n); case 'D' -> r.setD(n); case 'E' -> r.setE(n);
            default -> throw new IllegalArgumentException();
        }
    }
    private static QuestCollect battle(String area, String cell, char result, int n, boolean boss) {
        var q = new QuestCollect(); var count = new QuestCollect.Count(); rank(count.getAll(), result, n);
        if (boss) rank(count.getBoss(), result, n);
        if (cell != null) { var r = new QuestCollect.Rank(); rank(r, result, n); count.getCell().put(area + '-' + cell, r); }
        q.getArea().put(area, count); return q;
    }
    @TestFactory Stream<DynamicTest> fifteenRemainingSourceComparisonsWithExplicitEditionAndGaps() throws Exception {
        var records = facts().path("definitions"); assertEquals(15, records.size());
        var tests = new ArrayList<DynamicTest>();
        for (var row : records) tests.add(DynamicTest.dynamicTest(row.path("group").asText() + '/' + row.path("id") + " " + row.path("source_edition").asText(), () -> {
            String group = row.path("group").asText(); int id = row.path("id").asInt();
            byte[] raw = bytes("logbook/" + group + '/' + id + ".json");
            assertEquals(row.path("definition_sha256").asText(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
            var actual = JSON.readValue(raw, AppQuestCondition.class);
            assertEquals(row.path("encoded_period").asText(), actual.getResetType());
            // 843 is a documented disagreement, not a false certification of its MONTHLY reset.
            if (row.path("source_period").isTextual() && id != 843)
                assertEquals(row.path("source_period").asText(), actual.getResetType());
            assertEquals(row.path("rules").size(), actual.getConditions().size());
            assertFalse(row.path("whole_rule_verified").asBoolean()); assertFalse(row.path("unresolved").isEmpty());
            assertFalse(actual.test(new QuestCollect()));
            var complete = new QuestCollect();
            for (int i = 0; i < row.path("rules").size(); i++) {
                var rule = row.path("rules").get(i); var condition = actual.getConditions().get(i);
                int target = rule.path("count").asInt(); assertEquals(target, condition.getCount());
                if ("mission".equals(rule.path("kind").asText())) {
                    assertEquals(AppQuestCondition.Type.遠征, actual.getType());
                    String name = rule.path("missions").get(0).asText(); assertEquals(List.of(name), condition.getMissions());
                    var state = new QuestCollect(); state.getMissions().put(name, (long) target - 1); assertFalse(condition.test(state));
                    state.getMissions().put(name, (long) target); assertTrue(condition.test(state));
                    state.getMissions().clear(); state.getMissions().put("unrelated", 99L); assertFalse(condition.test(state));
                    complete.getMissions().put(name, (long) target); continue;
                }
                String area = rule.path("area").asText(); boolean goal = "goal".equals(rule.path("kind").asText());
                String cell = rule.path("encoded_cell").isTextual() ? rule.path("encoded_cell").asText() : null;
                assertEquals(Set.of(area), condition.getArea()); assertEquals(!goal, condition.isBoss());
                assertFalse(condition.isStart()); assertNull(condition.getStype()); assertNull(condition.getMissions());
                if (goal) {
                    assertNull(condition.getCell()); assertEquals(Set.of("B","D"), condition.getCells());
                    assertTrue(rule.has("unresolved")); cell = "B"; // Tests the proxy ONLY.
                } else { assertEquals(cell, condition.getCell()); assertNull(condition.getCells()); }
                String allowed = goal ? "SABCDE" : rule.path("source_ranks").asText();
                var ranks = new HashSet<String>(); for (char c : allowed.toCharArray()) ranks.add(String.valueOf(c));
                assertEquals(ranks, condition.getRank());
                for (char c : "SABCDE".toCharArray()) {
                    assertFalse(condition.test(battle(area, cell, c, target - 1, !goal)));
                    assertEquals(allowed.indexOf(c) >= 0, condition.test(battle(area, cell, c, target, !goal)), area + c);
                }
                assertFalse(condition.test(battle("99-99", cell, 'S', target, !goal)));
                if (cell != null) assertFalse(condition.test(battle(area, "wrong", 'S', target, !goal)));
                else assertFalse(condition.test(battle(area, null, 'S', target, false)));
                complete.getArea().putAll(battle(area, cell, 'S', target, !goal).getArea());
            }
            assertTrue(actual.test(complete));
            // Every independent area/expedition remains necessary, never an OR aggregate.
            for (String area : new ArrayList<>(complete.getArea().keySet())) {
                var saved = complete.getArea().remove(area); assertFalse(actual.test(complete)); complete.getArea().put(area, saved);
            }
            for (String name : new ArrayList<>(complete.getMissions().keySet())) {
                var saved = complete.getMissions().remove(name); assertFalse(actual.test(complete)); complete.getMissions().put(name, saved);
            }
        }));
        return tests.stream();
    }
    private static ShipMst ship(String name, String type) {
        var s = mock(ShipMst.class); var stype = new Stype(); stype.setName(type);
        when(s.getName()).thenReturn(name); when(s.asStype()).thenReturn(Optional.of(stype)); return s;
    }
    @Test void thirtySecondDestroyerSquadronNeedsThreeDistinctEligibleSlots() throws Exception {
        var p = quest("quest",1022).getFilter().getFleet();
        var a = ship("玉波改", "駆逐艦"); var b = ship("涼波改二補", "駆逐艦"); var c = ship("藤波改", "駆逐艦");
        assertTrue(p.test(List.of(a,b,c))); assertFalse(p.test(List.of(a,b)));
        assertFalse(p.test(List.of(a,b,ship("沖波改二","駆逐艦"))));
    }
    @Test void pumpkin2025FlagshipAndCountRulesAreNotReplacedWith2026Roster() throws Exception {
        var p = quest("quest",987).getFilter().getFleet();
        var a = ship("Roma改","戦艦"); var b = ship("村雨改二","駆逐艦"); var c = ship("Minneapolis改","重巡洋艦"); var x = ship("unknown","駆逐艦");
        assertTrue(p.test(List.of(a,b,c))); assertFalse(p.test(List.of(a,b)));
        assertFalse(p.test(List.of(x,a,b,c)));
        var wolf = quest("quest",1040).getFilter().getFleet();
        assertTrue(wolf.test(List.of(ship("夕立改二","駆逐艦"),ship("迅鯨改","潜水母艦"),ship("朧改","駆逐艦"))));
        assertFalse(wolf.test(List.of(x,ship("夕立","駆逐艦"),ship("朧","駆逐艦"),ship("野分","駆逐艦"))));
        var escort = quest("quest",1039).getFilter().getFleet();
        assertTrue(escort.test(List.of(ship("阿武隈改二","軽巡洋艦"),ship("曙改二","駆逐艦"),ship("朝潮改二","駆逐艦"),ship("鵜来改","海防艦"))));
        assertFalse(escort.test(List.of(ship("阿武隈","軽巡洋艦"),ship("曙","駆逐艦"),ship("朝潮","駆逐艦"))));
    }
    @Test void rainy2026RequiresSameClassFlagAndSecondPlusTwoDestroyers() throws Exception {
        var p = quest("eventquest",953).getFilter().getFleet(); var d = ship("駆逐","駆逐艦");
        var e = ship("海防","海防艦"); var a = ship("水母","水上機母艦");
        assertTrue(p.test(List.of(e,e,d,d))); assertTrue(p.test(List.of(a,a,d,d)));
        assertFalse(p.test(List.of(e,a,d,d))); assertFalse(p.test(List.of(d,e,e,d)));
        assertFalse(p.test(List.of(e,e,d)));
    }
    @Test void rainy2026EscortAndCounterattackCompositions() throws Exception {
        var d = ship("夕雲改二","駆逐艦"); var second = ship("巻雲改二","駆逐艦"); var cvl = ship("軽空","軽空母");
        var escort = quest("eventquest",954).getFilter().getFleet();
        assertTrue(escort.test(List.of(cvl,d,second))); assertFalse(escort.test(List.of(d,cvl,second)));
        assertFalse(escort.test(List.of(cvl,d)));
        var attack = quest("eventquest",955).getFilter().getFleet(); var bb = ship("戦艦","戦艦"); var ca = ship("重巡","重巡洋艦"); var cav = ship("航巡","航空巡洋艦");
        assertTrue(attack.test(List.of(bb,ca,cav))); assertTrue(attack.test(List.of(bb,d,second)));
        assertFalse(attack.test(List.of(bb,ca,d))); assertFalse(attack.test(List.of(ca,cav,d,second)));
    }
    @Test void sourceDisagreementsAndGoalProxyRemainExplicitlyUnresolved() throws Exception {
        var records = facts().path("definitions");
        for (var row : records) if (row.path("id").asInt() == 843) {
            assertEquals("ウィークリー", row.path("source_period").asText());
            assertEquals("マンスリー", row.path("encoded_period").asText());
            var rule = row.path("rules").get(3); assertEquals("T",rule.path("source_cell").asText());
            assertTrue(rule.path("encoded_cell").isNull()); assertTrue(rule.has("unresolved"));
        }
        assertFalse(facts().path("scope").asText().isBlank());
    }
}
