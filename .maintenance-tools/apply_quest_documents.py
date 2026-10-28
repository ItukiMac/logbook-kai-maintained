from pathlib import Path

def new(path,content):
    p=Path(path);assert not p.exists(),path
    p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content,encoding='utf8')

def edit(path,fn):
    p=Path(path);raw=p.read_bytes();ending='\r\n' if b'\r\n' in raw else '\n'
    before=raw.decode().replace('\r\n','\n');after=fn(before)
    assert after!=before,path
    p.write_bytes(after.replace('\n',ending).encode())

def once(text,old,replacement):
    assert text.count(old)==1,(old,text.count(old))
    return text.replace(old,replacement)

new('src/main/java/logbook/plugin/api/QuestDocumentsV1.java',r'''package logbook.plugin.api;

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
    private QuestDocumentsV1() { }
    public static int version() { return 1; }
    public static Workspace at(Path approvedDirectory) { return QuestDocumentService.at(approvedDirectory); }
    public interface Workspace { Session open(int questId) throws IOException; }
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
    public enum Operation { SAVE, RETIRE }
    public enum Outcome { APPLIED, UNCHANGED, CANCELLED }
    public record Result(Outcome outcome, Document document, Path retained) {
        public Result { Objects.requireNonNull(outcome); Objects.requireNonNull(document); }
    }
    /** Raw bytes, including comments/unknown keys. An absent file has null bytes, not an empty JSON object. */
    public static final class Document {
        private final int questId;
        private final String target;
        private final byte[] bytes;
        private final String sha256;
        public Document(int questId, String target, byte[] bytes) {
            if (questId <= 0) throw new IllegalArgumentException("Positive quest identity required");
            this.target = Objects.requireNonNull(target);
            if (bytes != null && bytes.length > MAX_BYTES) throw new IllegalArgumentException("Document exceeds size limit");
            this.questId = questId;
            this.bytes = bytes == null ? null : bytes.clone();
            this.sha256 = bytes == null ? null : digest(this.bytes);
        }
        public int questId() { return questId; }
        public String target() { return target; }
        public boolean present() { return bytes != null; }
        public byte[] bytes() { return bytes == null ? null : bytes.clone(); }
        public String sha256() { return sha256; }
        @Override public boolean equals(Object other) {
            return other instanceof Document d && questId == d.questId && target.equals(d.target) && Arrays.equals(bytes,d.bytes);
        }
        @Override public int hashCode() { return Objects.hash(questId,target,Arrays.hashCode(bytes)); }
        @Override public String toString() { return "QuestDocument[id=" + questId + ", present=" + present() + ", sha256=" + sha256 + "]"; }
    }
    private static String digest(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
''')

new('src/main/java/logbook/core/QuestDocumentService.java',r'''package logbook.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import logbook.internal.util.QuestDocumentStore;
import logbook.plugin.api.QuestDocumentsV1;
import logbook.plugin.api.QuestDocumentsV1.*;

/** Command ownership and validation; the existing store still owns all filesystem mutations. */
public final class QuestDocumentService {
    private QuestDocumentService() { }
    public static Workspace at(Path approvedDirectory) {
        Objects.requireNonNull(approvedDirectory);
        if (approvedDirectory.toString().isBlank()) throw new IllegalArgumentException("Missing directory");
        for (Path part : approvedDirectory)
            if (part.toString().equals("..")) throw new IllegalArgumentException("Parent traversal is not a document directory");
        Path root = approvedDirectory.toAbsolutePath().normalize();
        return id -> {
            if (id <= 0) throw new IllegalArgumentException("Positive quest identity required");
            Path target = root.resolve(id + ".json");
            return new Editor(target, new Document(id,target.toString(),QuestDocumentStore.read(target)));
        };
    }
    private static final class Change implements Plan {
        private final Editor owner;
        private final long revision;
        private final Operation operation;
        private final Document before;
        private final Document after;
        Change(Editor owner, long revision, Operation operation, Document before, Document after) {
            this.owner=owner; this.revision=revision; this.operation=operation; this.before=before; this.after=after;
        }
        public int questId() { return before.questId(); }
        public Operation operation() { return operation; }
        public String target() { return before.target(); }
        public boolean existing() { return before.present(); }
        public String expectedSha256() { return before.sha256(); }
        public String replacementSha256() { return after.sha256(); }
    }
    private static final class Editor implements Session {
        private final Path target;
        private Document document;
        private long revision;
        Editor(Path target, Document document) { this.target=target; this.document=document; }
        public synchronized Document document() { return document; }
        public synchronized Plan prepareSave(byte[] replacement) throws IOException {
            if (replacement == null || replacement.length > QuestDocumentsV1.MAX_BYTES)
                throw new IOException("保存する任務設定が不正、または上限を超えています。");
            Document after = new Document(document.questId(), document.target(), replacement);
            // Same strict parser/arrival/deck rules as the existing editor, not a second implementation.
            QuestDocumentValidation.validate(QuestDocumentValidation.parse(after.bytes()));
            return new Change(this,revision,Operation.SAVE,document,after);
        }
        public synchronized Plan prepareRetirement() throws IOException {
            if (document.questId() < 10001 || document.questId() > 10005)
                throw new IOException("退避できるのはカスタム任務10001～10005だけです。");
            return new Change(this,revision,Operation.RETIRE,document,
                    new Document(document.questId(),document.target(),null));
        }
        public synchronized Result apply(Plan requested, boolean confirmed) throws IOException {
            if (!(requested instanceof Change plan) || plan.owner != this)
                throw new IOException("別の編集対象で作られた操作です。対象を再読込してください。");
            if (!confirmed) return new Result(Outcome.CANCELLED,document,null);
            if (plan.revision != revision || !document.equals(plan.before))
                throw new IOException("編集状態が変わっています。対象を再読込してください。");
            boolean changed = !Arrays.equals(plan.before.bytes(),plan.after.bytes());
            long nextRevision = changed ? Math.incrementExact(revision) : revision;
            // Store rechecks exact bytes and safe paths, including after backup preparation.
            // No new filesystem implementation or non-atomic fallback is introduced here.
            Path retained = plan.operation == Operation.SAVE
                    ? QuestDocumentStore.save(target,plan.before.bytes(),plan.after.bytes())
                    : QuestDocumentStore.remove(target,plan.before.bytes());
            document = plan.after;
            revision = nextRevision;
            return new Result(changed ? Outcome.APPLIED : Outcome.UNCHANGED,document,retained);
        }
    }
}
''')

# Move the exact shared parsing/validation bodies out of the GUI; preserve the facade for old tests/callers.
p=Path('src/main/java/logbook/internal/gui/QuestEditorDocument.java')
s=p.read_text()
start=s.index('    static ObjectMapper mapper()')
end=s.index('    static Set<Integer> deckIds(',start)
parsing=s[start:end]
start_v=s.index('    static void validate(JsonNode document)')
end_v=s.index('    static JsonNode merge(',start_v)
validation=s[start_v:end_v]
shared='''package logbook.core;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Existing editor JSON rules, shared without a dependency on JavaFX or GUI controllers. */
public final class QuestDocumentValidation {
    private QuestDocumentValidation() { }
'''+parsing.replace('static ObjectMapper','public static ObjectMapper').replace('static JsonNode','public static JsonNode')+validation.replace('static void validate','public static void validate')+'}\n'
new('src/main/java/logbook/core/QuestDocumentValidation.java',shared)
def facade(s):
    s=once(s,parsing,'''    static ObjectMapper mapper() { return logbook.core.QuestDocumentValidation.mapper(); }
    static JsonNode parse(byte[] input) throws IOException { return logbook.core.QuestDocumentValidation.parse(input); }
    static JsonNode parse(String input) throws IOException { return logbook.core.QuestDocumentValidation.parse(input); }

''')
    return once(s,validation,'''    static void validate(JsonNode document) throws IOException { logbook.core.QuestDocumentValidation.validate(document); }

''')
edit(str(p),facade)

def controller(s):
    s=once(s,'    private byte[] loadedBytes;','    private byte[] loadedBytes;\n    private logbook.plugin.api.QuestDocumentsV1.Session documentSession;')
    s=once(s,'            this.loadedBytes = null;','            this.loadedBytes = null;\n            this.documentSession = null;')
    s=once(s,'        java.nio.file.Path file = this.questDirectory().resolve(quest.getNo() + ".json");\n        try {\n            this.loadedBytes = logbook.internal.util.QuestDocumentStore.read(file);',
        '''        try {
            this.documentSession = logbook.plugin.api.QuestDocumentsV1.at(this.questDirectory()).open(quest.getNo());
            this.loadedBytes = this.documentSession.document().bytes();''')
    s=once(s,'        var file = this.questDirectory().resolve(appQuest.getQuest().getNo() + ".json");',
        '''        if (this.documentSession == null || this.documentSession.document().questId() != appQuest.getQuest().getNo())
            throw new java.io.IOException("読込時と保存対象が異なります。対象を再読込してください。");''')
    s=once(s,'        var backup = logbook.internal.util.QuestDocumentStore.save(file, this.loadedBytes, bytes);',
        '''        var plan = this.documentSession.prepareSave(bytes);
        // Explicit Save button / package-local test hook. The session owns the exact captured target.
        var backup = this.documentSession.apply(plan, true).retained();''')
    start=s.index('    java.nio.file.Path removeDocument() throws java.io.IOException {')
    end=s.index('    @FXML\n    void remove(',start)
    old=s[start:end]
    replacement='''    private logbook.plugin.api.QuestDocumentsV1.Plan prepareRetirementDocument() throws java.io.IOException {
        AppQuest appQuest = this.questSelector.getSelectionModel().getSelectedItem();
        if (appQuest == null || appQuest.getQuest() == null || this.documentSession == null
                || this.documentSession.document().questId() != appQuest.getQuest().getNo())
            throw new java.io.IOException("読込時と退避対象が異なります。対象を再読込してください。");
        return this.documentSession.prepareRetirement();
    }

    private java.nio.file.Path applyRetirementDocument(logbook.plugin.api.QuestDocumentsV1.Plan plan)
            throws java.io.IOException {
        var result = this.documentSession.apply(plan, true);
        if (result.retained() != null) {
            this.setQuestData(this.questSelector.getSelectionModel().getSelectedItem());
            this.removeBtn.setDisable(true);
        }
        return result.retained();
    }

    // Retained explicit package-local test hook; user-facing retirement confirms the captured plan below.
    java.nio.file.Path removeDocument() throws java.io.IOException {
        return applyRetirementDocument(prepareRetirementDocument());
    }

'''
    s=once(s,old,replacement)
    s=once(s,'            java.nio.file.Path retained = this.removeDocument();',
        '''            var plan = this.prepareRetirementDocument();
            if (plan.existing()) {
                var proceed = new javafx.scene.control.ButtonType("原本を保持して退避", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
                var confirm = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION,
                        "対象: " + plan.target() + "\\n読込時SHA-256: " + plan.expectedSha256()
                        + "\\n\\n有効な定義名から外し、同じフォルダーへ別名で原本と検証済みコピーを残します。"
                        + "\\nこのカスタム枠は空きます。受諾履歴・到達記録・他の設定は変更しません。"
                        + "\\nファイルが読込後に変更されていた場合は退避を中止します。",
                        proceed, javafx.scene.control.ButtonType.CANCEL);
                confirm.initOwner(this.questSelector.getScene().getWindow());
                confirm.setTitle("カスタム任務の退避確認"); confirm.setHeaderText("完全削除ではなく、復旧可能な別名退避です");
                if (confirm.showAndWait().filter(proceed::equals).isEmpty()) {
                    this.documentSession.apply(plan, false);
                    return;
                }
            }
            java.nio.file.Path retained = this.applyRetirementDocument(plan);''')
    assert 'logbook.internal.util.QuestDocumentStore' not in s
    return s
edit('src/main/java/logbook/internal/gui/QuestConditionEditController.java',controller)
print('QUEST_DOCUMENT_API_APPLIED existing_store_unchanged=true exact_rules_reused=true source_data_untouched=true')
