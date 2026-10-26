package logbook.internal.kancolle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.fasterxml.jackson.databind.ObjectMapper;
import logbook.bean.*;
import org.junit.jupiter.api.Test;

/** Real duration and collector; only disk log/master services are fixture replacements. */
class QuestArrivalCollectionTest {
    private static final String EXPIRY="2025-06-01 05:00:00";
    private static long time(String date) { return LocalDateTime.parse(date,logbook.internal.util.DateUtil.DATE_FORMAT).atZone(ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli(); }
    private static PortArrival record(String start,String goal,int deck,List<Integer> masters,boolean noRetreat) {
        return new PortArrival(PortArrival.FORMAT,UUID.randomUUID().toString(),time(start),time(goal),"1-6",deck,masters,noRetreat);
    }
    private static PortArrival ordinary() { return record("2025-05-10 09:01:00","2025-05-10 09:10:00",1,List.of(101,102),true); }
    private static AppQuestDuration durations(String from,String to) {
        var value=new AppQuestDuration();var interval=new AppQuestDuration.Duration();interval.setFrom(from);interval.setTo(to);
        var byId=new ConcurrentHashMap<Integer,List<AppQuestDuration.Duration>>();byId.put(905,new ArrayList<>(List.of(interval)));value.getMap().put(EXPIRY,byId);return value;
    }
    private static AppQuestCondition condition() throws Exception {
        return new ObjectMapper().readValue("""
          {"filter":{"deckIds":[1],"noRetreat":true,"fleet":{"name":["flag"],"order":1}},
           "conditions":[{"area":["1-6"],"arrival":true,"count":1}]}
          """,AppQuestCondition.class);
    }
    private static QuestCollect collect(AppQuestDuration duration,List<PortArrival> records,boolean failRead,boolean unknownMaster,boolean battleOnly) throws Exception {
        return collect(duration, records, failRead, unknownMaster, battleOnly, condition());
    }
    private static QuestCollect collect(AppQuestDuration duration,List<PortArrival> records,boolean failRead,boolean unknownMaster,boolean battleOnly,AppQuestCondition activeCondition) throws Exception {
        var quest=new AppQuest();quest.setNo(905);quest.setExpire(EXPIRY);
        var rows=new ArrayList<BattleLogs.SimpleBattleLog>();
        if(battleOnly) {
            var row=mock(BattleLogs.SimpleBattleLog.class);when(row.getDateString()).thenReturn("2025-05-10 09:05:00");when(row.getArea()).thenReturn("near-sea");when(row.getCell()).thenReturn("B");when(row.getBoss()).thenReturn("");when(row.getRank()).thenReturn("S");rows.add(row);
        }
        var real=spy(duration);doReturn(Optional.of(rows)).when(real).getCondition(quest);
        var master=new ShipMstCollection();var flag=new ShipMst();flag.setName("flag");var escort=new ShipMst();escort.setName("escort");master.getShipMap().put(101,flag);if(!unknownMaster)master.getShipMap().put(102,escort);
        var maps=new MapinfoMstCollection();var sea=new MapinfoMst();sea.setName("near-sea");sea.setMapareaId(1);sea.setNo(6);maps.getMapinfo().put(16,sea);
        try(var periods=mockStatic(AppQuestDuration.class);var disk=mockStatic(PortArrivalRuntime.class);var m=mockStatic(ShipMstCollection.class);var map=mockStatic(MapinfoMstCollection.class);var battles=mockStatic(BattleLogs.class)) {
            periods.when(AppQuestDuration::get).thenReturn(real);m.when(ShipMstCollection::get).thenReturn(master);map.when(MapinfoMstCollection::get).thenReturn(maps);
            disk.when(()->PortArrivalRuntime.read(anyLong(),anyLong())).thenAnswer(call->{
                if(failRead)throw new java.io.IOException("fixture corruption");
                long from=call.getArgument(0),to=call.getArgument(1);
                return records.stream().filter(a->a.arrivedAt()>=from&&a.arrivedAt()<to).toList();
            });
            return QuestCollect.collect(quest,activeCondition);
        }
    }
    private static QuestCollect collect(List<PortArrival> records) throws Exception {return collect(durations("2025-05-10 09:00:00",null),records,false,false,false);}
    @Test void acceptedObservedArrivalCountsWithoutCreatingABattle() throws Exception {
        var actual=collect(List.of(ordinary()));assertEquals(1,actual.getArea().get("1-6").getArrivals());assertTrue(condition().test(actual));assertEquals(0,actual.getTotal().getAll().getS());
    }
    @Test void priorBattleAndReturnWithoutGoalNeverCountAsArrival() throws Exception {
        var actual=collect(durations("2025-05-10 09:00:00",null),List.of(),false,false,true);assertEquals(0,actual.getTotal().getArrivals());assertFalse(condition().test(actual));
    }
    @Test void departureBeforeAcceptanceDoesNotCount() throws Exception {
        assertEquals(0,collect(durations("2025-05-10 09:02:00",null),List.of(ordinary()),false,false,false).getTotal().getArrivals());
    }
    @Test void stoppedQuestAndExactEndBoundaryDoNotCount() throws Exception {
        for(String end:List.of("2025-05-10 09:09:00","2025-05-10 09:10:00")) assertEquals(0,collect(durations("2025-05-10 09:00:00",end),List.of(ordinary()),false,false,false).getTotal().getArrivals());
    }
    @Test void resetBoundaryExcludesThePreviousQuestPeriod() throws Exception {
        var atReset=record("2025-06-01 04:55:00",EXPIRY,1,List.of(101,102),true);assertEquals(0,collect(List.of(atReset)).getTotal().getArrivals());
    }
    @Test void noAcceptanceEvidenceMeansNoArrivalCredit() throws Exception {
        assertEquals(0,collect(new AppQuestDuration(),List.of(ordinary()),false,false,false).getTotal().getArrivals());
    }
    @Test void wrongDeckRetreatAndMissingMasterNeverSatisfyFleetFilter() throws Exception {
        assertEquals(0,collect(List.of(record("2025-05-10 09:01:00","2025-05-10 09:10:00",2,List.of(101,102),true))).getTotal().getArrivals());
        assertEquals(0,collect(List.of(record("2025-05-10 09:01:00","2025-05-10 09:10:00",1,List.of(101,102),false))).getTotal().getArrivals());
        assertEquals(0,collect(durations("2025-05-10 09:00:00",null),List.of(ordinary()),false,true,false).getTotal().getArrivals());
    }
    @Test void corruptedJournalIsVisibleAsUnavailableRatherThanZeroCertifiedProgress() throws Exception {
        var actual=collect(durations("2025-05-10 09:00:00",null),List.of(),true,false,false);assertTrue(actual.isArrivalEvidenceUnavailable());var c=condition();assertFalse(c.test(actual));assertTrue(c.getConditions().getFirst().getCurrent().contains("確認できません"));
    }
    @Test void incompleteAcceptanceDatesFailClosed() throws Exception {
        var actual=collect(durations("not a date",null),List.of(ordinary()),false,false,false);assertTrue(actual.isArrivalEvidenceUnavailable());
    }
    @Test void twoDistinctObservedSortiesCanMeetATwoArrivalTarget() throws Exception {
        var second=record("2025-05-10 10:01:00","2025-05-10 10:10:00",1,List.of(101,102),true);
        var actual=collect(List.of(ordinary(),second));var c=condition();c.getConditions().getFirst().setCount(2);assertTrue(c.test(actual));
        assertFalse(c.test(collect(List.of(ordinary()))));
    }

    @Test void departureFleetCannotStandInForUnprovenRetreatSurvivors() throws Exception {
        var rule=condition();rule.getFilter().setNoRetreat(null);
        var retired=record("2025-05-10 09:01:00","2025-05-10 09:10:00",1,List.of(101,102),false);
        assertEquals(0,collect(durations("2025-05-10 09:00:00",null),List.of(retired),false,false,false,rule).getTotal().getArrivals());
    }
    @Test void noExpiryHasNoAcceptedArrivalInterval() throws Exception {
        var quest=new AppQuest();quest.setNo(905);
        assertTrue(new AppQuestDuration().getArrivalCondition(quest).isEmpty());
    }

    @Test void impossibleCalendarDayCannotNormalizeIntoAnAcceptanceDate() throws Exception {
        var actual=collect(durations("2025-02-30 09:00:00",null),List.of(ordinary()),false,false,false);
        assertTrue(actual.isArrivalEvidenceUnavailable());
    }
}
