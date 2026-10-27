package logbook.internal.kancolle;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import logbook.internal.json.JsonMappers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Independently generated Jackson2 wire bytes must remain readable and hash stable. */
class Jackson3JournalCompatibilityTest {
    @TempDir Path root;
    private static final long T = Instant.parse("2026-10-27T01:00:00Z").toEpochMilli();
    private final com.fasterxml.jackson.databind.ObjectMapper old = new com.fasterxml.jackson.databind.ObjectMapper();
    private Path journalRoot() { return root.resolve("tracking-v1"); }
    private Path day() { return journalRoot().resolve("2026-10-27"); }
    private String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }

    private byte[] oldCheckpoint() throws Exception {
        var payload = old.createObjectNode().put("cell", 2).put("label", "艦隊/記録\n\"quoted\"\\");
        var row = old.createObjectNode().put("format", "logbook-port-arrival-tracking-v1")
                .put("sequence", 1L).put("previousDigest", "").put("kind", "START").put("observedAt", T);
        row.set("payload", payload); row.put("reason", "旧記録");
        row.put("digest", sha(old.writeValueAsBytes(row)));
        return old.writeValueAsBytes(row);
    }

    @Test void newReaderAcceptsRealOldEngineHashAndPreservesExactOldBytes() throws Exception {
        Files.createDirectories(day());
        byte[] before = oldCheckpoint(); Path first = day().resolve("00000001.json"); Files.write(first, before);
        var journal = new PortArrivalJournal(journalRoot());
        var record = journal.readLatest().getFirst();
        assertEquals("旧記録", record.reason()); assertEquals(2, record.payload().path("cell").intValue());
        assertArrayEquals(before, Files.readAllBytes(first));
        var nextPayload = JsonMappers.builder().build().createObjectNode().put("cell", 4).put("label", "次の記録");
        var next = journal.append(record, "STEP", T + 1, nextPayload, "続き");
        assertEquals(record.digest(), next.previousDigest());
        assertEquals(List.of(record, next), journal.readLatest());
        assertArrayEquals(before, Files.readAllBytes(first));
        byte[] appended = Files.readAllBytes(day().resolve("00000002.json"));
        var legacyNode = (com.fasterxml.jackson.databind.node.ObjectNode) old.readTree(appended);
        String actual = legacyNode.remove("digest").textValue();
        assertEquals(sha(old.writeValueAsBytes(legacyNode)), actual, "Jackson2 must independently reproduce the new hash");
    }

    @Test void oldArrivalRecordIsReadWithoutRewritingAndNewRecordIsOldEngineReadable() throws Exception {
        var before = new PortArrival(PortArrival.FORMAT, "11111111-1111-4111-8111-111111111111",
                T, T + 1000, "1-6", 1, List.of(101, 102), true);
        Path month = root.resolve("arrivals/2026-10"); Files.createDirectories(month);
        Path file = month.resolve(before.sortieId() + ".json"); byte[] bytes = old.writeValueAsBytes(before);
        Files.write(file, bytes);
        var store = new PortArrivalStore(root.resolve("arrivals"));
        assertArrayEquals(bytes, Files.readAllBytes(file));
        var modern = JsonMappers.builder().build().readValue(bytes, PortArrival.class);
        assertEquals(before, modern);
        var after = new PortArrival(PortArrival.FORMAT, "22222222-2222-4222-8222-222222222222",
                T + 2000, T + 3000, "1-6", 1, List.of(101, 102), true);
        store.append(after);
        assertEquals(after, old.readValue(Files.readAllBytes(month.resolve(after.sortieId() + ".json")), PortArrival.class));
        assertArrayEquals(bytes, Files.readAllBytes(file));
    }

    @Test void malformedLegacyCheckpointIsStillAnIoFailureWithoutRepair() throws Exception {
        Files.createDirectories(day()); Path file = day().resolve("00000001.json");
        byte[] bytes = "{\"format\":\"interrupted".getBytes(StandardCharsets.UTF_8); Files.write(file, bytes);
        assertThrows(java.io.IOException.class, () -> new PortArrivalJournal(journalRoot()).readLatest());
        assertArrayEquals(bytes, Files.readAllBytes(file));
    }
}
