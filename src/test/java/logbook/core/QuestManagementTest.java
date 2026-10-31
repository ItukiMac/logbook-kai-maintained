package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import logbook.core.quest.*;
import logbook.core.quest.QuestLedger.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuestManagementTest {
    @TempDir
    Path root;

    @Test
    void unknownCatalogRowsAreNotInventedCompletedAndLaterContradictionIsVisible() throws Exception {
        var key = new Key(1, "regular", "once");
        var ledger = new QuestLedger(root);
        var time = Instant.parse("2026-10-31T00:00:00Z");
        var row = QuestManagement.project(ledger.snapshot(), Map.of(key, "test")).rows().get(0);
        assertEquals(State.UNKNOWN, row.effective());
        ledger.append(0, "manual", key, State.CLAIMED, Source.MANUAL, time, "offline completion");
        ledger.append(1, "observed", key, State.AVAILABLE, Source.GAME, time, "");
        row = QuestManagement.project(ledger.snapshot(), Map.of(key, "test")).rows().get(0);
        assertTrue(row.conflict());
        assertEquals(State.CLAIMED, row.effective());
        assertEquals(State.AVAILABLE, row.observed());
        ledger.append(2, "correction", key, State.AVAILABLE, Source.MANUAL, time, "correct earlier mistake");
        row = QuestManagement.project(ledger.snapshot(), Map.of(key, "test")).rows().get(0);
        assertFalse(row.conflict());
        assertEquals(State.AVAILABLE, row.effective());
        assertEquals(3, ledger.snapshot().history().size());
    }

    @Test
    void importedCatalogSurvivesRestartAndDoesNotInventAvailability() throws Exception {
        var entry = new QuestManagement.CatalogEntry(991, "historical quest", "event-2020", "once", "fixture");
        var ledger = new QuestLedger(root);
        QuestManagement.register(ledger, java.util.List.of(entry));
        QuestManagement.register(ledger, java.util.List.of(entry));
        var restored = new QuestLedger(root).snapshot();
        assertEquals(1, restored.revision());
        var row = QuestManagement.project(restored, Map.of()).rows().get(0);
        assertEquals("historical quest", row.title());
        assertEquals(State.UNKNOWN, row.effective());
    }

    @Test
    void catalogRejectsDuplicateIdentityBeforeWriting() throws Exception {
        var file = root.resolve("import.json");
        java.nio.file.Files.writeString(file,
                "[{\"questId\":1,\"title\":\"a\",\"edition\":\"once\",\"period\":\"once\",\"source\":\"test\"},{\"questId\":1,\"title\":\"b\",\"edition\":\"once\",\"period\":\"once\",\"source\":\"test\"}]");
        assertThrows(IllegalArgumentException.class, () -> QuestManagement.readCatalog(file));
    }

    @Test
    void completedQuestKeepsObservedTitleAfterActiveListDisappears() throws Exception {
        var ledger = new QuestLedger(root);
        var key = new Key(700, "event-2026", "once");
        QuestManagement.rememberObserved(ledger, key, "期間限定任務");
        ledger.append(-1, "claim", key, State.CLAIMED, Source.GAME, Instant.now(), "successful claim");
        var row = QuestManagement.project(new QuestLedger(root).snapshot(), Map.of()).rows().get(0);
        assertEquals("期間限定任務", row.title());
        assertEquals(State.CLAIMED, row.effective());
    }
}
