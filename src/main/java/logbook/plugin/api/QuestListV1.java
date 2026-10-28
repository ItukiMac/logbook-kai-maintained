package logbook.plugin.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import logbook.core.QuestListReadService;

/**
 * Read-only observed-quest presentation. Copies detach display state from legacy
 * mutable beans; they do not certify atomicity with unsynchronized plugin writers.
 * No expiry calculation, acceptance reset, settings save or file deletion occurs.
 * Cached custom IDs are omitted from this observed list, never removed from storage.
 */
public final class QuestListV1 {
    private QuestListV1() { }
    public static int version() { return 1; }
    public static Listing snapshot() { return QuestListReadService.snapshot(); }
    public static boolean isCustomId(int id) { return id >= 10001 && id <= 10005; }

    public record Listing(List<Quest> quests, List<String> diagnostics) {
        public Listing {
            quests = List.copyOf(quests);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    /** Nullable fields and actual observation time remain nullable, not invented. */
    public record Quest(int no, Integer category, Integer type, Integer state,
            String title, String detail, List<Integer> materials, Integer bonusFlag,
            Integer progressFlag, Integer invalidFlag, Instant observedAt,
            String expire, boolean active) {
        public Quest {
            if (no <= 0) throw new IllegalArgumentException("Positive quest identity required");
            // Some old input lists contain null elements: preserve rather than repair them.
            materials = materials == null ? null : Collections.unmodifiableList(new ArrayList<>(materials));
        }
    }
}
