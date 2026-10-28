package logbook.plugin.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import logbook.core.ShipLabelService;

/**
 * Explicit commands for user-created ship labels. Capture targets before a
 * confirmation dialog; apply only when its user explicitly confirms the edit.
 * No game request, Config.store, file deletion or event-tag mutation is performed.
 * Commands cooperate through a host lock. Legacy plugins that directly mutate
 * the old bean do not participate in this lock; no global transaction is promised.
 */
public final class ShipLabelsV1 {
    private ShipLabelsV1() { }
    public static int version() { return 1; }
    public static List<Target> capture(List<Integer> shipIds) { return ShipLabelService.capture(shipIds); }
    public static Result add(List<Target> targets, String label) { return ShipLabelService.apply(targets, label, true); }
    public static Result remove(List<Target> targets, String label) { return ShipLabelService.apply(targets, label, false); }

    public record Target(int shipId, int masterId, Set<String> expectedLabels) {
        public Target {
            if (shipId <= 0 || masterId <= 0) throw new IllegalArgumentException("Positive ship identities required");
            expectedLabels = copy(expectedLabels);
        }
    }
    public enum Outcome { APPLIED, UNCHANGED, CONFLICT, MISSING_SHIP, INVALID_STATE }
    public record Result(Outcome outcome, Set<Integer> changedIds, Map<Integer, Set<String>> displayLabels) {
        public Result {
            Objects.requireNonNull(outcome);
            changedIds = Collections.unmodifiableSet(new LinkedHashSet<>(changedIds));
            Map<Integer, Set<String>> result = new LinkedHashMap<>();
            displayLabels.forEach((id, labels) -> result.put(Objects.requireNonNull(id), copy(labels)));
            displayLabels = Collections.unmodifiableMap(result);
        }
        public boolean accepted() { return outcome == Outcome.APPLIED || outcome == Outcome.UNCHANGED; }
    }
    private static Set<String> copy(Set<String> source) {
        Objects.requireNonNull(source);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String label : source) result.add(Objects.requireNonNull(label));
        return Collections.unmodifiableSet(result);
    }
}
