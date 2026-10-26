package logbook.internal.calc;

import java.util.List;
import java.util.TreeSet;

/** Pure calculation model; no JavaFX, configuration, network or file access. */
public final class ProbabilityCalculator {
    /** Bound work on the JavaFX event thread, before allocating output rows. */
    public static final int MAX_ROWS = 10000;

    private ProbabilityCalculator() {
    }

    public record Point(int count, double probability) {
    }

    /**
     * Limit/step rows and representable 50/75/90/95-percent milestones.
     * Invalid or excessive input returns no rows, never a truncated table.
     * Ordinary inputs retain the legacy numerical results bit for bit.
     * For tiny probabilities use cancellation-safe log1p/expm1 instead.
     * Milestones above Integer.MAX_VALUE are omitted, not falsely clamped.
     */
    public static List<Point> calculate(double percent, int limit, int step) {
        if (!Double.isFinite(percent) || percent <= 0 || percent >= 100
                || limit <= 0 || step <= 0
                || (long) limit / step + 5 > MAX_ROWS) {
            return List.of();
        }
        double p = percent / 100.0;
        if (p == 0) return List.of(); // division can underflow for subnormal input
        boolean tiny = p < 1e-8;
        double logFailure = tiny ? Math.log1p(-p) : Math.log(1.0 - p);
        TreeSet<Integer> counts = new TreeSet<>();
        counts.add(limit);
        for (long count = step; count <= limit; count += step) {
            counts.add((int) count);
        }
        for (double target : new double[] {0.5, 0.75, 0.9, 0.95}) {
            double count = Math.ceil(Math.log1p(-target) / logFailure);
            if (Double.isFinite(count) && count >= 1 && count <= Integer.MAX_VALUE) {
                counts.add((int) count);
            }
        }
        return counts.stream()
                .map(count -> new Point(count, tiny
                        ? -Math.expm1(count * logFailure)
                        : 1.0 - Math.pow(1.0 - p, count)))
                .toList();
    }
}
