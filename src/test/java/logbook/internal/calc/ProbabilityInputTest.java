package logbook.internal.calc;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ProbabilityInputTest {
    @Test
    void validInputKeepsCalculatorResultsAndAcceptsOuterWhitespace() {
        var result = ProbabilityInput.evaluate(" 1 ", "100", "10");
        assertTrue(result.valid());
        assertEquals(ProbabilityCalculator.calculate(1, 100, 10), result.points());
        assertThrows(UnsupportedOperationException.class, () -> result.points().clear());
    }

    @Test
    void incompleteAndMalformedFieldsHaveDifferentMessages() {
        assertTrue(ProbabilityInput.evaluate("", "100", "10").message().contains("すべて"));
        assertTrue(ProbabilityInput.evaluate("abc", "100", "10").message().contains("確率は数値"));
        assertTrue(ProbabilityInput.evaluate("1", "1.5", "10").message().contains("整数"));
    }

    @Test
    void allModelRejectionsAreExplained() {
        for (String p : new String[] { "NaN", "Infinity", "0", "100", "-1" }) {
            var result = ProbabilityInput.evaluate(p, "100", "10");
            assertFalse(result.valid());
            assertTrue(result.points().isEmpty());
        }
        assertTrue(ProbabilityInput.evaluate("1", "100", "0").message().contains("1以上"));
        assertTrue(ProbabilityInput.evaluate("4.9e-324", "100", "10").message().contains("小さすぎ"));
    }

    @Test
    void workBudgetRejectsBeforeGenerationAndCanBeCorrected() {
        assertTrue(ProbabilityInput.evaluate("1", "2147483647", "1").message().contains("10000行"));
        assertTrue(ProbabilityInput.evaluate("1", "2147483647", "2147483647").valid());
    }

    @Test
    void overflowingIntegersAreNotSilentlyClamped() {
        assertFalse(ProbabilityInput.evaluate("1", "2147483648", "1").valid());
        assertFalse(ProbabilityInput.evaluate("1", "100", "99999999999999999999").valid());
    }
}
