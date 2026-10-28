package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import logbook.plugin.api.QuestVisibilityV1;
import logbook.plugin.api.QuestVisibilityV1.*;
import org.junit.jupiter.api.Test;

/** Isolated sessions only: no live singleton, Config, filesystem or core init/close. */
class QuestVisibilityCommandTest {
    @Test void versionAndApiHaveNoLegacyBeanOrFilesystemParameters() {
        assertEquals(1,QuestVisibilityV1.version());
        for(var method:QuestVisibilityV1.class.getDeclaredMethods()) {
            assertFalse(method.toGenericString().contains("logbook.bean"));
            assertFalse(method.toGenericString().contains("java.nio.file"));
            assertFalse(method.toGenericString().contains("javafx"));
        }
    }
    @Test void freshSessionHasNoHiddenIds() {var s=new QuestVisibilityService.Session();assertEquals(new State(0,Set.of()),s.snapshot());}
    @Test void snapshotIsSortedAndDetached() {
        var input=new HashSet<>(Set.of(3,1));var state=new State(2,input);input.clear();
        assertEquals(List.of(1,3),List.copyOf(state.hiddenIds()));assertThrows(UnsupportedOperationException.class,()->state.hiddenIds().clear());
    }
    @Test void planCopiesItsTargets() {var ids=new HashSet<>(Set.of(1));var plan=new Plan(new State(0,Set.of()),Action.HIDE,ids);ids.clear();assertEquals(Set.of(1),plan.questIds());}
    @Test void invalidIdsAreRejectedBeforeChangingState() {
        var s=new QuestVisibilityService.Session();
        for(var ids:List.of(Arrays.asList((Integer)null),List.of(0),List.of(-1)))assertThrows(IllegalArgumentException.class,()->s.prepareHide(ids));
        assertEquals(0,s.snapshot().revision());
    }
    @Test void negativeRevisionAndNullActionAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new State(-1,Set.of()));
        assertThrows(NullPointerException.class,()->new Plan(new State(0,Set.of()),null,Set.of(1)));
    }
    @Test void cancelDoesNotChangeState() {
        var s=new QuestVisibilityService.Session();var before=s.snapshot();var r=s.apply(s.prepareHide(List.of(1)),false);
        assertEquals(Outcome.CANCELLED,r.outcome());assertSame(before,s.snapshot());assertFalse(r.accepted());
    }
    @Test void confirmedSingleHideIsReversible() {
        var s=new QuestVisibilityService.Session();var r=s.apply(s.prepareHide(List.of(10001)),true);
        assertTrue(r.accepted());assertEquals(Set.of(10001),r.state().hiddenIds());
        assertEquals(Outcome.APPLIED,s.apply(s.prepareRestoreAll(),true).outcome());assertTrue(s.snapshot().hiddenIds().isEmpty());
    }
    @Test void batchTargetsAreAppliedTogetherWithoutChangingInput() {
        var s=new QuestVisibilityService.Session();var ids=new ArrayList<>(List.of(3,1,3));var p=s.prepareHide(ids);
        var r=s.apply(p,true);assertEquals(Set.of(1,3),r.state().hiddenIds());assertEquals(List.of(3,1,3),ids);
    }
    @Test void stalePlanRejectsTheWholeBatch() {
        var s=new QuestVisibilityService.Session();var stale=s.prepareHide(List.of(1,2));
        s.apply(s.prepareHide(List.of(3)),true);var r=s.apply(stale,true);
        assertEquals(Outcome.CONFLICT,r.outcome());assertEquals(Set.of(3),s.snapshot().hiddenIds());
    }
    @Test void repeatedOldPlanCannotApplyTwice() {
        var s=new QuestVisibilityService.Session();var plan=s.prepareHide(List.of(1));s.apply(plan,true);
        assertEquals(Outcome.CONFLICT,s.apply(plan,true).outcome());assertEquals(1,s.snapshot().revision());
    }
    @Test void unchangedRequestDoesNotAdvanceRevision() {
        var s=new QuestVisibilityService.Session();s.apply(s.prepareHide(List.of(1)),true);var before=s.snapshot();
        assertEquals(Outcome.UNCHANGED,s.apply(s.prepareHide(List.of(1)),true).outcome());assertSame(before,s.snapshot());
    }
    @Test void cancellingStalePlanDoesNotConflictOrModifyAnything() {
        var s=new QuestVisibilityService.Session();var p=s.prepareHide(List.of(1));s.apply(s.prepareHide(List.of(2)),true);
        assertEquals(Outcome.CANCELLED,s.apply(p,false).outcome());assertEquals(Set.of(2),s.snapshot().hiddenIds());
    }
    @Test void restoreCannotUndoANewerHideWithoutReconfirmation() {
        var s=new QuestVisibilityService.Session();s.apply(s.prepareHide(List.of(1)),true);var old=s.prepareRestoreAll();
        s.apply(s.prepareHide(List.of(2)),true);assertEquals(Outcome.CONFLICT,s.apply(old,true).outcome());assertEquals(Set.of(1,2),s.snapshot().hiddenIds());
    }
    @Test void revisionPreventsAnAbaReuseAfterRestore() {
        var s=new QuestVisibilityService.Session();var initial=s.prepareHide(List.of(4));s.apply(initial,true);s.apply(s.prepareRestoreAll(),true);
        assertEquals(Outcome.CONFLICT,s.apply(initial,true).outcome());assertEquals(2,s.snapshot().revision());
    }
    @Test void oldSnapshotDoesNotChangeWithSession() {
        var s=new QuestVisibilityService.Session();var before=s.snapshot();s.apply(s.prepareHide(List.of(1)),true);assertTrue(before.hiddenIds().isEmpty());
    }
    @Test void emptyCommandsAreNoOps() {
        var s=new QuestVisibilityService.Session();assertEquals(Outcome.UNCHANGED,s.apply(s.prepareRestoreAll(),true).outcome());
        assertEquals(Outcome.UNCHANGED,s.apply(s.prepareHide(List.of()),true).outcome());assertEquals(0,s.snapshot().revision());
    }
    @Test void resetIsOnlyANewSessionNotAStateFile() {
        var s=new QuestVisibilityService.Session();s.apply(s.prepareHide(List.of(1)),true);
        assertTrue(new QuestVisibilityService.Session().snapshot().hiddenIds().isEmpty());assertEquals(Set.of(1),s.snapshot().hiddenIds());
    }
    @Test void oversizedPlanFailsClosed() {
        var s=new QuestVisibilityService.Session();var ids=java.util.stream.IntStream.rangeClosed(1,10001).boxed().toList();
        assertThrows(IllegalArgumentException.class,()->s.prepareHide(ids));assertTrue(s.snapshot().hiddenIds().isEmpty());
    }
    @Test void oversizedUnionDoesNotPartiallyCommit() {
        var s=new QuestVisibilityService.Session();s.apply(s.prepareHide(java.util.stream.IntStream.rangeClosed(1,10000).boxed().toList()),true);
        var before=s.snapshot();assertThrows(IllegalArgumentException.class,()->s.apply(s.prepareHide(List.of(10001)),true));assertSame(before,s.snapshot());
    }
    @Test void concurrentConfirmedPlansHaveExactlyOneWinner() throws Exception {
        var s=new QuestVisibilityService.Session();var first=s.prepareHide(List.of(1));var second=s.prepareHide(List.of(2));
        try(var executor=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var a=executor.submit(()->{start.await();return s.apply(first,true).outcome();});
            var b=executor.submit(()->{start.await();return s.apply(second,true).outcome();});start.countDown();
            assertEquals(Set.of(Outcome.APPLIED,Outcome.CONFLICT),Set.of(a.get(3,TimeUnit.SECONDS),b.get(3,TimeUnit.SECONDS)));
        }
        assertEquals(1,s.snapshot().hiddenIds().size());assertEquals(1,s.snapshot().revision());
    }
}
