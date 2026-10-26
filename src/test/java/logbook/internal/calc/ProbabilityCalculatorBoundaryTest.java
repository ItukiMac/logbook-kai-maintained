package logbook.internal.calc;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ProbabilityCalculatorBoundaryTest {
    @Test void rejectsBillionsOfRowsBeforeAllocation() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
            assertTrue(ProbabilityCalculator.calculate(1, Integer.MAX_VALUE, 1).isEmpty()));
    }
    @Test void outputBudgetBoundaryIsExplicit() {
        assertFalse(ProbabilityCalculator.calculate(1, ProbabilityCalculator.MAX_ROWS - 5, 1).isEmpty());
        assertTrue(ProbabilityCalculator.calculate(1, ProbabilityCalculator.MAX_ROWS - 4, 1).isEmpty());
    }
    @Test void largeLimitWithLargeStepIsStillAllowed() {
        assertFalse(ProbabilityCalculator.calculate(1, Integer.MAX_VALUE, Integer.MAX_VALUE).isEmpty());
    }
    @Test void tinyProbabilityDoesNotRoundToZero() {
        var rows = ProbabilityCalculator.calculate(1e-14, 1, 1);
        assertEquals(1, rows.size());
        assertEquals(1, rows.get(0).count());
        assertEquals(1e-16, rows.get(0).probability(), 1e-30);
    }
    @Test void unrepresentableMilestonesAreNotClampedIntoFakeRows() {
        var rows = ProbabilityCalculator.calculate(1e-10, 100, 100);
        assertEquals(1, rows.size());
        assertEquals(100, rows.get(0).count());
        assertTrue(rows.get(0).probability() > 0);
        assertTrue(rows.get(0).probability() < 1e-8);
    }
    @Test void underflowInputReturnsNoRows() {
        assertTrue(ProbabilityCalculator.calculate(Double.MIN_VALUE, 1, 1).isEmpty());
    }
    @Test void probabilitiesAreFiniteMonotoneAndWithinUnitInterval() {
        for (double percent : new double[] {1e-14, 1e-7, 0.01, 50, 99.99}) {
            double previous = 0;
            for (var row : ProbabilityCalculator.calculate(percent, 1000, 1)) {
                assertTrue(Double.isFinite(row.probability()));
                assertTrue(row.probability() >= previous && row.probability() <= 1);
                previous = row.probability();
            }
        }
    }
}
