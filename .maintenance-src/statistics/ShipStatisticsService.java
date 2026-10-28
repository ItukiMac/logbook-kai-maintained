package logbook.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.IntStream;
import logbook.bean.Ship;
import logbook.bean.ShipCollection;
import logbook.constants.ExpTable;
import logbook.constants.StatisticsShipTypeGroup;
import logbook.plugin.api.ShipStatisticsV1.Group;
import logbook.plugin.api.ShipStatisticsV1.Snapshot;

/** Host-only statistics. Capture scalar values once; every chart consumes the same result. */
public final class ShipStatisticsService {
    private static final String UNKNOWN_GROUP = "未分類";
    private ShipStatisticsService() { }

    public static Snapshot snapshot() {
        return capture(ShipCollection.get().getShipMap().values(), StatisticsShipTypeGroup::toTypeGroup);
    }

    // Synthetic tests use the real aggregation without creating the user's default Config.
    static Snapshot capture(Collection<Ship> source, Function<Ship, StatisticsShipTypeGroup> classify) {
        List<String> categories = new ArrayList<>(Arrays.stream(StatisticsShipTypeGroup.values())
                .map(StatisticsShipTypeGroup::name).toList());
        List<String> bands = IntStream.rangeClosed(1, ExpTable.MAX_LEVEL)
                .mapToObj(ShipStatisticsService::band).distinct().toList();
        Map<String, List<Observation>> values = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        int included = 0, missingExp = 0, missingLevel = 0, unclassified = 0, skipped = 0;
        long total = 0;
        for (Ship ship : new ArrayList<>(source)) {
            if (ship == null || ship.getLocked() == null) {
                skipped++; warnings.add("Ship or lock state unavailable"); continue;
            }
            if (!Boolean.TRUE.equals(ship.getLocked())) continue;
            included++;
            Integer level = ship.getLv();
            if (level == null || level < 1 || level > ExpTable.MAX_LEVEL) {
                missingLevel++; level = null; warnings.add("Level unavailable: " + ship.getId());
            }
            List<Integer> exp = ship.getExp();
            Integer experience = exp == null || exp.isEmpty() ? null : exp.getFirst();
            if (experience == null || experience < 0) {
                missingExp++; experience = null; warnings.add("Experience unavailable: " + ship.getId());
            } else total = Math.addExact(total, experience.longValue());
            StatisticsShipTypeGroup type = classify.apply(ship);
            String group = type == null ? UNKNOWN_GROUP : type.name();
            if (type == null) {
                unclassified++; warnings.add("Ship classification unavailable: " + ship.getId());
                if (!categories.contains(UNKNOWN_GROUP)) categories.add(UNKNOWN_GROUP);
            }
            values.computeIfAbsent(group, ignored -> new ArrayList<>()).add(new Observation(level, experience));
        }
        List<Group> groups = new ArrayList<>();
        for (String name : categories) {
            List<Observation> rows = values.get(name);
            if (rows == null) continue;
            List<Integer> levels = rows.stream().map(Observation::level).filter(java.util.Objects::nonNull).sorted().toList();
            List<Integer> experiences = rows.stream().map(Observation::experience).filter(java.util.Objects::nonNull).toList();
            Double average = levels.isEmpty() ? null : levels.stream().mapToInt(Integer::intValue).average().orElseThrow();
            int size = levels.size();
            Double median = size == 0 ? null : (size % 2 == 0
                    ? levels.get(size / 2 - 1) / 2.0 + levels.get(size / 2) / 2.0
                    : levels.get(size / 2).doubleValue());
            Map<String, Long> histogram = new LinkedHashMap<>();
            for (String label : bands) {
                long count = levels.stream().filter(level -> band(level).equals(label)).count();
                if (count > 0) histogram.put(label, count);
            }
            groups.add(new Group(name, rows.size(), size, experiences.size(),
                    experiences.stream().mapToLong(Integer::longValue).sum(), average, median, histogram));
        }
        return new Snapshot(categories, bands, groups, total, included, missingExp, missingLevel,
                unclassified, skipped, warnings);
    }

    static String band(int level) {
        if (level == 99) return "99";
        int lower = level / 10 * 10;
        return lower + "-" + (lower == 90 ? 98 : lower + 9);
    }
    private record Observation(Integer level, Integer experience) { }
}
