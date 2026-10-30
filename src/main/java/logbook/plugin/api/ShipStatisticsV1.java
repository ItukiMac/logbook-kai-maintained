package logbook.plugin.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import logbook.core.ShipStatisticsService;

/**
 * Read-only statistics of locked ships. Results are detached and deeply immutable.
 * Host API and local-command writers share the capture lock; uncooperative legacy plugins do not. This is not an
 * atomic game-state snapshot, a new game rule, or an independent-core ABI promise.
 */
public final class ShipStatisticsV1 {
    private ShipStatisticsV1() { }
    public static int version() { return 1; }
    public static Snapshot snapshot() { return ShipStatisticsService.snapshot(); }

    public record Group(String name, int ships, int recordedLevels, int recordedExperienceShips,
            long experience, Double average, Double median, Map<String, Long> histogram) {
        public Group { histogram = Collections.unmodifiableMap(new LinkedHashMap<>(histogram)); }
    }
    public record Snapshot(List<String> categories, List<String> levelBands, List<Group> groups,
            long recordedExperience, int lockedShips, int missingExperience, int missingLevel,
            int unclassifiedShips, int skippedRecords, List<String> warnings) {
        public Snapshot {
            categories = List.copyOf(categories);
            levelBands = List.copyOf(levelBands);
            groups = List.copyOf(groups);
            warnings = List.copyOf(warnings);
        }
    }

    /** Preserve the existing K/M/G display and HALF_EVEN rounding. No unknown-to-zero conversion. */
    public static String formatExperience(long value) {
        if (value < 0) throw new IllegalArgumentException("Negative experience");
        long divisor = value >= 1_000_000_000L ? 1_000_000_000L : value >= 1_000_000L ? 1_000_000L : 1_000L;
        int scale = divisor == 1_000_000_000L ? 3 : divisor == 1_000_000L ? 2 : 1;
        String suffix = divisor == 1_000_000_000L ? "G" : divisor == 1_000_000L ? "M" : "K";
        return BigDecimal.valueOf(value).divide(BigDecimal.valueOf(divisor), scale, RoundingMode.HALF_EVEN)
                .toPlainString() + suffix;
    }
}
