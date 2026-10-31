package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class QuestDocumentCommandsBoundaryTest {
    private static String symbols(Class<?> type) throws Exception {
        try (var in = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            assertNotNull(in);
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    @Test
    void editorUsesPublicCommandsInsteadOfTheFilesystemStore() throws Exception {
        var text = symbols(QuestConditionEditController.class);
        assertFalse(text.contains("logbook/internal/util/QuestDocumentStore"),
                "Editor directly links filesystem mutations");
        assertTrue(text.contains("logbook/plugin/api/QuestDocumentsV1"));
    }

    @Test
    void editorParserDelegatesToTheSameHostValidationRules() throws Exception {
        assertTrue(symbols(QuestEditorDocument.class).contains("logbook/core/QuestDocumentValidation"),
                "GUI still owns shared validation");
    }
}
