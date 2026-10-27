package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Includes nested dialogs/cells; checking only outer controllers misses JVM access failures. */
class RecordUiBoundaryTest {
    @Test void recordBytecodeDoesNotDependOnHostGuiImplementation() throws Exception {
        List<String> families = List.of("BattleLogController", "MissionLogController", "CreateItemController");
        List<String> prohibited = List.of("TableTool", "TreeTableTool", "InternalFXMLLoader",
                "Tools", "WindowController", "BattleDetail");
        Path classes = Path.of(BattleLogController.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .resolve("logbook/internal/gui");
        int checked = 0;
        try (var paths = Files.list(classes)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                String name = file.getFileName().toString();
                if (families.stream().noneMatch(f -> name.equals(f + ".class") || name.startsWith(f + "$"))) continue;
                checked++;
                String symbols = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                for (String internal : prohibited)
                    assertFalse(symbols.contains("logbook/internal/gui/" + internal), name + " links host GUI " + internal);
            }
        }
        assertTrue(checked >= families.size(), "No record classfiles inspected");
    }
}
