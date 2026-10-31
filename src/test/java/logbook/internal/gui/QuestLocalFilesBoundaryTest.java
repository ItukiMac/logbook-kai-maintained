package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class QuestLocalFilesBoundaryTest {
    @Test
    void listRendererDoesNotOpenParseOrGenerateLocalQuestObservations() throws Exception {
        var source = Files.readString(Path.of("src/main/java/logbook/internal/gui/MainController.java"));
        var body = source.substring(source.indexOf("    private void quest()"),
                source.indexOf("    private void checkNotifyMission()"));
        assertFalse(body.contains("java.nio.file"));
        assertFalse(body.contains("readValue"));
        assertFalse(body.contains("AppQuest.toAppQuest"));
        assertTrue(body.contains("QuestLocalFilesV1.snapshot"));
    }

    @Test
    void paneRendersCapturedNotesWithoutFilesystemOrJsonDependencies() throws Exception {
        try (var input = QuestPane.class.getResourceAsStream("QuestPane.class")) {
            var symbols = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
            assertFalse(symbols.contains("java/nio/file/Files"));
            assertFalse(symbols.contains("tools/jackson"));
            assertFalse(symbols.contains("ArsenalQuestSetting"));
            assertTrue(symbols.contains("QuestLocalFilesV1"));
        }
    }
}
