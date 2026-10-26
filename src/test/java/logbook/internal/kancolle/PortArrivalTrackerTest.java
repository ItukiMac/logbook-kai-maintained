package logbook.internal.kancolle;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import logbook.bean.MapStartNext;
import org.junit.jupiter.api.Test;

class PortArrivalTrackerTest {
    private static final long T = 1_700_000_000_000L;
    private static final List<Integer> IDS = List.of(11, 12), MST = List.of(101, 102);
    private static MapStartNext node(int event, int cell) {
        var n = new MapStartNext(); n.setMapareaId(1); n.setMapinfoNo(6); n.setEventId(event); n.setNo(cell); return n;
    }
    private PortArrivalTracker started() {
        var t = new PortArrivalTracker(); t.start(node(4, 2), 1, IDS, MST, T); return t;
    }
    @Test void observedGoalIsNotThePrecedingBattleAndCountsOnce() {
        var t = started(); assertTrue(t.next(node(4, 4), 1, IDS, MST, true, T + 1).isEmpty());
        var result = t.next(node(9, 0), 1, IDS, MST, true, T + 2).orElseThrow();
        assertEquals(MST, result.shipMasterIds()); assertEquals("1-6", result.map()); assertTrue(result.noRetreat());
        assertTrue(t.next(node(9, 0), 1, IDS, MST, true, T + 3).isEmpty());
    }
    @Test void goalWithoutObservedStartCannotReconstructProgress() {
        var t = new PortArrivalTracker(); assertTrue(t.next(node(9, 0), 1, IDS, MST, true, T).isEmpty());
    }
    @Test void portOrExplicitResetCancelsIncompleteSortie() {
        var t = started(); t.clear(); assertTrue(t.next(node(9, 0), 1, IDS, MST, true, T + 1).isEmpty());
    }
    @Test void wrongMapInvalidatesRatherThanWaitingForALaterGoal() {
        var t = started(); var wrong = node(4, 5); wrong.setMapinfoNo(5);
        assertTrue(t.next(wrong, 1, IDS, MST, true, T + 1).isEmpty());
        assertTrue(t.next(node(9, 0), 1, IDS, MST, true, T + 2).isEmpty());
    }
    @Test void missingOrReorderedFleetEvidenceCannotBePromoted() {
        for (var changed : List.of(List.<Integer>of(), List.of(12, 11), List.of(11))) {
            var t = started(); assertTrue(t.next(node(9, 0), 1, changed, MST, true, T + 1).isEmpty());
        }
    }
    @Test void wrongFleetOrChangedMasterCannotCompleteSession() {
        assertTrue(started().next(node(9, 0), 2, IDS, MST, true, T + 1).isEmpty());
        assertTrue(started().next(node(9, 0), 1, IDS, List.of(201, 102), true, T + 1).isEmpty());
    }
    @Test void observedPredecessorMismatchCancelsSession() {
        var next = node(9, 0); next.setFromNo(9);
        assertTrue(started().next(next, 1, IDS, MST, true, T + 1).isEmpty());
    }
    @Test void clockReversalOrVeryOldSessionCannotCount() {
        assertTrue(started().next(node(9, 0), 1, IDS, MST, true, T - 1).isEmpty());
        assertTrue(started().next(node(9, 0), 1, IDS, MST, true, T + 86_400_001L).isEmpty());
    }
    @Test void newSortieCreatesANewIndependentIdentity() {
        var t = started(); var first = t.next(node(9, 0), 1, IDS, MST, false, T + 1).orElseThrow();
        t.start(node(4, 2), 1, IDS, MST, T + 2);
        var second = t.next(node(9, 0), 1, IDS, MST, true, T + 3).orElseThrow();
        assertNotEquals(first.sortieId(), second.sortieId()); assertFalse(first.noRetreat());
    }
    @Test void goalIsNeverAcceptedAsTheStartingNode() {
        var t = new PortArrivalTracker(); t.start(node(9, 0), 1, IDS, MST, T);
        assertTrue(t.next(node(9, 0), 1, IDS, MST, true, T + 1).isEmpty());
    }
    @Test void malformedOrDuplicateFleetStartFailsClosed() {
        var t = new PortArrivalTracker(); t.start(node(4, 1), 1, List.of(11, 11), MST, T);
        assertTrue(t.next(node(9, 0), 1, IDS, MST, true, T + 1).isEmpty());
        t.start(node(4, 1), 0, IDS, MST, T); assertTrue(t.next(node(9, 0), 1, IDS, MST, true, T + 1).isEmpty());
    }
}
