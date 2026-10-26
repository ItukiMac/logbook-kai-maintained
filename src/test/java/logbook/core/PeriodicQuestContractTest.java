package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

/** External-source transcriptions test the real predicates, not just JSON syntax. */
class PeriodicQuestContractTest {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.ALLOW_COMMENTS);
    private static byte[] resource(String name) throws Exception {
        try (InputStream in = PeriodicQuestContractTest.class.getResourceAsStream('/' + name)) {
            assertNotNull(in, name); return in.readAllBytes();
        }
    }
    private static AppQuestCondition quest(int id) throws Exception {
        return JSON.readValue(resource("logbook/quest/"+id+".json"), AppQuestCondition.class);
    }
    private static List<String> strings(JsonNode node) {
        List<String> result = new ArrayList<>();
        for (JsonNode value : node) result.add(value.asText());
        return result;
    }
    private static Set<String> set(Collection<String> value) {
        return value == null ? Set.of() : new HashSet<>(value);
    }
    private static void rank(QuestCollect.Rank value, char rank, int n) {
        switch(rank) {
            case 'S' -> value.setS(n); case 'A' -> value.setA(n); case 'B' -> value.setB(n);
            case 'C' -> value.setC(n); case 'D' -> value.setD(n); case 'E' -> value.setE(n);
            default -> throw new IllegalArgumentException();
        }
    }
    private static QuestCollect battle(JsonNode spec, String area, String cell, char r, int n, boolean boss) {
        QuestCollect collected = new QuestCollect();
        QuestCollect.Count c = new QuestCollect.Count();
        rank(c.getAll(), r, n);
        if (boss) rank(c.getBoss(), r, n);
        if (cell != null) {
            var cr = new QuestCollect.Rank();rank(cr,r,n);c.getCell().put(area+'-'+cell,cr);
        }
        if (spec.path("areas").isArray()) collected.getArea().put(area,c);
        else collected.setTotal(c);
        return collected;
    }
    @TestFactory Stream<DynamicTest> reviewedBattlePredicates() throws Exception {
        JsonNode root=JSON.readTree(resource("maintenance/r4-periodic-contracts.json"));
        assertEquals(38,root.path("definitions").size());
        List<DynamicTest> tests=new ArrayList<>();
        for(JsonNode record:root.path("definitions")) {
            int id=record.path("id").asInt();
            tests.add(DynamicTest.dynamicTest("reviewed quest "+id,()-> {
                byte[] raw=resource("logbook/quest/"+id+".json");
                assertEquals(record.path("definition_sha256").asText(),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)),"review fingerprint");
                var actual=JSON.readValue(raw,AppQuestCondition.class);
                assertEquals(record.path("period").asText(),actual.getResetType());
                assertEquals(record.path("rules").size(),actual.getConditions().size());
                assertFalse(actual.test(new QuestCollect()));
                for(int i=0;i<actual.getConditions().size();i++) {
                    var condition=actual.getConditions().get(i);JsonNode spec=record.path("rules").get(i);
                    int target=spec.path("count").asInt();assertEquals(target,condition.getCount());
                    switch(spec.path("kind").asText()) {
                        case "kills" -> {
                            assertFalse(condition.isStart());assertFalse(condition.isBoss());
                            for(String type:strings(spec.path("types"))) {
                                assertTrue(condition.getStype().contains(type));
                                var c=new QuestCollect();c.getStype().put(type,target-1);assertFalse(condition.test(c));
                                c.getStype().put(type,target);assertTrue(condition.test(c));
                            }
                            var unrelated=new QuestCollect();unrelated.getStype().put("航空戦艦",999);
                            assertFalse(condition.test(unrelated),"Historical armored-carrier bosses have aviation-battleship type");
                            // Do NOT declare modern armored-carrier enemy semantics verified.
                            if(id!=211 && id!=220) assertEquals(new HashSet<>(strings(spec.path("types"))),set(condition.getStype()));
                        }
                        case "sorties" -> {
                            assertTrue(condition.isStart());var c=new QuestCollect();c.getTotal().setStart(target-1);
                            assertFalse(condition.test(c));c.getTotal().setStart(target);assertTrue(condition.test(c));
                        }
                        case "battle" -> {
                            assertFalse(condition.isStart());assertNull(condition.getStype());
                            boolean boss=spec.path("boss").asBoolean();assertEquals(boss,condition.isBoss());
                            assertEquals(new HashSet<>(strings(spec.path("areas"))),set(condition.getArea()));
                            String cell=spec.path("cell").isNull()?null:spec.path("cell").asText();
                            assertEquals(cell,condition.getCell());assertNull(condition.getCells());
                            String allowed=spec.path("ranks").asText();
                            for(char r:"SABCDE".toCharArray()) {
                                String area=spec.path("areas").isArray()?spec.path("areas").get(0).asText():"1-1";
                                assertFalse(condition.test(battle(spec,area,cell,r,target-1,boss)));
                                assertEquals(allowed.indexOf(r)>=0,condition.test(battle(spec,area,cell,r,target,boss)),"rank "+r);
                            }
                            if(spec.path("areas").isArray()) {
                                for(String area:strings(spec.path("areas")))
                                    assertTrue(condition.test(battle(spec,area,cell,'S',target,boss)),area);
                                assertFalse(condition.test(battle(spec,"99-99",cell,'S',target,boss)),"unrelated sea area");
                            }
                            if(cell!=null) assertFalse(condition.test(battle(spec,spec.path("areas").get(0).asText(),"wrong",'S',target,boss)));
                            else if(boss) assertFalse(condition.test(battle(spec,spec.path("areas").isArray()?spec.path("areas").get(0).asText():"1-1",null,'S',target,false)),"nonboss battles");
                        }
                        default -> fail("Unknown manually reviewed predicate");
                    }
                }
            }));
        }
        return tests.stream();
    }
    private static ShipMst ship(String name,String type) {
        ShipMst s=mock(ShipMst.class);when(s.getName()).thenReturn(name);
        Stype st=new Stype();st.setName(type);when(s.asStype()).thenReturn(Optional.of(st));return s;
    }
    private static ShipMst type(String type) {return ship(type,type);}
    private static boolean fleet(int id, ShipMst... ships) throws Exception {
        return quest(id).getFilter().getFleet().test(new ArrayList<>(List.of(ships)));
    }
    @Test void mainForceDailyCountsDefeatsButFirstDailyRequiresVictory() throws Exception {
        for(char r:"CDE".toCharArray()) {
            var c=new QuestCollect();rank(c.getTotal().getAll(),r,1);
            assertTrue(quest(216).test(c));assertFalse(quest(201).test(c));
        }
    }
    @Test void agoRequiresEveryIndependentCounterAndSNeedNotBeBoss() throws Exception {
        var c=new QuestCollect();c.getTotal().setStart(36);c.getTotal().getAll().setS(6);
        c.getTotal().getBoss().setB(12);c.getTotal().getBoss().setE(12);
        assertTrue(quest(214).test(c));c.getTotal().setStart(35);assertFalse(quest(214).test(c));
        c.getTotal().setStart(36);c.getTotal().getAll().setS(5);assertFalse(quest(214).test(c));
        c.getTotal().getAll().setS(6);c.getTotal().getBoss().setE(11);assertFalse(quest(214).test(c));
        c.getTotal().getBoss().setE(13);c.getTotal().getBoss().setB(11);assertFalse(quest(214).test(c));
    }
    @Test void fifthSquadronRequiresThreeDistinctNamedShips() throws Exception {
        assertTrue(fleet(249,ship("妙高改二","重巡洋艦"),ship("那智改二","重巡洋艦"),ship("羽黒改二","重巡洋艦")));
        assertFalse(fleet(249,ship("妙高改二","重巡洋艦"),ship("那智改二","重巡洋艦")));
    }
    @Test void torpedoSquadronRequiresFlagshipLightCruiserAndAtLeastThreeDestroyers() throws Exception {
        var cl=type("軽巡洋艦");var dd=type("駆逐艦");
        assertTrue(fleet(257,cl,cl,dd,dd,dd,dd));
        assertFalse(fleet(257,cl,dd,dd));assertFalse(fleet(257,dd,cl,dd,dd,dd,dd));
        assertFalse(fleet(257,cl,cl,cl,cl,dd,dd));
        assertFalse(fleet(257,cl,type("重雷装巡洋艦"),dd,dd,dd,dd));
        // Total-size/retreat handling is intentionally not certified by these examples.
    }
    @Test void southernStrikeRequiresTheNamedJapaneseBattleshipFamilies() throws Exception {
        var a=ship("長門改二","戦艦");var b=ship("伊勢改二","航空戦艦");var c=ship("大和改二","戦艦");
        var foreign=ship("Colorado改","戦艦");var cl=type("軽巡洋艦");var dd=type("駆逐艦");
        assertTrue(fleet(259,a,b,c,cl,foreign,dd));assertFalse(fleet(259,a,b,foreign,cl,dd,dd));
    }
    @Test void westernCarrierFleetAllowsLightAndArmoredCarriersButNeedsTwoDestroyers() throws Exception {
        assertTrue(fleet(264,type("軽空母"),type("装甲空母"),type("駆逐艦"),type("駆逐艦")));
        assertFalse(fleet(264,type("軽空母"),type("装甲空母"),type("駆逐艦")));
    }
    @Test void counterattackRequiresDestroyerFlagshipAndNotAnAviationCruiser() throws Exception {
        var dd=type("駆逐艦");var ca=type("重巡洋艦");var cl=type("軽巡洋艦");
        assertTrue(fleet(266,dd,ca,cl,dd,dd,dd));
        assertFalse(fleet(266,ca,dd,cl,dd,dd,dd));
        assertFalse(fleet(266,dd,type("航空巡洋艦"),cl,dd,dd,dd));
    }
    @Test void patrolAcceptsListedLightCruiserAndEscortAlternatives() throws Exception {
        for(int id:new int[]{280,284}) {
            assertTrue(fleet(id,type("重雷装巡洋艦"),type("駆逐艦"),type("海防艦"),type("海防艦")));
            assertTrue(fleet(id,type("練習巡洋艦"),type("海防艦"),type("海防艦"),type("海防艦")));
            assertFalse(fleet(id,type("正規空母"),type("海防艦"),type("海防艦"),type("海防艦")));
        }
    }
    @Test void reconnaissanceAndNorthernGuardDoNotSubstituteTorpedoCruisersForLightCruisers() throws Exception {
        assertTrue(fleet(862,type("水上機母艦"),type("軽巡洋艦"),type("軽巡洋艦")));
        assertFalse(fleet(862,type("水上機母艦"),type("軽巡洋艦"),type("重雷装巡洋艦")));
        assertTrue(fleet(873,type("軽巡洋艦")));assertFalse(fleet(873,type("重雷装巡洋艦")));
    }
    @Test void thirtyFirstDestroyerNeedsNaganamiKaiNiAndOneOfThreeOthers() throws Exception {
        assertTrue(fleet(875,ship("長波改二","駆逐艦"),ship("朝霜改二","駆逐艦")));
        assertFalse(fleet(875,ship("長波改","駆逐艦"),ship("朝霜改二","駆逐艦")));
        assertFalse(fleet(875,ship("長波改二","駆逐艦"),ship("夕立改二","駆逐艦")));
    }
    @Test void mikawaRequiresFourFromItsNamedPool() throws Exception {
        var a=ship("鳥海改二","重巡洋艦");var b=ship("古鷹改二","重巡洋艦");
        var c=ship("天龍改二","軽巡洋艦");var d=ship("夕張改二特","軽巡洋艦");
        assertTrue(fleet(888,a,b,c,d));assertFalse(fleet(888,a,b,c,type("重巡洋艦")));
    }
    @Test void carrierPatrolRequiresAnAircraftCarrierNotAnAviationBattleship() throws Exception {
        assertTrue(fleet(894,type("軽空母")));assertTrue(fleet(894,type("装甲空母")));
        assertFalse(fleet(894,type("航空戦艦")));
    }
    @Test void sixthSquadronPreservesFlagshipAndEitherBranchOfTheAlternative() throws Exception {
        var y=ship("夕張改二特","軽巡洋艦");var yura=ship("由良改二","軽巡洋艦");
        var m=ship("睦月改二","駆逐艦");var k=ship("如月改二","駆逐艦");
        assertTrue(fleet(903,y,yura));assertTrue(fleet(903,y,m,k));
        assertFalse(fleet(903,y,m));assertFalse(fleet(903,yura,y));
        assertFalse(fleet(903,ship("夕張改","軽巡洋艦"),m,k));
    }
}
