package logbook.internal.calc;

import java.util.List;
import java.util.TreeSet;

/** Pure calculation model; no JavaFX, configuration, network or file access. */
public final class ProbabilityCalculator {
    private ProbabilityCalculator() {
    }

    public record Point(int count, double probability) {
    }

    /**
     * Retains the calculator's limit, step and 50/75/90/95-percent milestone
     * rows, including milestones beyond the limit. Invalid input has no rows.
     * Numerical formulas deliberately match the existing controller.
     */
    public static List<Point> calculate(double percent, int limit, int step) {
        if (!Double.isFinite(percent) || percent <= 0 || percent >= 100
                || limit <= 0 || step <= 0) {
            return List.of();
        }
        double p = percent / 100.0;
        TreeSet<Integer> counts = new TreeSet<>();
        counts.add(limit);
        // A long loop counter cannot wrap around at Integer.MAX_VALUE.
        for (long count = step; count <= limit; count += step) {
            counts.add((int) count);
        }
        for (double target : new double[] {0.5, 0.75, 0.9, 0.95}) {
            int count = (int) Math.ceil(Math.log(1.0 - target) / Math.log(1.0 - p));
            counts.add(count);
        }
        return counts.stream()
                .map(count -> new Point(count, 1.0 - Math.pow(1.0 - p, count)))
                .toList();
    }
}
