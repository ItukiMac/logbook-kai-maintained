package logbook.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import logbook.internal.util.QuestDocumentStore;
import logbook.plugin.api.QuestDocumentsV1;
import logbook.plugin.api.QuestDocumentsV1.*;

/** Command ownership and validation; the existing store still owns all filesystem mutations. */
public final class QuestDocumentService {
    private QuestDocumentService() {
    }

    public static Workspace at(Path approvedDirectory) {
        return at(approvedDirectory, bytes -> QuestDocumentValidation.validate(QuestDocumentValidation.parse(bytes)),
                true);
    }

    @FunctionalInterface
    interface Validator {
        void validate(byte[] bytes) throws IOException;
    }

    static Workspace at(Path approvedDirectory, Validator validator, boolean allowRetirement) {
        Objects.requireNonNull(approvedDirectory);
        if (approvedDirectory.toString().isBlank())
            throw new IllegalArgumentException("Missing directory");
        for (Path part : approvedDirectory)
            if (part.toString().equals(".."))
                throw new IllegalArgumentException("Parent traversal is not a document directory");
        Path root = approvedDirectory.toAbsolutePath().normalize();
        return id -> {
            if (id <= 0)
                throw new IllegalArgumentException("Positive quest identity required");
            Path target = root.resolve(id + ".json");
            return new Editor(target, new Document(id, target.toString(), QuestDocumentStore.read(target)), validator,
                    allowRetirement);
        };
    }

    private static final class Change implements Plan {
        private final Editor owner;
        private final long revision;
        private final Operation operation;
        private final Document before;
        private final Document after;

        Change(Editor owner, long revision, Operation operation, Document before, Document after) {
            this.owner = owner;
            this.revision = revision;
            this.operation = operation;
            this.before = before;
            this.after = after;
        }

        public int questId() {
            return before.questId();
        }

        public Operation operation() {
            return operation;
        }

        public String target() {
            return before.target();
        }

        public boolean existing() {
            return before.present();
        }

        public String expectedSha256() {
            return before.sha256();
        }

        public String replacementSha256() {
            return after.sha256();
        }
    }

    private static final class Editor implements Session {
        private final Path target;
        private Document document;
        private long revision;
        private final Validator validator;
        private final boolean allowRetirement;

        Editor(Path target, Document document, Validator validator, boolean allowRetirement) {
            this.target = target;
            this.document = document;
            this.validator = validator;
            this.allowRetirement = allowRetirement;
        }

        public synchronized Document document() {
            return document;
        }

        public synchronized Plan prepareSave(byte[] replacement) throws IOException {
            if (replacement == null || replacement.length > QuestDocumentsV1.MAX_BYTES)
                throw new IOException("保存する任務設定が不正、または上限を超えています。");
            Document after = new Document(document.questId(), document.target(), replacement);
            // Same strict parser/arrival/deck rules as the existing editor, not a second implementation.
            validator.validate(after.bytes());
            return new Change(this, revision, Operation.SAVE, document, after);
        }

        public synchronized Plan prepareRetirement() throws IOException {
            if (!allowRetirement || document.questId() < 10001 || document.questId() > 10005)
                throw new IOException("退避できるのはカスタム任務10001～10005だけです。");
            return new Change(this, revision, Operation.RETIRE, document,
                    new Document(document.questId(), document.target(), null));
        }

        public synchronized Result apply(Plan requested, boolean confirmed) throws IOException {
            if (!(requested instanceof Change plan) || plan.owner != this)
                throw new IOException("別の編集対象で作られた操作です。対象を再読込してください。");
            if (!confirmed)
                return new Result(Outcome.CANCELLED, document, null);
            if (plan.revision != revision || !document.equals(plan.before))
                throw new IOException("編集状態が変わっています。対象を再読込してください。");
            boolean changed = !Arrays.equals(plan.before.bytes(), plan.after.bytes());
            long nextRevision = changed ? Math.incrementExact(revision) : revision;
            // Store rechecks exact bytes and safe paths, including after backup preparation.
            // No new filesystem implementation or non-atomic fallback is introduced here.
            Path retained = plan.operation == Operation.SAVE
                    ? QuestDocumentStore.save(target, plan.before.bytes(), plan.after.bytes())
                    : QuestDocumentStore.remove(target, plan.before.bytes());
            document = plan.after;
            revision = nextRevision;
            return new Result(changed ? Outcome.APPLIED : Outcome.UNCHANGED, document, retained);
        }
    }
}
