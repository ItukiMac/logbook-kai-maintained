package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuestUiPackagingBoundaryTest {
    @Test
    void everyQuestUiClassAvoidsPrivateHostGuiHelpers() throws Exception {
        var families = List.of("QuestPane", "QuestProgress", "QuestConditionEditController",
                "ArsenalQuestConditionEditController", "QuestEditorDocument", "QuestObservationUiBinding");
        var denied = List.of("InternalFXMLLoader", "WindowController", "DefinitionUiBinding", "QuestVisibilityUi",
                "Tools", "TableTool");
        Path directory = Path.of(QuestPane.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .resolve("logbook/internal/gui");
        int inspected = 0;
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                String name = path.getFileName().toString();
                if (families.stream().noneMatch(f -> name.equals(f + ".class") || name.startsWith(f + "$")))
                    continue;
                inspected++;
                String symbols = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
                for (String type : denied)
                    assertFalse(symbols.contains("logbook/internal/gui/" + type), name + " links private host " + type);
            }
        }
        assertTrue(inspected >= families.size());
    }
}
