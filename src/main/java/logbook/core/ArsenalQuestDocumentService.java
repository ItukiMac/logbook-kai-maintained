package logbook.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import logbook.plugin.api.ArsenalQuestDocumentsV1.*;
import logbook.plugin.api.QuestDocumentsV1;
import tools.jackson.databind.node.ObjectNode;

/** Reuse the existing guarded document command/store, preserving unsupported JSON fields. */
public final class ArsenalQuestDocumentService {
    private ArsenalQuestDocumentService() { }
    private static ObjectNode parse(byte[] bytes) throws IOException {
        var node = QuestDocumentValidation.parse(bytes);
        if (node == null || !node.isObject()) throw new IOException("工廠設定はJSONオブジェクトが必要です。");
        var discard = node.path("discard");
        var comment = node.path("comment");
        if ((!discard.isMissingNode() && !discard.isBoolean())
                || (!comment.isMissingNode() && !comment.isNull() && !comment.isTextual()))
            throw new IOException("工廠設定のdiscardまたはcommentの形式が不正です。元ファイルは保持しています。");
        return (ObjectNode) node;
    }
    public static Workspace at(Path directory) {
        var workspace = QuestDocumentService.at(directory, bytes -> parse(bytes), false);
        return id -> new Editor(workspace.open(id));
    }
    private static final class Editor implements Session {
        private final QuestDocumentsV1.Session delegate;
        private Value value;
        Editor(QuestDocumentsV1.Session delegate) throws IOException {
            this.delegate = delegate;
            var raw = delegate.document().bytes();
            var node = raw == null ? QuestDocumentValidation.mapper().createObjectNode() : parse(raw);
            value = new Value(node.path("discard").asBoolean(false),
                    node.path("comment").isTextual() ? node.path("comment").textValue() : null);
        }
        public synchronized QuestDocumentsV1.Document document() { return delegate.document(); }
        public synchronized Value value() { return value; }
        public synchronized QuestDocumentsV1.Plan prepareSave(Value replacement) throws IOException {
            Objects.requireNonNull(replacement);
            var raw = delegate.document().bytes();
            if (raw != null && replacement.equals(value)) return delegate.prepareSave(raw);
            ObjectNode node = raw == null ? QuestDocumentValidation.mapper().createObjectNode() : parse(raw);
            node.put("discard", replacement.discard());
            if (replacement.comment() == null) node.putNull("comment");
            else node.put("comment", replacement.comment());
            return delegate.prepareSave(QuestDocumentValidation.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(node));
        }
        public synchronized QuestDocumentsV1.Result apply(QuestDocumentsV1.Plan plan, boolean confirmed) throws IOException {
            var result = delegate.apply(plan, confirmed);
            if (result.outcome() != QuestDocumentsV1.Outcome.CANCELLED) {
                var node = parse(result.document().bytes());
                value = new Value(node.path("discard").asBoolean(false),
                        node.path("comment").isTextual() ? node.path("comment").textValue() : null);
            }
            return result;
        }
    }
}
