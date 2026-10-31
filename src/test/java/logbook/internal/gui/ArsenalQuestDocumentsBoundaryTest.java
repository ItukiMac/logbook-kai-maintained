package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ArsenalQuestDocumentsBoundaryTest {
    @Test
    void editorCannotLinkDirectFilesystemOrMutableQuestCollection() throws Exception {
        try (var in = ArsenalQuestConditionEditController.class
                .getResourceAsStream("ArsenalQuestConditionEditController.class")) {
            assertNotNull(in);
            String symbols = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            for (String forbidden : java.util.List.of("java/nio/file/Files", "logbook/bean/AppQuest",
                    "logbook/bean/ArsenalQuestSetting", "tools/jackson", "logbook/internal/util/QuestDocumentStore"))
                assertFalse(symbols.contains(forbidden), forbidden);
            assertTrue(symbols.contains("logbook/plugin/api/ArsenalQuestDocumentsV1"));
            assertTrue(symbols.contains("logbook/plugin/api/QuestListV1"));
        }
    }
}
