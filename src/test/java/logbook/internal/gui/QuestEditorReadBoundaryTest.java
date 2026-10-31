package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class QuestEditorReadBoundaryTest {
    @Test
    void controllerDoesNotReadLiveQuestCollectionOrHostResourceResolver() throws Exception {
        try (var in = QuestConditionEditController.class.getResourceAsStream("QuestConditionEditController.class")) {
            String symbols = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            for (String type : new String[] { "logbook/bean/AppQuestCollection", "logbook/core/LogBookCoreServices",
                    "logbook/constants/StatisticsShipTypeGroup" })
                assertFalse(symbols.contains(type), "EDITOR_DIRECT_READ: " + type);
        }
    }

    @Test
    void formLoadingUsesPublicProjectionNotMutableDomainModel() throws Exception {
        String source = Files
                .readString(Path.of("src/main/java/logbook/internal/gui/QuestConditionEditController.java"));
        int a = source.indexOf("    private void loadQuestData(");
        int b = source.indexOf("    private void updateConditionsArea(", a);
        String body = source.substring(a, b);
        assertFalse(body.contains("logbook.bean.AppQuestCondition"), "EDITOR_MUTABLE_FORM_READ");
        assertFalse(body.contains("treeToValue"), "EDITOR_DOMAIN_DECODING");
        assertTrue(body.contains("QuestEditorDataV1.form"));
    }
}
