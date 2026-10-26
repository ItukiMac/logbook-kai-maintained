package logbook.internal.util;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuestDocumentStoreTest {
    @TempDir Path root;
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    @Test void saveKeepsExactOriginalInUniqueBackup() throws Exception {
        Path file = root.resolve("10001.json");
        byte[] old = bytes("// comment\r\n{}\r\n"), next = bytes("{\"x\":1}");
        Files.write(file, old);
        Path backup = QuestDocumentStore.save(file, old, next);
        assertArrayEquals(old, Files.readAllBytes(backup));
        assertArrayEquals(next, Files.readAllBytes(file));
        Path second = QuestDocumentStore.save(file, next, bytes("{}"));
        assertNotEquals(backup, second);
        assertArrayEquals(old, Files.readAllBytes(backup));
    }
    @Test void noOpPreservesCommentBytesAndCreatesNoBackup() throws Exception {
        Path file = root.resolve("1.json"); byte[] old = bytes("// note\n{}");
        Files.write(file, old);
        assertNull(QuestDocumentStore.save(file, old, old));
        try (var files = Files.list(root)) { assertEquals(1, files.count()); }
    }
    @Test void conflictAndUnexpectedNewFileAreNotOverwritten() throws Exception {
        Path file = root.resolve("1.json"); Files.writeString(file, "changed");
        assertThrows(IOException.class, () -> QuestDocumentStore.save(file, bytes("old"), bytes("new")));
        assertThrows(IOException.class, () -> QuestDocumentStore.save(file, null, bytes("new")));
        assertEquals("changed", Files.readString(file));
        try (var files = Files.list(root)) { assertEquals(1, files.count()); }
    }
    @Test void newFileUsesNewPathOnly() throws Exception {
        Path file = root.resolve("customquest/1.json");
        assertNull(QuestDocumentStore.save(file, null, bytes("{}")));
        assertEquals("{}", Files.readString(file));
        assertThrows(IOException.class, () -> QuestDocumentStore.save(file, null, bytes("changed")));
    }
    @Test void deletedOriginalIsAConflictNotRecreated() throws Exception {
        Path file = root.resolve("1.json");
        assertThrows(IOException.class, () -> QuestDocumentStore.save(file, bytes("old"), bytes("new")));
        assertFalse(Files.exists(file));
    }
    @Test void symlinkAndTraversalRefuseWithoutChangingTarget() throws Exception {
        Path target = root.resolve("original"); Files.writeString(target, "keep");
        Path alias = root.resolve("alias"); Files.createSymbolicLink(alias, target);
        assertThrows(IOException.class, () -> QuestDocumentStore.save(alias, bytes("keep"), bytes("lost")));
        assertThrows(IOException.class, () -> QuestDocumentStore.read(root.resolve("x/../original")));
        assertEquals("keep", Files.readString(target));
    }
    @Test void oversizedAndDirectoryInputsAreRefused() throws Exception {
        assertThrows(IOException.class, () -> QuestDocumentStore.read(root));
        Path file = root.resolve("1.json");
        assertThrows(IOException.class, () -> QuestDocumentStore.save(file, null, new byte[2 * 1024 * 1024 + 1]));
        assertFalse(Files.exists(file));
    }
}
