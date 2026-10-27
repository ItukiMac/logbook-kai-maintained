package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Modifier;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import logbook.internal.kancolle.BattleLogs;
import logbook.internal.kancolle.MissionLogs;
import logbook.plugin.api.RecordDataV1;
import logbook.plugin.api.RecordDataV1.*;
import org.junit.jupiter.api.Test;

class RecordDataContractTest {
    static String[] fields(String rank, String area, String boss, String ship, String exp) {
        String[] fields = new String[65]; Arrays.fill(fields, "");
        fields[0]="2026-10-27 12:00:00"; fields[1]=area+" synthetic"; fields[2]="1";
        fields[3]=boss; fields[4]=rank; fields[11]="enemy"; fields[12]="駆逐艦";
        fields[13]="synthetic ship"; fields[63]=ship; fields[64]=exp;
        return fields;
    }
    static BattleLogs.SimpleBattleLog raw(String rank, String area, String boss) {
        return new BattleLogs.SimpleBattleLog(String.join(",",fields(rank,area,boss,"120","60")));
    }
    static BattleRow row(String rank, String area, String boss) { return RecordDataService.copyBattle(raw(rank,area,boss)); }

    @Test void versionIsExplicit() { assertEquals(1,RecordDataV1.version()); }
    @Test void boundedQueryRejectsAnUndefinedCutoffBeforeReading() {
        assertThrows(NullPointerException.class, () -> RecordDataV1.missionsSince(null));
    }
    @Test void publicValuesAreFinalAndContainNoMutableBeansOrJavafx() {
        for (Class<?> type : List.of(BattleRow.class, MissionRow.class, DevelopmentRow.class, BattleTotals.class, RecordedInteger.class)) {
            assertTrue(type.isRecord()); assertTrue(Modifier.isFinal(type.getModifiers()));
            for (var component : type.getRecordComponents()) {
                Class<?> c=component.getType();
                assertTrue(c.isPrimitive() || c==String.class || c==Integer.class || c==ZonedDateTime.class || c==ValueState.class,component.toString());
            }
        }
        for (var method : RecordDataV1.class.getMethods()) {
            assertFalse(method.toGenericString().contains("logbook.bean"));
            assertFalse(method.toGenericString().contains("javafx"));
            assertFalse(method.toGenericString().contains("logbook.internal"));
        }
    }
    @Test void battleSnapshotDoesNotShareMutableParserState() {
        var raw=raw("S","1-1","出撃ボス");var row=RecordDataService.copyBattle(raw);
        raw.setRank("D");raw.setShipExp("999");raw.setArea("changed");
        assertEquals("S",row.rank());assertEquals("120",row.shipExp());assertEquals("synthetic",row.area());
    }
    @Test void missionSnapshotDoesNotShareMutableParserState() {
        var raw=new MissionLogs.SimpleMissionLog("2026-10-27 12:00:00,成功,area,mission,30,20,0,0");
        var row=RecordDataService.copyMission(raw);raw.setFuel(900);raw.setName("changed");
        assertEquals(30,row.fuel());assertEquals("mission",row.name());
    }
    @Test void allRanksAndUnknownRanksKeepExistingCountMeaning() {
        List<BattleRow> rows=new ArrayList<>();for(String rank:List.of("S","A","B","C","D","E","?")) rows.add(row(rank,"1-1","出撃"));
        assertEquals(new BattleTotals("7",3,1,1,1,1,1),RecordDataV1.summarizeBattles(rows,null,false));
    }
    @Test void bossAndAreaFiltersDoNotCountDeparturesAsBossBattles() {
        var rows=List.of(row("S","1-1","出撃"),row("A","1-1","ボス"),row("B","2-1","出撃ボス"));
        assertEquals(new BattleTotals("1",2,1,1,0,0,0),RecordDataV1.summarizeBattles(rows,"1-1",false));
        assertEquals(new BattleTotals("-",1,0,1,0,0,0),RecordDataV1.summarizeBattles(rows,"1-1",true));
        assertEquals(new BattleTotals("-",2,0,1,1,0,0),RecordDataV1.summarizeBattles(rows,null,true));
    }
    @Test void emptySelectionIsDistinctFromMissingDepartureValue() {
        assertEquals("0",RecordDataV1.summarizeBattles(List.of(),null,false).departures());
        assertEquals("-",RecordDataV1.summarizeBattles(List.of(),null,true).departures());
    }
    @Test void legacyCollectionSignatureStillReturnsTheOriginalPublicModel() {
        var result=BattleLogs.collect(List.of(raw("S","1-1","出撃ボス")),null,false);
        assertEquals(logbook.internal.gui.BattleLogCollect.class,result.getClass());
        assertEquals("1",result.getStart());assertEquals(1,result.getS());assertEquals(1,result.getWin());
    }
    @Test void aggregationDoesNotMutateInputSnapshots() {
        var list=List.of(row("S","1-1","出撃"));var before=list.getFirst();
        RecordDataV1.summarizeBattles(list,null,false);assertSame(before,list.getFirst());assertEquals("S",before.rank());
    }
    @Test void recordedZeroIsDifferentFromMissingExperience() {
        var zero=RecordDataV1.integerValue("0");var missing=RecordDataV1.integerValue("");
        assertEquals(ValueState.RECORDED,zero.state());assertEquals(0,zero.value());assertEquals("0",zero.display());
        assertEquals(ValueState.MISSING,missing.state());assertNull(missing.value());assertEquals("未記録",missing.display());
    }
    @Test void invalidAndOverflowValuesAreNotSilentlyZeroed() {
        for(String value:List.of("oops","2147483648"," 10 ")) {
            var result=RecordDataV1.integerValue(value);assertEquals(ValueState.INVALID,result.state());
            assertEquals(value,result.raw());assertNull(result.value());assertEquals("不正",result.display());
        }
    }
    @Test void nullExperienceRemainsMissing() { assertEquals(ValueState.MISSING,RecordDataV1.integerValue(null).state()); }
    @Test void impossibleRecordedStateIsRejected() {
        assertThrows(IllegalArgumentException.class,()->new RecordedInteger("",0,ValueState.MISSING));
        assertThrows(IllegalArgumentException.class,()->new RecordedInteger("1",null,ValueState.RECORDED));
    }
    @Test void oldFourteenColumnRecordStillProducesADetachedRow() {
        var old=new BattleLogs.SimpleBattleLog(String.join(",",Arrays.copyOf(fields("S","1-1","出撃","",""),14)));
        var row=RecordDataService.copyBattle(old);assertEquals("S",row.rank());
        assertEquals(ValueState.MISSING,RecordDataV1.integerValue(row.exp()).state());
    }
    @Test void developmentParserPreservesSuccessFailureAndTrailingFields() {
        var success=RecordDataV1.parseDevelopment("date,item,type,10,20,30,40,secretary,trailing").orElseThrow();
        assertEquals(10,success.fuel());assertEquals("secretary",success.secretary());
        var failed=RecordDataV1.parseDevelopment("date,,,10,20,30,40,secretary").orElseThrow();
        assertEquals("",failed.item());assertEquals(40,failed.bauxite());
    }
    @Test void malformedDevelopmentIsRejectedWithoutAReplacementRecord() {
        for(String value:List.of("short","date,item,type,no,20,30,40,secretary")) assertTrue(RecordDataV1.parseDevelopment(value).isEmpty());
        assertTrue(RecordDataV1.parseDevelopment(null).isEmpty());
    }
    @Test void dateRangeIncludesBothEndpointsAtTheExistingResetZone() {
        var range=new DateRange(LocalDate.of(2026,10,27),LocalDate.of(2026,10,28));
        assertTrue(range.accept(range.getFrom(),range.getFrom()));assertTrue(range.accept(range.getTo(),range.getFrom()));
        assertFalse(range.accept(range.getFrom().minusNanos(1),range.getFrom()));
        assertFalse(range.accept(range.getTo().plusNanos(1),range.getFrom()));
        assertEquals(5,range.getFrom().withZoneSameInstant(ZoneId.of("Asia/Tokyo")).getHour());
    }
    @Test void invertedDateRangeCannotPretendToBeAValidQuery() {
        assertThrows(IllegalArgumentException.class,()->new DateRange(LocalDate.of(2026,10,28),LocalDate.of(2026,10,27)));
    }
    @Test void periodAdaptersUseExistingSelectionRulesWithoutDuplicatingThem() {
        ZonedDateTime now=ZonedDateTime.of(2026,1,1,0,0,0,0,ZoneId.of("GMT+04:00"));
        for(Period p:Period.values()) for(int days:new int[]{-400,-40,-8,-1,0,1}) {
            var old=BattleLogs.Unit.valueOf(p.name());assertEquals(old.getName(),p.getName());
            assertEquals(old.accept(now.plusDays(days),now),p.accept(now.plusDays(days),now));
        }
    }
}
