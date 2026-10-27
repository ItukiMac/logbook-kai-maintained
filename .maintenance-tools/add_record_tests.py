from pathlib import Path
import json,re
paths=json.loads(Path('../evidence/changed-paths.json').read_text())
def new(path,text):
    p=Path(path);assert not p.exists(),path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text);paths.append(path)
def edit(path,fn):
    p=Path(path);old=p.read_text();value=fn(old);assert old!=value,path;p.write_text(value)
    if path not in paths:paths.append(path)
def once(s,a,b):
    assert s.count(a)==1,(a,s.count(a));return s.replace(a,b)
# Keep the view model's established property method names; only its period value type changed.
edit('src/main/java/logbook/internal/gui/MissionRecordCollect.java',lambda s:s.replace('CollectPeriod','CollectUnit'))

new('src/test/java/logbook/core/RecordDataContractTest.java',r'''package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Modifier;
import java.time.*;
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
''')
new('src/test/java/logbook/internal/gui/RecordDataViewTest.java',r'''package logbook.internal.gui;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import logbook.core.RecordDataService;
import logbook.internal.kancolle.BattleLogs;
import logbook.plugin.api.RecordDataV1;
import org.junit.jupiter.api.Test;

class RecordDataViewTest {
    private static RecordDataV1.BattleRow row(String ship, String exp) {
        String[] v=new String[65];Arrays.fill(v,"");v[0]="2026-10-27 12:00:00";v[1]="1-1 test";v[2]="1";
        v[3]="出撃";v[4]="S";v[11]="enemy";v[63]=ship;v[64]=exp;
        return RecordDataService.copyBattle(new BattleLogs.SimpleBattleLog(String.join(",",v)));
    }
    @Test void missingExperienceIsANullNumericPropertyAndExplicitCopyText() {
        var view=BattleRecordDetail.toBattleRecordDetail(row("",""));
        assertNull(view.getExp());assertNull(view.expProperty().getValue());
        assertEquals(RecordDataV1.ValueState.MISSING,view.getExpValue().state());
        assertTrue(view.toString().endsWith("未記録\t未記録"));
    }
    @Test void malformedExperienceRemainsDistinctFromMissingAndRecordedZero() {
        var view=BattleRecordDetail.toBattleRecordDetail(row("0","bad"));
        assertEquals(0,view.getShipExp());assertNull(view.getExp());
        assertTrue(view.toString().endsWith("0\t不正"));
    }
    @Test void uiCreatesRowsFromImmutableTotalsWithoutChangingThem() {
        var total=new RecordDataV1.BattleTotals("2",3,1,1,1,0,0);var view=BattleRecordCollect.fromTotals(total);
        assertEquals("2",view.getStart());assertEquals(3,view.getWin());view.setS(99);assertEquals(1,total.s());
    }
    @Test void currentRecordControllersDoNotLinkMutableLogReadersOrCompatibilityRows() throws Exception {
        for(String name:List.of("BattleLogController","MissionLogController","CreateItemController")) {
            try(var in=getClass().getResourceAsStream(name+".class")) {
                assertNotNull(in);String bytecode=new String(in.readAllBytes(),StandardCharsets.ISO_8859_1);
                for(String forbidden:List.of("logbook/internal/kancolle/BattleLogs", "logbook/internal/kancolle/MissionLogs",
                        "logbook/internal/log/LogWriter", "java/nio/file/Files", "logbook/internal/gui/BattleLogDetail;",
                        "logbook/internal/gui/BattleLogCollect;")) assertFalse(bytecode.contains(forbidden),name+" -> "+forbidden);
            }
        }
    }
    @Test void currentBattleMenuNoLongerNeedsTheLegacyRowConversion() throws Exception {
        try(var in=getClass().getResourceAsStream("MainMenuController.class")) {
            String bytecode=new String(in.readAllBytes(),StandardCharsets.ISO_8859_1);
            assertFalse(bytecode.contains("logbook/internal/gui/BattleLogDetail"));
        }
    }
}
''')

# Enhance the real three-window test with old/malformed experience, grouped display, sorting and immutable snapshots.
def probe(s):
    s=once(s,'import logbook.plugin.api.RecordViewsV1;','import logbook.plugin.api.RecordViewsV1;\nimport logbook.plugin.api.RecordDataV1;')
    old='''        fixture(reports.resolve(new BattleResultLogFormat().fileName()),
                "synthetic battle header\\n" + String.join(",", currentBattle) + "\\n");'''
    replacement='''        String[] historicalBattle = Arrays.copyOf(currentBattle,14);
        historicalBattle[0] = "2026-10-27 12:00:01";
        String[] malformedExperience = currentBattle.clone();
        malformedExperience[0] = "2026-10-27 12:00:02";
        malformedExperience[64] = "bad-exp";
        fixture(reports.resolve(new BattleResultLogFormat().fileName()),
                "synthetic battle header\\n" + String.join(",", currentBattle) + "\\n"
                + String.join(",",historicalBattle) + "\\n" + String.join(",",malformedExperience) + "\\n");'''
    s=once(s,old,replacement)
    s=once(s,'AppConfig.get().setBattleLogDir(battle.toString());','''AppConfig.get().setBattleLogDir(battle.toString());
        var snapshot = RecordDataV1.battles();
        require(snapshot.size() == 3, "Immutable snapshot lost historical rows");
        try { snapshot.clear(); throw new AssertionError("Mutable battle snapshot"); }
        catch (UnsupportedOperationException expected) { }
        var periods = RecordDataV1.recentBattles();
        try { periods.clear(); throw new AssertionError("Mutable period snapshot"); }
        catch (UnsupportedOperationException expected) { }
        for (var rows : periods.values()) {
            try { rows.clear(); throw new AssertionError("Mutable period rows"); }
            catch (UnsupportedOperationException expected) { }
        }
        require(RecordDataV1.missions().size()==2 && RecordDataV1.developments().size()==2, "Record service results changed");''')
    s=once(s,'"BattleLogController", 1);','"BattleLogController", 3);')
    s=once(s,'require(exp.getCellObservableValue(detail.getItems().getFirst()).getValue().intValue() == 60, "Battle experience changed");','''require(detail.getItems().stream().filter(row -> exp.getCellObservableValue(row).getValue() != null)
                        .mapToInt(row -> exp.getCellObservableValue(row).getValue().intValue()).sum()==60, "Missing experience became a number");
                require(detail.getItems().stream().filter(row -> exp.getCellObservableValue(row).getValue()==null).count()==2,
                        "Old/malformed experience must stay unknown");
                require(detail.getItems().stream().anyMatch(row -> row.toString().endsWith("未記録\\t未記録")), "Copy hides missing values");
                require(detail.getItems().stream().anyMatch(row -> row.toString().endsWith("120\\t不正")), "Copy hides invalid values");
                javafx.scene.control.ChoiceBox<String> grouping = (javafx.scene.control.ChoiceBox<String>) fxml.getNamespace().get("aggregateType");
                grouping.getSelectionModel().select(FeatureUiV1.Tables.columnName(exp));
                TableView<?> aggregate = (TableView<?>) fxml.getNamespace().get("aggregate");
                require(aggregate.getItems().size()==3, "Grouping merged unknown/invalid/recorded values");
                ((TableView)detail).getSortOrder().add(exp);detail.sort();
                require(detail.getItems().size()==3, "Sorting dropped legacy records");
                root.applyCss();root.layout();
                var labels = root.lookupAll(".table-cell").stream().filter(n -> n instanceof javafx.scene.control.Labeled)
                        .map(n -> ((javafx.scene.control.Labeled)n).getText()).toList();
                require(labels.contains("未記録") && labels.contains("不正"), "Actual table cells lost unknown labels");''')
    s=s.replace('battle_rows=1 battle_exp=60','battle_rows=3 battle_exp=60')
    return s
edit('scripts/java/RecordUiProbe.java',probe)
edit('scripts/tests/test_record_ui_integration.py',lambda s:s.replace("'legacy_missing_experience_bug_fixed': False","'legacy_missing_experience_bug_fixed': True").replace('battle_rows=1,','battle_rows=3,'))
Path('../evidence/changed-paths.json').write_text(json.dumps(paths,indent=2)+'\n')
print('RECORD_DATA_REGRESSIONS_READY 24 Java methods + actual GUI proof; synthetic data only')
