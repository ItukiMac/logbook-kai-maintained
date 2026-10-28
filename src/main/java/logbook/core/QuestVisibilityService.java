package logbook.core;

import java.util.Collection;
import java.util.TreeSet;
import logbook.plugin.api.QuestVisibilityV1;
import logbook.plugin.api.QuestVisibilityV1.*;

/** Owns only an in-memory display policy. No legacy data, filesystem or core lifecycle access. */
public final class QuestVisibilityService {
    private static final Session SESSION = new Session();
    private QuestVisibilityService() { }
    public static State snapshot() { return SESSION.snapshot(); }
    public static Plan prepareHide(Collection<Integer> ids) { return SESSION.prepareHide(ids); }
    public static Plan prepareRestoreAll() { return SESSION.prepareRestoreAll(); }
    public static Result apply(Plan plan, boolean confirmed) { return SESSION.apply(plan, confirmed); }

    /** An isolated owner for pure tests; not a public way to reset the live session. */
    static final class Session {
        private State state = new State(0, java.util.Set.of());
        synchronized State snapshot() { return state; }
        synchronized Plan prepareHide(Collection<Integer> ids) {
            return new Plan(state, Action.HIDE, QuestVisibilityV1.copy(ids));
        }
        synchronized Plan prepareRestoreAll() {
            return new Plan(state, Action.RESTORE, state.hiddenIds());
        }
        synchronized Result apply(Plan plan, boolean confirmed) {
            java.util.Objects.requireNonNull(plan);
            if (!confirmed) return new Result(Outcome.CANCELLED, state);
            if (!state.equals(plan.expected())) return new Result(Outcome.CONFLICT, state);
            var next = new TreeSet<>(state.hiddenIds());
            if (plan.action() == Action.HIDE) next.addAll(plan.questIds());
            else next.removeAll(plan.questIds());
            if (next.equals(state.hiddenIds())) return new Result(Outcome.UNCHANGED, state);
            // Validate/copy before committing either revision or membership.
            State committed = new State(Math.incrementExact(state.revision()), next);
            state = committed;
            return new Result(Outcome.APPLIED, committed);
        }
    }
}
