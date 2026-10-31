package logbook.internal.kancolle;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashSet;
import java.util.Set;
import logbook.bean.*;
import logbook.bean.AppQuestCondition.FilterCondition;
import org.junit.jupiter.api.Test;

class QuestBattleRequirementsTest {
    private static FilterCondition restricted() {
        var filter = new FilterCondition();
        filter.setDeckIds(Set.of(1));
        return filter;
    }

    private static BattleLog log(Integer deck) {
        var log = new BattleLog();
        var day = new SortieBattle();
        day.setDockId(deck);
        log.setBattle(day);
        log.setEscape(Set.of());
        return log;
    }

    @Test
    void onlyTheRecordedFirstFleetMatches() {
        for (int n = 1; n <= 4; n++)
            assertEquals(n == 1, QuestBattleRequirements.matches(restricted(), log(n)));
    }

    @Test
    void missingAndInvalidDeckCannotDefaultToFirstFleet() {
        assertFalse(QuestBattleRequirements.matches(restricted(), null));
        for (Integer n : new Integer[] { null, 0, -1, 5 }) {
            var log = log(n);
            log.setDeckMap(java.util.Map.of(1, java.util.List.of()));
            assertFalse(QuestBattleRequirements.matches(restricted(), log));
        }
    }

    @Test
    void nightOnlyRecordWorksButContradictoryMetadataDoesNot() {
        var log = log(null);
        log.setBattle(null);
        var night = new BattleMidnightBattle();
        night.setDockId(1);
        log.setMidnight(night);
        assertTrue(QuestBattleRequirements.matches(restricted(), log));
        log.setBattle(log(2).getBattle());
        assertFalse(QuestBattleRequirements.matches(restricted(), log));
    }

    @Test
    void retreatGuardRequiresAnExplicitEmptySnapshot() {
        var filter = new FilterCondition();
        filter.setNoRetreat(true);
        var log = log(2);
        assertTrue(QuestBattleRequirements.matches(filter, log));
        log.setEscape(Set.of(42));
        assertFalse(QuestBattleRequirements.matches(filter, log));
        log.setEscape(null);
        assertFalse(QuestBattleRequirements.matches(filter, log));
    }

    @Test
    void guardsCombineWithAndAndNeverReadTheLiveRetreatState() {
        var filter = restricted();
        filter.setNoRetreat(true);
        assertTrue(QuestBattleRequirements.matches(filter, log(1)));
        assertFalse(QuestBattleRequirements.matches(filter, log(2)));
        var retired = log(1);
        retired.setEscape(Set.of(42));
        assertFalse(QuestBattleRequirements.matches(filter, retired));
    }

    @Test
    void legacyDefinitionsDoNotRequireAdditionalLogData() {
        var filter = new FilterCondition();
        assertFalse(QuestBattleRequirements.needsLog(filter));
        assertTrue(QuestBattleRequirements.matches(filter, null));
        filter.setNoRetreat(false);
        assertTrue(QuestBattleRequirements.matches(filter, null));
        assertTrue(QuestBattleRequirements.matches(null, null));
    }

    @Test
    void invalidDirectlyConstructedAllowListsFailClosed() {
        var filter = restricted();
        for (Set<Integer> ids : java.util.List.of(Set.<Integer> of(), Set.of(0, 1), Set.of(1, 5))) {
            filter.setDeckIds(ids);
            assertFalse(QuestBattleRequirements.matches(filter, log(1)));
        }
        var withNull = new HashSet<Integer>();
        withNull.add(null);
        withNull.add(1);
        filter.setDeckIds(withNull);
        assertFalse(QuestBattleRequirements.matches(filter, log(1)));
    }

    @Test
    void checkingTheLogDoesNotMutateAnySnapshot() {
        var log = log(1);
        var escape = new HashSet<Integer>();
        log.setEscape(escape);
        var filter = restricted();
        filter.setNoRetreat(true);
        assertTrue(QuestBattleRequirements.matches(filter, log));
        assertSame(escape, log.getEscape());
        assertTrue(escape.isEmpty());
        assertEquals(1, log.getBattle().getDockId());
    }
}
