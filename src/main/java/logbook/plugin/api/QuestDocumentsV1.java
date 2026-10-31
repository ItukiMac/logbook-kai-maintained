package logbook.plugin.api;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import logbook.core.QuestDocumentService;

/**
 * Explicit local editor commands, not game requests or a plugin security sandbox.
 * The host/caller supplies an approved directory; IDs resolve to decimal .json names.
 * A session is bound to the ID and bytes read at opening. Prepare changes before
 * confirmation; cancellation writes nothing. Retire means backed-up removal from
 * the active name, NOT deletion. The existing store retains original/pending files.
 * This contract does not synchronize uncooperative external filesystem writers.
 */
public final class QuestDocumentsV1 {
    public static final int MAX_BYTES = 2 * 1024 * 1024;

    private QuestDocumentsV1() {
    }

    public static int version() {
        return 1;
    }

    public static Workspace at(Path approvedDirectory) {
        return QuestDocumentService.at(approvedDirectory);
    }

    public interface Workspace {
        Session open(int questId) throws IOException;
    }

    public interface Session {
        Document document();

        Plan prepareSave(byte[] replacement) throws IOException;

        Plan prepareRetirement() throws IOException;

        Result apply(Plan plan, boolean confirmed) throws IOException;
    }

    /** Host-issued immutable plan. It is not transferable to another session. */
    public interface Plan {
        int questId();

        Operation operation();

        String target();

        boolean existing();

        String expectedSha256();

        String replacementSha256();
    }

    public enum Operation {
        SAVE, RETIRE
    }

    public enum Outcome {
        APPLIED, UNCHANGED, CANCELLED
    }

    public record Result(Outcome outcome, Document document, Path retained) {
        public Result {
            Objects.requireNonNull(outcome);
            Objects.requireNonNull(document);
        }
    }

    /** Raw bytes, including comments/unknown keys. An absent file has null bytes, not an empty JSON object. */
    public static final class Document {
        private final int questId;
        private final String target;
        private final byte[] bytes;
        private final String sha256;

        public Document(int questId, String target, byte[] bytes) {
            if (questId <= 0)
                throw new IllegalArgumentException("Positive quest identity required");
            this.target = Objects.requireNonNull(target);
            if (bytes != null && bytes.length > MAX_BYTES)
                throw new IllegalArgumentException("Document exceeds size limit");
            this.questId = questId;
            this.bytes = bytes == null ? null : bytes.clone();
            this.sha256 = bytes == null ? null : digest(this.bytes);
        }

        public int questId() {
            return questId;
        }

        public String target() {
            return target;
        }

        public boolean present() {
            return bytes != null;
        }

        public byte[] bytes() {
            return bytes == null ? null : bytes.clone();
        }

        public String sha256() {
            return sha256;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Document d && questId == d.questId && target.equals(d.target)
                    && Arrays.equals(bytes, d.bytes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(questId, target, Arrays.hashCode(bytes));
        }

        @Override
        public String toString() {
            return "QuestDocument[id=" + questId + ", present=" + present() + ", sha256=" + sha256 + "]";
        }
    }

    private static String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
