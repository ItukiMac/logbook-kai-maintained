package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.ConcurrentModificationException;
import logbook.core.quest.QuestLedger;
import logbook.core.quest.QuestLedger.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuestLedgerTest {
    @TempDir
    Path root;
    final Key key = new Key(901, "event-2026-autumn", "once");
    final Instant time = Instant.parse("2026-10-31T00:00:00Z");

    @Test
    void restartKeepsClaimAndManualCorrectionSeparately() throws Exception {
        var ledger = new QuestLedger(root);
        ledger.append(0, "claim", key, State.CLAIMED, Source.GAME, time, "");
        ledger.append(1, "correction", key, State.UNKNOWN, Source.MANUAL, time, "offline reconciliation");
        var state = new QuestLedger(root).snapshot();
        assertEquals(2, state.revision());
        assertEquals(State.CLAIMED, state.latest(key, Source.GAME).orElseThrow().state());
        assertEquals(State.UNKNOWN, state.latest(key, Source.MANUAL).orElseThrow().state());
        assertThrows(UnsupportedOperationException.class, () -> state.history().clear());
    }

    @Test
    void retryIsIdempotentButConflictingReuseAndStaleEditAreRejected() throws Exception {
        var ledger = new QuestLedger(root);
        var first = ledger.append(0, "claim", key, State.CLAIMED, Source.GAME, time, "");
        assertEquals(first, ledger.append(0, "claim", key, State.CLAIMED, Source.GAME, time, ""));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.append(1, "claim", key, State.UNKNOWN, Source.GAME, time, ""));
        assertThrows(ConcurrentModificationException.class,
                () -> ledger.append(0, "edit", key, State.UNKNOWN, Source.MANUAL, time, "reason"));
        assertEquals(1, ledger.snapshot().revision());
    }

    @Test
    void reusedIdAndRecurringPeriodsAreSeparate() throws Exception {
        var ledger = new QuestLedger(root);
        ledger.append(0, "old", key, State.CLAIMED, Source.GAME, time, "");
        var next = new Key(901, "event-2027", "once");
        assertTrue(ledger.snapshot().latest(next, Source.GAME).isEmpty());
        assertNotEquals(new Key(1, "daily", "2026-10-31"), new Key(1, "daily", "2026-11-01"));
    }

    @Test
    void corruptOrMissingHistoryFailsClosed() throws Exception {
        var ledger = new QuestLedger(root);
        ledger.append(0, "claim", key, State.CLAIMED, Source.GAME, time, "");
        Files.move(root.resolve("00000000000000000001.json"), root.resolve("00000000000000000002.json"));
        assertThrows(java.io.IOException.class, ledger::snapshot);
    }

    @Test
    void manualReasonRequiredAndPendingFileIgnored() throws Exception {
        var ledger = new QuestLedger(root);
        assertThrows(IllegalArgumentException.class,
                () -> ledger.append(0, "edit", key, State.CLAIMED, Source.MANUAL, time, ""));
        Files.writeString(root.resolve("pending-interrupted.tmp"), "partial");
        assertEquals(0, ledger.snapshot().revision());
    }
}
