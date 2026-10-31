package logbook.internal.kancolle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.ObjectMapper;
import logbook.bean.AppQuest;
import logbook.bean.AppQuestCondition;
import logbook.bean.AppQuestDuration;
import logbook.bean.BattleLog;
import logbook.bean.BattleTypes.IFormation;
import logbook.bean.MapinfoMstCollection;
import logbook.bean.ShipMstCollection;
import logbook.bean.Stype;
import logbook.bean.StypeCollection;
import logbook.constants.ShipType;
import logbook.internal.kancolle.BattleLogs.SimpleBattleLog;
import org.junit.jupiter.api.Test;

/** Saved-log aggregation through the real PhaseState, master lookup and bundled quest predicates. */
class QuestEnemyCarrierClassificationTest {
    private static final ObjectMapper JSON = logbook.internal.json.JsonMappers.builder()
            .enable(tools.jackson.core.json.JsonReadFeature.ALLOW_JAVA_COMMENTS).build();

    private static AppQuestCondition condition(int questId) throws Exception {
        try (var in = QuestEnemyCarrierClassificationTest.class.getResourceAsStream(
                "/logbook/quest/" + questId + ".json")) {
            assertNotNull(in);
            return JSON.readValue(in, AppQuestCondition.class);
        }
    }

    private static ShipMstCollection publishedMasters() throws Exception {
        try (var in = QuestEnemyCarrierClassificationTest.class.getResourceAsStream(
                "/logbook/config/logbook.bean.ShipMstCollection.json")) {
            assertNotNull(in);
            return JSON.readValue(in, ShipMstCollection.class);
        }
    }

    private static QuestCollect collect(int questId, List<Integer> enemyIds, int remainingHp) throws Exception {
        var condition = condition(questId);
        var quest = new AppQuest();
        quest.setNo(questId);
        var duration = mock(AppQuestDuration.class);
        var rows = new ArrayList<SimpleBattleLog>();
        var battles = new ArrayList<BattleLog>();
        for (int i = 0; i < enemyIds.size(); i++) {
            var row = mock(SimpleBattleLog.class);
            when(row.getDateString()).thenReturn("carrier-fixture-" + i);
            when(row.getArea()).thenReturn("synthetic-sea");
            when(row.getCell()).thenReturn("A");
            when(row.getBoss()).thenReturn("");
            when(row.getRank()).thenReturn("B");
            rows.add(row);
            var fight = mock(IFormation.class);
            when(fight.getDockId()).thenReturn(1);
            when(fight.getShipKe()).thenReturn(List.of(enemyIds.get(i)));
            when(fight.getShipLv()).thenReturn(List.of(1));
            when(fight.getESlot()).thenReturn(List.of(List.of()));
            when(fight.getEMaxhps()).thenReturn(List.of(50));
            when(fight.getENowhps()).thenReturn(List.of(remainingHp));
            var battle = new BattleLog();
            battle.setBattle(fight);
            battle.setDeckMap(Map.of(1, List.of()));
            battle.setItemMap(Map.of());
            battle.setEscape(Set.of());
            battles.add(battle);
        }
        when(duration.getCondition(quest)).thenReturn(Optional.of(rows));
        var types = new StypeCollection();
        for (ShipType type : ShipType.values()) {
            var value = new Stype();
            value.setId(type.getStype());
            value.setName(type.name());
            types.getStypeMap().put(type.getStype(), value);
        }
        var masters = publishedMasters();
        try (var durations = mockStatic(AppQuestDuration.class);
                var maps = mockStatic(MapinfoMstCollection.class);
                var logs = mockStatic(BattleLogs.class);
                var masterAccess = mockStatic(ShipMstCollection.class);
                var typeAccess = mockStatic(StypeCollection.class)) {
            durations.when(AppQuestDuration::get).thenReturn(duration);
            maps.when(MapinfoMstCollection::get).thenReturn(new MapinfoMstCollection());
            masterAccess.when(ShipMstCollection::get).thenReturn(masters);
            typeAccess.when(StypeCollection::get).thenReturn(types);
            for (int i = 0; i < battles.size(); i++) {
                String key = "carrier-fixture-" + i;
                var battle = battles.get(i);
                logs.when(() -> BattleLogs.read(key)).thenReturn(battle);
            }
            return QuestCollect.collect(quest, condition);
        }
    }

    @Test
    void namedArmoredDemonsAreAlreadyExcludedByTheirActualMasterType() throws Exception {
        var masters = publishedMasters();
        assertEquals("装甲空母鬼", masters.getShipMap().get(1544).getName());
        assertEquals("装甲空母姫", masters.getShipMap().get(1545).getName());
        assertEquals(10, masters.getShipMap().get(1544).getStype());
        assertEquals(10, masters.getShipMap().get(1545).getStype());
        for (int questId : List.of(211, 220)) {
            int required = questId == 211 ? 3 : 20;
            var ids = new ArrayList<Integer>();
            for (int i = 0; i < required; i++)
                ids.add(i % 2 == 0 ? 1544 : 1545);
            var counted = collect(questId, ids, 0);
            assertEquals(required, counted.getStype().get("航空戦艦"));
            assertFalse(condition(questId).test(counted));
        }
    }

    @Test
    void knownLightAndRegularCarriersReachOnlyTheirActualThreshold() throws Exception {
        for (int questId : List.of(211, 220)) {
            int required = questId == 211 ? 3 : 20;
            var ids = new ArrayList<Integer>();
            for (int i = 0; i < required - 1; i++)
                ids.add(i % 2 == 0 ? 1510 : 1512);
            ids.add(1544);
            ids.add(1545);
            assertFalse(condition(questId).test(collect(questId, ids, 0)));
            ids.add(1512);
            assertTrue(condition(questId).test(collect(questId, ids, 0)));
        }
    }

    @Test
    void survivingCarriersAndMissingMastersDoNotAdvanceTheQuest() throws Exception {
        assertFalse(condition(211).test(collect(211, List.of(1510, 1512, 1512), 1)));
        assertFalse(condition(211).test(collect(211, List.of(999999, 999999, 999999), 0)));
    }
}
