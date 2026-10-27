package logbook.internal.kancolle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.ObjectMapper;
import logbook.bean.*;
import logbook.internal.kancolle.BattleLogs.SimpleBattleLog;
import org.junit.jupiter.api.Test;

/** Actual aggregation entry point, with saved-log I/O substituted by isolated fixtures. */
class QuestCollectRequirementsTest {
    private static AppQuestCondition condition(String filter) throws Exception {
        return logbook.internal.json.JsonMappers.builder().build().readValue("{\"conditions\":[{\"boss\":true,\"area\":[\"2-4\"],\"rank\":[\"S\"],\"count\":2}],\"filter\":"+filter+"}",AppQuestCondition.class);
    }
    private static BattleLog battle(Integer deck,Set<Integer> escape) {
        var b=new BattleLog(); var day=new SortieBattle(); day.setDockId(deck); b.setBattle(day); b.setEscape(escape); return b;
    }
    private static QuestCollect run(AppQuestCondition condition,List<BattleLog> saved,boolean assertNoRead) {
        AppQuest quest=new AppQuest(); quest.setNo(854);
        var duration=mock(AppQuestDuration.class); var maps=new MapinfoMstCollection(); var map=new MapinfoMst();
        map.setName("fixture-sea"); map.setMapareaId(2); map.setNo(4); maps.getMapinfo().put(24,map);
        List<SimpleBattleLog> rows=new ArrayList<>();
        for(int i=0;i<saved.size();i++) {
            var row=mock(SimpleBattleLog.class); when(row.getArea()).thenReturn("fixture-sea");
            when(row.getDateString()).thenReturn("fixture-"+i); when(row.getBoss()).thenReturn("ボス");
            when(row.getCell()).thenReturn("P"); when(row.getRank()).thenReturn("S"); rows.add(row);
        }
        when(duration.getCondition(quest)).thenReturn(Optional.of(rows));
        try(var durations=mockStatic(AppQuestDuration.class);var mapStatic=mockStatic(MapinfoMstCollection.class);var logs=mockStatic(BattleLogs.class)) {
            durations.when(AppQuestDuration::get).thenReturn(duration);mapStatic.when(MapinfoMstCollection::get).thenReturn(maps);
            for(int i=0;i<saved.size();i++){String id="fixture-"+i; logs.when(()->BattleLogs.read(id)).thenReturn(saved.get(i));}
            QuestCollect result=QuestCollect.collect(quest,condition);
            if(assertNoRead)logs.verifyNoInteractions();
            return result;
        }
    }
    @Test void secondFleetThenFirstFleetCountsOnlyOneInsteadOfPrematureCompletion() throws Exception {
        var c=condition("{\"deckIds\":[1]}");
        var result=run(c,List.of(battle(2,Set.of()),battle(1,Set.of())),false);
        assertEquals(1,result.getTotal().getBoss().getS()); assertFalse(c.test(result));
    }
    @Test void validFirstFleetRecordsReachThreshold() throws Exception {
        var c=condition("{\"deckIds\":[1]}"); var result=run(c,List.of(battle(1,Set.of()),battle(1,Set.of())),false);
        assertTrue(c.test(result)); assertEquals(2,result.getTotal().getBoss().getS());
    }
    @Test void absentLogsAreSkippedAndDoNotPreventLaterValidRecords() throws Exception {
        var c=condition("{\"deckIds\":[1]}");
        var result=run(c,Arrays.asList(null,battle(null,Set.of()),battle(1,Set.of())),false);
        assertEquals(1,result.getTotal().getBoss().getS());
    }
    @Test void retreatAndUnknownSnapshotCannotContribute() throws Exception {
        var c=condition("{\"noRetreat\":true}");
        var result=run(c,List.of(battle(1,Set.of(123)),battle(1,null),battle(2,Set.of())),false);
        assertEquals(1,result.getTotal().getBoss().getS());
    }
    @Test void unrestrictedOrAreaOnlyDefinitionsDoNotLoadFullLogs() throws Exception {
        for(String filter:List.of("{}","{\"area\":[\"2-4\"]}")) {
            var c=condition(filter); var result=run(c,Arrays.asList(null,null),true);
            assertTrue(c.test(result));
        }
    }
    @Test void areaMismatchIsRejectedBeforeAnyFullLogRead() throws Exception {
        var c=condition("{\"area\":[\"1-4\"],\"deckIds\":[1]}");
        assertEquals(0,run(c,List.of(battle(1,Set.of())),true).getTotal().getBoss().getS());
    }
    @Test void bundledZAndWaterTorpedoDefinitionsOptIntoTheRightGuardOnly() throws Exception {
        var mapper=logbook.internal.json.JsonMappers.builder().enable(tools.jackson.core.json.JsonReadFeature.ALLOW_JAVA_COMMENTS).build();
        for(int no:List.of(854,872,257))try(var in=AppQuestCondition.class.getClassLoader().getResourceAsStream("logbook/quest/"+no+".json")){
            var c=mapper.readValue(in,AppQuestCondition.class);
            if(no==257){assertEquals(Boolean.TRUE,c.getFilter().getNoRetreat());assertNull(c.getFilter().getDeckIds());}
            else {assertEquals(Set.of(1),c.getFilter().getDeckIds());assertNull(c.getFilter().getNoRetreat());}
        }
    }
}
