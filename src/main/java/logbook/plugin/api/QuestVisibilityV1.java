package logbook.plugin.api;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import logbook.core.QuestVisibilityService;

/**
 * Reversible, session-only presentation commands. NEVER deletes a quest, changes
 * its acceptance/history, frees a custom slot, writes Config, or sends a game request.
 * Hidden IDs stay hidden until explicitly restored or this JVM ends, even if the
 * game reports new progress. This is separate from editing/deleting a definition.
 * Capture the plan before asking the user; apply it only after explicit confirmation.
 * The boolean is a caller contract, not authentication of a user's identity.
 */
public final class QuestVisibilityV1 {
    private QuestVisibilityV1() {
    }

    public static int version() {
        return 1;
    }

    public static State snapshot() {
        return QuestVisibilityService.snapshot();
    }

    public static Plan prepareHide(Collection<Integer> ids) {
        return QuestVisibilityService.prepareHide(ids);
    }

    public static Plan prepareRestoreAll() {
        return QuestVisibilityService.prepareRestoreAll();
    }

    public static Result apply(Plan plan, boolean confirmed) {
        return QuestVisibilityService.apply(plan, confirmed);
    }

    public enum Action {
        HIDE, RESTORE
    }

    public enum Outcome {
        APPLIED, UNCHANGED, CANCELLED, CONFLICT
    }

    public record State(long revision, Set<Integer> hiddenIds) {
        public State {
            if (revision < 0)
                throw new IllegalArgumentException("Negative visibility revision");
            hiddenIds = copy(hiddenIds);
        }
    }

    public record Plan(State expected, Action action, Set<Integer> questIds) {
        public Plan {
            Objects.requireNonNull(expected);
            Objects.requireNonNull(action);
            questIds = copy(questIds);
        }
    }

    public record Result(Outcome outcome, State state) {
        public Result {
            Objects.requireNonNull(outcome);
            Objects.requireNonNull(state);
        }

        public boolean accepted() {
            return outcome == Outcome.APPLIED || outcome == Outcome.UNCHANGED;
        }
    }

    public static Set<Integer> copy(Collection<Integer> ids) {
        Objects.requireNonNull(ids);
        if (ids.size() > 10000)
            throw new IllegalArgumentException("Too many quest IDs");
        var result = new TreeSet<Integer>();
        for (Integer id : ids) {
            if (id == null || id <= 0)
                throw new IllegalArgumentException("Positive quest IDs required");
            result.add(id);
        }
        return Collections.unmodifiableSet(result);
    }
}
