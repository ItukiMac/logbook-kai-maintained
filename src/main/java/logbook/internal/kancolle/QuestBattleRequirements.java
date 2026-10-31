package logbook.internal.kancolle;

import java.util.Set;
import logbook.bean.AppQuestCondition.FilterCondition;
import logbook.bean.BattleLog;

/** Opt-in requirements evaluated from this battle's saved metadata, never current live state. */
final class QuestBattleRequirements {
    private QuestBattleRequirements() {
    }

    static boolean needsLog(FilterCondition filter) {
        return filter != null && (filter.getDeckIds() != null || Boolean.TRUE.equals(filter.getNoRetreat()));
    }

    static boolean matches(FilterCondition filter, BattleLog log) {
        if (!needsLog(filter))
            return true;
        if (log == null)
            return false;
        Set<Integer> allowed = filter.getDeckIds();
        if (allowed != null) {
            if (allowed.isEmpty() || allowed.stream().anyMatch(id -> id == null || id < 1 || id > 4))
                return false;
            Integer day = log.getBattle() == null ? null : log.getBattle().getDockId();
            Integer night = log.getMidnight() == null ? null : log.getMidnight().getDockId();
            if (day != null && night != null && !day.equals(night))
                return false;
            Integer recorded = day != null ? day : night;
            if (recorded == null || !allowed.contains(recorded))
                return false;
        }
        // A null legacy snapshot is unknown, not evidence that no ship retreated.
        return !Boolean.TRUE.equals(filter.getNoRetreat())
                || (log.getEscape() != null && log.getEscape().isEmpty());
    }
}
