package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Parser failures remain checked I/O failures for the release recovery caller. */
class JsonGenerationPointerCompatibilityTest {
    @TempDir Path root;

    private void rejectsWithoutWriting(String text) throws Exception {
        byte[] original = text.getBytes(StandardCharsets.UTF_8);
        Path pointer = root.resolve("active.json");
        Files.write(pointer, original);
        assertThrows(IOException.class, () -> DefinitionPackInstaller.activeDefinitionsRoot(root));
        assertArrayEquals(original, Files.readAllBytes(pointer));
        try (var entries = Files.list(root)) {
            assertEquals(1, entries.count(), "Validation must not replace or discard evidence");
        }
    }

    @Test void truncatedPointerRetainsCheckedIoContractAndOriginalBytes() throws Exception {
        rejectsWithoutWriting("{\"schema\":\"interrupted");
    }

    @Test void duplicatePointerRetainsCheckedIoContractAndOriginalBytes() throws Exception {
        rejectsWithoutWriting("{\"schema\":\"" + DefinitionPackInstaller.POINTER_FORMAT
                + "\",\"generation\":\"" + "a".repeat(64) + "\",\"generation\":\"" + "b".repeat(64) + "\"}");
    }

    @Test void trailingPointerRetainsCheckedIoContractAndOriginalBytes() throws Exception {
        rejectsWithoutWriting("{\"schema\":\"" + DefinitionPackInstaller.POINTER_FORMAT
                + "\",\"generation\":\"" + "a".repeat(64) + "\"} {}");
    }

    @Test void missingPointerSelectsTheExplicitLegacyRootWithoutWritingIt() throws Exception {
        assertEquals(root.toAbsolutePath().normalize(), DefinitionPackInstaller.activeDefinitionsRoot(root));
        try (var entries = Files.list(root)) { assertEquals(0, entries.count()); }
    }
}
