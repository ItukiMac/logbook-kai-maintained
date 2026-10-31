package logbook.internal.calc;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class ProbabilityCalculatorTest {
    /** Independent transcription of the pre-extraction controller algorithm. */
    private static Map<Integer, Double> legacy(double percent, int limit, int step) {
        double p = percent / 100.0;
        List<ProbabilityCalculator.Point> rows = new ArrayList<>();
        rows.add(new ProbabilityCalculator.Point(limit, 1.0 - Math.pow(1.0 - p, limit)));
        if (step <= limit) {
            for (int count = step; count <= limit; count += step) {
                rows.add(new ProbabilityCalculator.Point(count, 1.0 - Math.pow(1.0 - p, count)));
            }
        }
        for (double target : new double[] { 0.5, 0.75, 0.9, 0.95 }) {
            int count = (int) Math.ceil(Math.log(1.0 - target) / Math.log(1.0 - p));
            rows.add(new ProbabilityCalculator.Point(count, 1.0 - Math.pow(1.0 - p, count)));
        }
        rows.sort(Comparator.comparingInt(ProbabilityCalculator.Point::count));
        Map<Integer, Double> result = new TreeMap<>();
        for (var row : rows)
            result.putIfAbsent(row.count(), row.probability());
        return result;
    }

    @Test
    void matchesLegacyFor180InputCombinations() {
        for (double p : new double[] { 0.01, 0.1, 1, 5, 25, 50, 75, 99, 99.99 }) {
            for (int limit : new int[] { 1, 5, 20, 100, 1000 }) {
                for (int step : new int[] { 1, 3, 20, 1001 }) {
                    Map<Integer, Double> expected = legacy(p, limit, step);
                    var actual = ProbabilityCalculator.calculate(p, limit, step);
                    assertEquals(expected.size(), actual.size());
                    for (var point : actual) {
                        assertEquals(Double.doubleToLongBits(expected.get(point.count())),
                                Double.doubleToLongBits(point.probability()));
                    }
                }
            }
        }
    }

    @Test
    void retainsMilestonesBeyondTheLimit() {
        assertTrue(ProbabilityCalculator.calculate(1, 10, 5).stream()
                .anyMatch(row -> row.count() > 10 && row.probability() >= 0.95));
    }

    @Test
    void stepAboveLimitStillIncludesLimitAndMilestones() {
        assertEquals(List.of(1, 2, 4, 5), ProbabilityCalculator.calculate(50, 2, 100)
                .stream().map(ProbabilityCalculator.Point::count).toList());
    }

    @Test
    void returnsSortedDistinctCounts() {
        var counts = ProbabilityCalculator.calculate(50, 10, 2).stream()
                .map(ProbabilityCalculator.Point::count).toList();
        assertEquals(counts.stream().distinct().sorted().toList(), counts);
        assertEquals(List.of(1, 2, 4, 5, 6, 8, 10), counts);
    }

    @Test
    void rejectsInvalidInputsWithoutRows() {
        for (double p : new double[] { -1, 0, 100, 101, Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY }) {
            assertTrue(ProbabilityCalculator.calculate(p, 10, 1).isEmpty());
        }
        assertTrue(ProbabilityCalculator.calculate(50, 0, 1).isEmpty());
        assertTrue(ProbabilityCalculator.calculate(50, 10, 0).isEmpty());
        assertTrue(ProbabilityCalculator.calculate(50, -1, 1).isEmpty());
        assertTrue(ProbabilityCalculator.calculate(50, 10, -1).isEmpty());
    }

    @Test
    void iterationDoesNotOverflowAtIntegerMaxValue() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            var rows = ProbabilityCalculator.calculate(50, Integer.MAX_VALUE, Integer.MAX_VALUE);
            assertEquals(5, rows.size());
            assertEquals(Integer.MAX_VALUE, rows.get(rows.size() - 1).count());
        });
    }

    @Test
    void returnedRowsAreImmutable() {
        assertThrows(UnsupportedOperationException.class, () -> ProbabilityCalculator.calculate(50, 10, 2).clear());
    }
}
