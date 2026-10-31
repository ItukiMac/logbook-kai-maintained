package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class QuestVisibilityBoundaryTest {
    @Test
    void questPaneCannotDeleteFilesOrMutateTheQuestCollection() throws Exception {
        try (var in = QuestPane.class.getResourceAsStream("QuestPane.class")) {
            assertNotNull(in);
            String symbols = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertFalse(symbols.contains("deleteIfExists"));
            assertFalse(symbols.contains("logbook/bean/AppQuestCollection"));
            assertFalse(symbols.contains("logbook/internal/gui/QuestVisibilityUi"));
            assertTrue(symbols.contains("logbook/plugin/api/QuestUiV1"));
        }
    }

    @Test
    void commandApiIsPresentAndFxmlNoLongerPromisesDeletion() throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream("logbook/gui/quest.fxml")) {
            assertNotNull(in);
            var fxml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(fxml.contains("text=\"非表示\""));
            assertFalse(fxml.contains("text=\"除去\""));
        }
    }
}
