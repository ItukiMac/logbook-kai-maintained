package logbook.plugin.api;

import java.io.IOException;
import java.nio.file.Path;
import logbook.core.ArsenalQuestDocumentService;

/** Local factory-note editing. Never changes quest observations or game acceptance. */
public final class ArsenalQuestDocumentsV1 {
    private ArsenalQuestDocumentsV1() {
    }

    public static int version() {
        return 1;
    }

    public static Workspace at(Path approvedDirectory) {
        return ArsenalQuestDocumentService.at(approvedDirectory);
    }

    public interface Workspace {
        Session open(int questId) throws IOException;
    }

    public interface Session {
        QuestDocumentsV1.Document document();

        Value value();

        QuestDocumentsV1.Plan prepareSave(Value replacement) throws IOException;

        QuestDocumentsV1.Result apply(QuestDocumentsV1.Plan plan, boolean confirmed) throws IOException;
    }

    public record Value(boolean discard, String comment) {
    }
}
