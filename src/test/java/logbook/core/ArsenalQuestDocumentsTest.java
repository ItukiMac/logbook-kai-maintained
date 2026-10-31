package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import logbook.plugin.api.ArsenalQuestDocumentsV1;
import logbook.plugin.api.ArsenalQuestDocumentsV1.Value;
import logbook.plugin.api.QuestDocumentsV1;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArsenalQuestDocumentsTest {
    @TempDir
    Path root;
    static final String ORIGINAL = "/*keep*/{\"discard\":false,\"comment\":\"before\",\"future\":{\"x\":7}}";

    Path file() {
        return root.resolve("605.json");
    }

    ArsenalQuestDocumentsV1.Session original() throws IOException {
        Files.writeString(file(), ORIGINAL, StandardOpenOption.CREATE_NEW);
        return ArsenalQuestDocumentsV1.at(root).open(605);
    }

    @Test
    void missingReadDoesNotCreateDirectory() throws Exception {
        var path = root.resolve("absent");
        var s = ArsenalQuestDocumentsV1.at(path).open(605);
        assertFalse(s.document().present());
        assertFalse(s.value().discard());
        assertNull(s.value().comment());
        assertFalse(Files.exists(path));
    }

    @Test
    void confirmedSavePreservesUnknownFieldsAndOriginalBackup() throws Exception {
        var s = original();
        var value = new Value(true, "after");
        var result = s.apply(s.prepareSave(value), true);
        assertEquals(QuestDocumentsV1.Outcome.APPLIED, result.outcome());
        assertEquals(ORIGINAL, Files.readString(result.retained()));
        assertEquals(7, QuestDocumentValidation.parse(Files.readAllBytes(file())).path("future").path("x").asInt());
        assertEquals(value, s.value());
        assertEquals(value, ArsenalQuestDocumentsV1.at(root).open(605).value());
    }

    @Test
    void unchangedSaveRetainsCommentsAndCreatesNoBackup() throws Exception {
        var s = original();
        var result = s.apply(s.prepareSave(s.value()), true);
        assertEquals(QuestDocumentsV1.Outcome.UNCHANGED, result.outcome());
        assertNull(result.retained());
        assertEquals(ORIGINAL, Files.readString(file()));
        try (var files = Files.list(root)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void cancelledSaveDoesNotWriteOrChangeSession() throws Exception {
        var s = original();
        var value = s.value();
        var result = s.apply(s.prepareSave(new Value(true, "after")), false);
        assertEquals(QuestDocumentsV1.Outcome.CANCELLED, result.outcome());
        assertEquals(value, s.value());
        assertEquals(ORIGINAL, Files.readString(file()));
        try (var files = Files.list(root)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void externalChangesAreRefusedAndRetained() throws Exception {
        var s = original();
        var plan = s.prepareSave(new Value(true, "after"));
        Files.writeString(file(), "external");
        assertThrows(IOException.class, () -> s.apply(plan, true));
        assertEquals("external", Files.readString(file()));
        assertEquals(new Value(false, "before"), s.value());
    }

    @Test
    void newlyCreatedExternalFileCannotBeReplaced() throws Exception {
        var s = ArsenalQuestDocumentsV1.at(root).open(605);
        var plan = s.prepareSave(new Value(true, "after"));
        Files.writeString(file(), "external", StandardOpenOption.CREATE_NEW);
        assertThrows(IOException.class, () -> s.apply(plan, true));
        assertEquals("external", Files.readString(file()));
    }

    @Test
    void twoSessionsCannotOverwriteEachOther() throws Exception {
        var first = original();
        var second = ArsenalQuestDocumentsV1.at(root).open(605);
        var p = second.prepareSave(new Value(true, "second"));
        first.apply(first.prepareSave(new Value(true, "first")), true);
        assertThrows(IOException.class, () -> second.apply(p, true));
        assertEquals("first", ArsenalQuestDocumentsV1.at(root).open(605).value().comment());
    }

    @Test
    void plansCannotBeTransferredOrReplayedAfterChanges() throws Exception {
        var first = original();
        var second = ArsenalQuestDocumentsV1.at(root).open(605);
        var p = first.prepareSave(new Value(true, "after"));
        assertThrows(IOException.class, () -> second.apply(p, true));
        first.apply(p, true);
        assertThrows(IOException.class, () -> first.apply(p, true));
    }

    @Test
    void corruptAndWrongTypedFilesAreNotRepaired() throws Exception {
        for (String bad : List.of("null", "[]", "{\"discard\":1}", "{\"comment\":false}",
                "{\"discard\":true,\"discard\":false}", "{} {}")) {
            Files.writeString(file(), bad);
            assertThrows(IOException.class, () -> ArsenalQuestDocumentsV1.at(root).open(605));
            assertEquals(bad, Files.readString(file()));
        }
    }

    @Test
    void invalidIdentityTraversalAndSymlinksAreRefused() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ArsenalQuestDocumentsV1.at(root).open(0));
        assertThrows(IllegalArgumentException.class, () -> ArsenalQuestDocumentsV1.at(root.resolve("../other")));
        Path external = root.resolve("external.json");
        Files.writeString(external, ORIGINAL);
        Files.createSymbolicLink(file(), external);
        assertThrows(IOException.class, () -> ArsenalQuestDocumentsV1.at(root).open(605));
        assertEquals(ORIGINAL, Files.readString(external));
    }

    @Test
    void publicContractIsIndependentOfMutableBeansJsonAndJavafx() {
        assertEquals(1, ArsenalQuestDocumentsV1.version());
        for (Class<?> c : List.of(ArsenalQuestDocumentsV1.class, ArsenalQuestDocumentsV1.Session.class, Value.class))
            for (var method : c.getDeclaredMethods())
                for (String forbidden : List.of("logbook.bean", "tools.jackson", "javafx"))
                    assertFalse(method.toGenericString().contains(forbidden));
    }
}
