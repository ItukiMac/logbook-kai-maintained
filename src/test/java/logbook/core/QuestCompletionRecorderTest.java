package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Instant;
import jakarta.json.Json;
import logbook.core.quest.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuestCompletionRecorderTest {
    @TempDir
    Path directory;

    @Test
    void onlySuccessfulGameResultCreatesCompletionEvidence() throws Exception {
        var ledger = new QuestLedger(directory);
        var recorder = new QuestCompletionRecorder(ledger);
        var now = Instant.parse("2026-10-31T00:00:00Z");
        assertFalse(recorder.record(Json.createObjectBuilder().add("api_result", 0).build(), 901, "failure", now));
        assertFalse(recorder.record(Json.createObjectBuilder().build(), 901, "missing", now));
        assertTrue(recorder.record(Json.createObjectBuilder().add("api_result", 1).build(), 901, "success", now));
        assertTrue(recorder.record(Json.createObjectBuilder().add("api_result", 1).build(), 901, "success", now));
        assertEquals(1, ledger.snapshot().history().size());
        var event = ledger.snapshot().history().get(0);
        assertEquals(QuestLedger.State.CLAIMED, event.state());
        assertEquals("unresolved", event.key().edition());
    }

    @Test
    void failedPersistenceDoesNotClaimSuccess() throws Exception {
        java.nio.file.Files.writeString(directory.resolve("not-a-directory"), "existing");
        var recorder = new QuestCompletionRecorder(new QuestLedger(directory.resolve("not-a-directory")));
        assertThrows(java.io.IOException.class, () -> recorder
                .record(Json.createObjectBuilder().add("api_result", 1).build(), 1, "claim", Instant.now()));
    }
}
