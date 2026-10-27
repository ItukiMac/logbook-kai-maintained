package logbook.internal.gui;
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
