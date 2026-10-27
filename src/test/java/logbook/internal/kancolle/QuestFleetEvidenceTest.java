package logbook.internal.kancolle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import tools.jackson.databind.ObjectMapper;
import logbook.bean.*;
import logbook.bean.BattleTypes.IFormation;
import logbook.internal.kancolle.BattleLogs.SimpleBattleLog;
import org.junit.jupiter.api.Test;

/** Exercise the real aggregation path, keeping fixture-only saved-log and master data. */
class QuestFleetEvidenceTest {
    private static QuestCollect collect(List<Ship> members, Map<Ship, ShipMst> masters) throws Exception {
        var condition = logbook.internal.json.JsonMappers.builder().build().readValue("""
          {"filter":{"fleet":{"name":["required"],"order":1}},
           "conditions":[{"area":["1-4"],"boss":true,"rank":["S"],"count":1}]}
          """, AppQuestCondition.class);
        var quest = new AppQuest(); quest.setNo(100);
        var duration = mock(AppQuestDuration.class); var row = mock(SimpleBattleLog.class);
        when(row.getDateString()).thenReturn("fixture"); when(row.getArea()).thenReturn("fixture-sea");
        when(row.getCell()).thenReturn("L"); when(row.getBoss()).thenReturn("ボス"); when(row.getRank()).thenReturn("S");
        when(duration.getCondition(quest)).thenReturn(Optional.of(List.of(row)));
        var maps = new MapinfoMstCollection(); var map = new MapinfoMst();
        map.setName("fixture-sea"); map.setMapareaId(1); map.setNo(4); maps.getMapinfo().put(14,map);
        var battle = new BattleLog();
        var fight = mock(IFormation.class); when(fight.getDockId()).thenReturn(1);
        battle.setBattle(fight); battle.setDeckMap(Map.of(1,members));
        battle.setItemMap(Map.of()); battle.setEscape(Set.of());
        try (var durations=mockStatic(AppQuestDuration.class); var mapData=mockStatic(MapinfoMstCollection.class);
             var logs=mockStatic(BattleLogs.class); var ships=mockStatic(Ships.class)) {
            durations.when(AppQuestDuration::get).thenReturn(duration); mapData.when(MapinfoMstCollection::get).thenReturn(maps);
            logs.when(()->BattleLogs.read("fixture")).thenReturn(battle);
            ships.when(()->Ships.shipMst(any(Ship.class))).thenAnswer(call -> {
                Ship recorded = call.getArgument(0);
                return masters.entrySet().stream()
                        .filter(e -> Objects.equals(e.getKey().getShipId(), recorded.getShipId()))
                        .map(Map.Entry::getValue).findFirst();
            });
            return QuestCollect.collect(quest,condition);
        }
    }
    private static Ship ship(int id) { var s=new Ship(); s.setId(id); s.setShipId(id);return s; }
    private static ShipMst master(String name) { var m=new ShipMst();m.setName(name);return m; }
    @Test void unresolvedFlagshipCannotPromoteSecondShipIntoFlagship() throws Exception {
        var a=ship(1);var b=ship(2);
        assertEquals(0,collect(List.of(a,b),Map.of(b,master("required"))).getTotal().getBoss().getS());
    }
    @Test void unresolvedEscortCannotBeSilentlyRemoved() throws Exception {
        var a=ship(1);var b=ship(2);
        assertEquals(0,collect(List.of(a,b),Map.of(a,master("required"))).getTotal().getBoss().getS());
    }
    @Test void leadingGapCannotPromoteSecondShipIntoFlagship() throws Exception {
        var a=ship(1);
        assertEquals(0,collect(Arrays.asList(null,a),Map.of(a,master("required"))).getTotal().getBoss().getS());
    }
    @Test void internalGapIsNotCompacted() throws Exception {
        var a=ship(1);var b=ship(2);
        assertEquals(0,collect(Arrays.asList(a,null,b),Map.of(a,master("required"),b,master("escort"))).getTotal().getBoss().getS());
    }
    @Test void recordedTrailingEmptySlotsRemainSupported() throws Exception {
        var a=ship(1);var b=ship(2);
        assertEquals(1,collect(Arrays.asList(a,b,null,null,null,null),Map.of(a,master("required"),b,master("escort"))).getTotal().getBoss().getS());
    }
    @Test void emptyRecordedFleetCannotEstablishAFlagship() throws Exception {
        assertEquals(0, collect(List.of(), Map.of()).getTotal().getBoss().getS());
    }
    @Test void onlyEmptyPaddingCannotEstablishAFlagship() throws Exception {
        assertEquals(0, collect(Arrays.asList(null,null,null), Map.of()).getTotal().getBoss().getS());
    }
    @Test void completeFleetStillMatchesWithoutChangingEvidence() throws Exception {
        var a=ship(1);var b=ship(2);var members=new ArrayList<>(List.of(a,b));
        assertEquals(1,collect(members,Map.of(a,master("required"),b,master("escort"))).getTotal().getBoss().getS());
        assertEquals(List.of(a,b),members);
    }
}
