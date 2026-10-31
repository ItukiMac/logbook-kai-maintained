package logbook.plugin.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import logbook.core.QuestLocalFilesService;

/**
 * Read-only captured local presentation, not observed game state or editing commands.
 * Contents are reread on capture, including same-size/same-mtime changes; files are
 * never repaired or saved. This is not an atomic snapshot across external writers.
 */
public final class QuestLocalFilesV1 {
    private QuestLocalFilesV1() {
    }

    public static int version() {
        return 1;
    }

    public static Snapshot snapshot(List<Integer> arsenalQuestIds) {
        return QuestLocalFilesService.snapshot(arsenalQuestIds);
    }

    public static Note note(int questId) {
        return QuestLocalFilesService.note(questId);
    }

    public enum Status {
        MISSING, VALID, INVALID, UNREADABLE
    }

    public record FileInfo(Status status, String sha256, String diagnostic) {
        public FileInfo {
            Objects.requireNonNull(status);
            Objects.requireNonNull(diagnostic);
        }

        public boolean present() {
            return status != Status.MISSING;
        }

        public boolean problem() {
            return status == Status.INVALID || status == Status.UNREADABLE;
        }
    }

    public record Custom(int questId, String title, FileInfo file) {
        public Custom {
            if (!QuestListV1.isCustomId(questId))
                throw new IllegalArgumentException("Custom quest identity required");
            Objects.requireNonNull(title);
            Objects.requireNonNull(file);
        }

        /** Local custom rows have no game observation timestamp or fabricated expiry. */
        public QuestListV1.Quest display() {
            return new QuestListV1.Quest(questId, 2, 4, 2, title, "カスタム任務" + (questId - 10000),
                    List.of(), 0, 0, 0, null, null, true);
        }
    }

    public record Note(int questId, String comment, boolean discard, FileInfo file) {
        public Note {
            if (questId <= 0)
                throw new IllegalArgumentException("Positive quest identity required");
            Objects.requireNonNull(comment);
            Objects.requireNonNull(file);
        }
    }

    public record Snapshot(List<Custom> customs, Map<Integer, Note> notes) {
        public Snapshot {
            customs = List.copyOf(customs);
            notes = Map.copyOf(notes);
        }
    }
}
