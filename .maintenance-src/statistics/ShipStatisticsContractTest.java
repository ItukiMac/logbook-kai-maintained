package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import logbook.bean.Ship;
import logbook.constants.ExpTable;
import logbook.constants.StatisticsShipTypeGroup;
import logbook.plugin.api.ShipStatisticsV1;
import org.junit.jupiter.api.Test;

class ShipStatisticsContractTest {
    static Ship ship(int id, Integer level, Integer experience, Boolean locked) {
        Ship s = new Ship(); s.setId(id); s.setLv(level); s.setLocked(locked);
        s.setExp(experience == null ? null : new ArrayList<>(List.of(experience, 10))); return s;
    }
    static ShipStatisticsV1.Snapshot snapshot(Ship... ships) {
        return ShipStatisticsService.capture(Arrays.asList(ships), s -> StatisticsShipTypeGroup.駆逐艦);
    }
    @Test void publicSignatureAndRecordsExposeNoMutableBeansOrJavafx() {
        assertEquals(1, ShipStatisticsV1.version());
        for (var method : ShipStatisticsV1.class.getDeclaredMethods()) {
            assertFalse(method.toGenericString().contains("logbook.bean"));
            assertFalse(method.toGenericString().contains("javafx"));
        }
        assertTrue(ShipStatisticsV1.Group.class.isRecord()); assertTrue(ShipStatisticsV1.Snapshot.class.isRecord());
    }
    @Test void onlyLockedShipsContribute() {
        var s = snapshot(ship(1, 10, 100, true), ship(2, 99, 9999, false));
        assertEquals(1, s.lockedShips()); assertEquals(100, s.recordedExperience()); assertEquals(10.0, s.groups().getFirst().average());
    }
    @Test void originalCollectionsAndBeansCanChangeAfterCapture() {
        var a = ship(1, 10, 100, true); var s = snapshot(a);
        a.setLv(99); a.getExp().set(0, 777); a.setLocked(false);
        assertEquals(10.0, s.groups().getFirst().average()); assertEquals(100, s.recordedExperience());
    }
    @Test void snapshotContainersAndHistogramAreImmutable() {
        var s = snapshot(ship(1, 10, 100, true));
        assertThrows(UnsupportedOperationException.class, () -> s.groups().clear());
        assertThrows(UnsupportedOperationException.class, () -> s.categories().clear());
        assertThrows(UnsupportedOperationException.class, () -> s.levelBands().clear());
        assertThrows(UnsupportedOperationException.class, () -> s.warnings().clear());
        assertThrows(UnsupportedOperationException.class, () -> s.groups().getFirst().histogram().clear());
    }
    @Test void medianAndMeanPreserveOddAndEvenRules() {
        var even = snapshot(ship(1, 10, 0, true), ship(2, 20, 0, true));
        assertEquals(15.0, even.groups().getFirst().average()); assertEquals(15.0, even.groups().getFirst().median());
        var odd = snapshot(ship(1, 10, 0, true), ship(2, 20, 0, true), ship(3, 99, 0, true));
        assertEquals(43.0, odd.groups().getFirst().average()); assertEquals(20.0, odd.groups().getFirst().median());
    }
    @Test void ninetyNineIsItsOwnBand() {
        var s = snapshot(ship(1, 90, 0, true), ship(2, 98, 0, true), ship(3, 99, 0, true), ship(4, 100, 0, true));
        assertEquals(Map.of("90-98", 2L, "99", 1L, "100-109", 1L), s.groups().getFirst().histogram());
        assertFalse(s.levelBands().contains("90-99")); assertTrue(s.levelBands().contains("99"));
    }
    @Test void allBoundaryLevelsAppearExactlyOnce() {
        List<Ship> ships = new ArrayList<>();
        for (int i = 1; i <= ExpTable.MAX_LEVEL; i++) ships.add(ship(i, i, i, true));
        var s = ShipStatisticsService.capture(ships, v -> StatisticsShipTypeGroup.駆逐艦);
        assertEquals(ExpTable.MAX_LEVEL, s.groups().getFirst().histogram().values().stream().mapToLong(Long::longValue).sum());
        assertEquals((ExpTable.MAX_LEVEL + 1) / 2.0, s.groups().getFirst().average());
    }
    @Test void missingExperienceIsNotConvertedToZeroEvidence() {
        var s = snapshot(ship(1, 10, null, true), ship(2, 20, 0, true));
        assertEquals(1, s.missingExperience()); assertEquals(1, s.groups().getFirst().recordedExperienceShips());
        assertEquals(0, s.recordedExperience()); assertFalse(s.warnings().isEmpty());
    }
    @Test void emptyAndNegativeExperienceAreReported() {
        var a = ship(1, 10, 0, true); a.setExp(List.of());
        var s = snapshot(a, ship(2, 20, -1, true));
        assertEquals(2, s.missingExperience()); assertEquals(0, s.groups().getFirst().recordedExperienceShips());
    }
    @Test void nullFirstExperienceIsReported() {
        var a = ship(1, 10, 0, true); a.setExp(Arrays.asList(null, 10)); assertEquals(1, snapshot(a).missingExperience());
    }
    @Test void missingLevelDoesNotBecomeLevelZero() {
        var s = snapshot(ship(1, null, 100, true)); var g = s.groups().getFirst();
        assertNull(g.average()); assertNull(g.median()); assertTrue(g.histogram().isEmpty());
        assertEquals(1, s.missingLevel()); assertEquals(100, s.recordedExperience());
    }
    @Test void invalidLevelsAreVisibleAsUncertain() {
        var s = snapshot(ship(1, 0, 1, true), ship(2, ExpTable.MAX_LEVEL + 1, 2, true));
        assertEquals(2, s.missingLevel()); assertTrue(s.groups().getFirst().histogram().isEmpty());
    }
    @Test void unknownClassificationGetsExplicitGroupRatherThanCrashing() {
        var s = ShipStatisticsService.capture(List.of(ship(1, 10, 100, true)), v -> null);
        assertEquals(1, s.unclassifiedShips()); assertEquals("未分類", s.groups().getFirst().name());
        assertEquals(100, s.recordedExperience()); assertEquals(100, s.groups().getFirst().experience());
    }
    @Test void categoryOrderPreservesExistingGroupOrder() {
        var s = ShipStatisticsService.capture(List.of(ship(1, 10, 100, true), ship(2, 20, 200, true)),
                v -> v.getId() == 1 ? StatisticsShipTypeGroup.特殊艦 : StatisticsShipTypeGroup.駆逐艦);
        assertEquals(List.of("駆逐艦", "特殊艦"), s.groups().stream().map(ShipStatisticsV1.Group::name).toList());
    }
    @Test void nullShipAndUnknownLockAreNotAssumedOwnedAndLocked() {
        var s = snapshot(null, ship(1, 10, 100, null), ship(2, 20, 200, true));
        assertEquals(2, s.skippedRecords()); assertEquals(1, s.lockedShips()); assertEquals(200, s.recordedExperience());
    }
    @Test void totalUsesLongWithoutIntegerOverflow() {
        var s = snapshot(ship(1, 10, Integer.MAX_VALUE, true), ship(2, 20, Integer.MAX_VALUE, true));
        assertEquals(2L * Integer.MAX_VALUE, s.recordedExperience());
    }
    @Test void emptyRosterKeepsCategoriesButContainsNoInventedShips() {
        var s = snapshot(); assertEquals(0, s.lockedShips()); assertTrue(s.groups().isEmpty()); assertTrue(s.warnings().isEmpty());
        assertEquals(StatisticsShipTypeGroup.values().length, s.categories().size());
    }
    @Test void formattingRetainsSuffixThresholdsAndHalfEvenRounding() {
        assertEquals("0.0K", ShipStatisticsV1.formatExperience(0));
        assertEquals("1.2K", ShipStatisticsV1.formatExperience(1250));
        assertEquals("1.4K", ShipStatisticsV1.formatExperience(1350));
        assertEquals("1.00M", ShipStatisticsV1.formatExperience(1_000_000));
        assertEquals("1.000G", ShipStatisticsV1.formatExperience(1_000_000_000));
        assertThrows(IllegalArgumentException.class, () -> ShipStatisticsV1.formatExperience(-1));
    }
    @Test void directRecordConstructionDefensivelyCopies() {
        Map<String, Long> histogram = new LinkedHashMap<>(Map.of("10-19", 1L));
        var group = new ShipStatisticsV1.Group("g", 1, 1, 1, 1, 10.0, 10.0, histogram);
        List<ShipStatisticsV1.Group> groups = new ArrayList<>(List.of(group));
        var s = new ShipStatisticsV1.Snapshot(List.of("g"), List.of("10-19"), groups, 1, 1, 0, 0, 0, 0, List.of());
        histogram.clear(); groups.clear(); assertEquals(1, s.groups().size()); assertEquals(1L, group.histogram().get("10-19"));
    }
    @Test void completeFixtureMatchesTheFormerChartFormulae() {
        List<Ship> ships = List.of(ship(1, 10, 100, true), ship(2, 98, 200, true), ship(3, 99, 300, true), ship(4, 100, 400, true));
        var s = ShipStatisticsService.capture(ships, v -> StatisticsShipTypeGroup.駆逐艦);
        var g = s.groups().getFirst();
        assertEquals(ships.stream().mapToLong(v -> v.getExp().getFirst()).sum(), s.recordedExperience());
        assertEquals(ships.stream().mapToInt(Ship::getLv).average().orElseThrow(), g.average());
        assertEquals(98.5, g.median()); assertEquals(4, g.recordedLevels()); assertTrue(s.warnings().isEmpty());
    }
}
