package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import logbook.bean.Ship;
import logbook.plugin.api.ShipLabelsV1;
import logbook.plugin.api.ShipLabelsV1.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests the real command implementation on synthetic maps, never the default Config. */
class ShipLabelCommandTest {
    Map<Integer, Ship> ships;
    Map<Integer, Set<String>> labels;

    @BeforeEach
    void prepare() {
        ships = new LinkedHashMap<>();
        labels = new LinkedHashMap<>();
        for (int id : List.of(1, 2)) {
            Ship ship = new Ship();
            ship.setId(id);
            ship.setShipId(100 + id);
            ship.setSallyArea(7);
            ships.put(id, ship);
        }
        labels.put(1, new LinkedHashSet<>(List.of("first", "keep")));
        labels.put(2, new LinkedHashSet<>(List.of("other")));
    }

    List<Target> capture(Integer... ids) {
        return ShipLabelService.captureFrom(ships, labels, List.of(ids));
    }

    Result apply(List<Target> target, String label, boolean add) {
        return ShipLabelService.applyTo(ships, labels, target, label, add, ship -> "event-tag");
    }

    @Test
    void publicContractsContainNoMutableBeansOrJavafx() {
        assertEquals(1, ShipLabelsV1.version());
        for (var method : ShipLabelsV1.class.getDeclaredMethods()) {
            assertFalse(method.toGenericString().contains("logbook.bean"));
            assertFalse(method.toGenericString().contains("javafx"));
        }
    }

    @Test
    void captureAndNestedLabelsAreImmutableCopies() {
        var targets = capture(1, 2);
        labels.get(1).add("later");
        assertFalse(targets.getFirst().expectedLabels().contains("later"));
        assertThrows(UnsupportedOperationException.class, targets::clear);
        assertThrows(UnsupportedOperationException.class, () -> targets.getFirst().expectedLabels().clear());
    }

    @Test
    void confirmedAddPreservesOrderAndUnrelatedLabels() {
        Set<String> old = labels.get(1);
        Set<String> unrelated = labels.get(2);
        var result = apply(capture(1), "new", true);
        assertEquals(Outcome.APPLIED, result.outcome());
        assertEquals(Set.of(1), result.changedIds());
        assertEquals(List.of("first", "keep", "new"), new ArrayList<>(labels.get(1)));
        assertEquals(Set.of("first", "keep"), old);
        assertSame(unrelated, labels.get(2));
        assertEquals(List.of("event-tag", "first", "keep", "new"), new ArrayList<>(result.displayLabels().get(1)));
    }

    @Test
    void alreadyPresentLabelIsUnchanged() {
        Set<String> original = labels.get(1);
        var result = apply(capture(1), "first", true);
        assertEquals(Outcome.UNCHANGED, result.outcome());
        assertTrue(result.accepted());
        assertSame(original, labels.get(1));
    }

    @Test
    void removeOnlyTheRequestedManualLabel() {
        var result = apply(capture(1), "first", false);
        assertEquals(Set.of("keep"), labels.get(1));
        assertEquals(Set.of("event-tag", "keep"), result.displayLabels().get(1));
        assertEquals(7, ships.get(1).getSallyArea());
    }

    @Test
    void lastManualLabelRemovesOnlyItsEmptyMapEntry() {
        var result = apply(capture(2), "other", false);
        assertFalse(labels.containsKey(2));
        assertEquals(Set.of("event-tag"), result.displayLabels().get(2));
        assertTrue(labels.containsKey(1));
    }

    @Test
    void eventTagIsNeverWrittenToOrRemovedFromGameState() {
        var result = apply(capture(1), "event-tag", false);
        assertEquals(Outcome.UNCHANGED, result.outcome());
        assertEquals(7, ships.get(1).getSallyArea());
        assertTrue(result.displayLabels().get(1).contains("event-tag"));
        assertFalse(labels.get(1).contains("event-tag"));
    }

    @Test
    void manualNameEqualToEventTagRemainsVisibleAfterManualRemoval() {
        labels.get(1).add("event-tag");
        var result = apply(capture(1), "event-tag", false);
        assertFalse(labels.get(1).contains("event-tag"));
        assertTrue(result.displayLabels().get(1).contains("event-tag"));
    }

    @Test
    void conflictInLastTargetPreventsAllChanges() {
        var targets = capture(1, 2);
        Set<String> first = labels.get(1);
        labels.get(2).add("concurrent");
        var result = apply(targets, "new", true);
        assertEquals(Outcome.CONFLICT, result.outcome());
        assertFalse(result.accepted());
        assertSame(first, labels.get(1));
        assertFalse(labels.get(1).contains("new"));
        assertFalse(labels.get(2).contains("new"));
        assertTrue(result.displayLabels().isEmpty());
    }

    @Test
    void removedShipPreventsWholeBatch() {
        var targets = capture(1, 2);
        ships.remove(2);
        assertEquals(Outcome.MISSING_SHIP, apply(targets, "new", true).outcome());
        assertFalse(labels.get(1).contains("new"));
    }

    @Test
    void sameInstanceIdWithDifferentMasterIsRejected() {
        var targets = capture(1);
        ships.get(1).setShipId(777);
        assertEquals(Outcome.MISSING_SHIP, apply(targets, "new", true).outcome());
        assertEquals(Set.of("first", "keep"), labels.get(1));
    }

    @Test
    void malformedStoredLabelPreventsAllChanges() {
        var targets = capture(1, 2);
        labels.get(2).add(null);
        assertEquals(Outcome.INVALID_STATE, apply(targets, "new", true).outcome());
        assertFalse(labels.get(1).contains("new"));
    }

    @Test
    void duplicateSelectionsRejectWithoutPartialWrite() {
        var target = capture(1).getFirst();
        assertThrows(IllegalArgumentException.class, () -> apply(List.of(target, target), "new", true));
        assertEquals(Set.of("first", "keep"), labels.get(1));
    }

    @Test
    void emptySelectionDoesNotChangeAnyState() {
        var before = new LinkedHashMap<>(labels);
        assertEquals(Outcome.UNCHANGED, apply(List.of(), "new", true).outcome());
        assertEquals(before, labels);
    }

    @Test
    void whitespaceInAConfirmedLabelIsNotSilentlyNormalized() {
        apply(capture(1), "  spaced  ", true);
        assertTrue(labels.get(1).contains("  spaced  "));
        apply(capture(1), "  spaced  ", false);
        assertFalse(labels.get(1).contains("  spaced  "));
    }

    @Test
    void invalidEmptyLabelDoesNotMutateState() {
        assertThrows(IllegalArgumentException.class, () -> apply(capture(1), "", true));
        assertEquals(Set.of("first", "keep"), labels.get(1));
    }

    @Test
    void absentLabelsCanBeAddedAndRemovedWithoutNullContainers() {
        labels.remove(1);
        apply(capture(1), "added", true);
        assertEquals(Set.of("added"), labels.get(1));
        apply(capture(1), "added", false);
        assertFalse(labels.containsKey(1));
    }

    @Test
    void resultCopiesAreDeeplyImmutable() {
        var result = apply(capture(1), "new", true);
        assertThrows(UnsupportedOperationException.class, () -> result.changedIds().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.displayLabels().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.displayLabels().get(1).clear());
        labels.get(1).add("later");
        assertFalse(result.displayLabels().get(1).contains("later"));
    }

    @Test
    void invalidCaptureRejectsDuplicateMissingAndMismatchedIds() {
        assertThrows(IllegalArgumentException.class, () -> capture(1, 1));
        assertThrows(IllegalStateException.class, () -> capture(99));
        ships.get(1).setId(2);
        assertThrows(IllegalStateException.class, () -> capture(1));
    }

    @Test
    void immutableTargetRejectsInvalidIdentitiesAndNullLabels() {
        assertThrows(IllegalArgumentException.class, () -> new Target(0, 101, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new Target(1, 0, Set.of()));
        assertThrows(NullPointerException.class,
                () -> new Target(1, 101, new LinkedHashSet<>(Arrays.asList("ok", null))));
    }

    @Test
    void displayProjectionFailureDoesNotCommitAValidatedPrefix() {
        var targets = capture(1, 2);
        assertThrows(IllegalStateException.class, () -> ShipLabelService.applyTo(ships, labels, targets, "new", true,
                ship -> {
                    if (ship.getId() == 2)
                        throw new IllegalStateException("synthetic");
                    return "event";
                }));
        assertFalse(labels.get(1).contains("new"));
        assertFalse(labels.get(2).contains("new"));
    }

    @Test
    void concurrentCooperatingCommandsCannotOverwriteEachOther() throws Exception {
        var targets = capture(1);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> {
                start.await();
                return apply(targets, "a", true);
            });
            var b = pool.submit(() -> {
                start.await();
                return apply(targets, "b", true);
            });
            start.countDown();
            var outcomes = List.of(a.get(5, TimeUnit.SECONDS).outcome(), b.get(5, TimeUnit.SECONDS).outcome());
            assertTrue(outcomes.contains(Outcome.APPLIED));
            assertTrue(outcomes.contains(Outcome.CONFLICT));
            assertEquals(3, labels.get(1).size());
        }
    }
}
