package logbook.internal.kancolle;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Synthetic journals only: old records and failed writes are deliberately retained. */
class PortArrivalJournalTest {
    @TempDir Path temp;
    private static final ObjectMapper JSON = logbook.internal.json.JsonMappers.builder().build();
    private static final long T = Instant.parse("2026-10-27T01:00:00Z").toEpochMilli();
    private Path root() { return temp.resolve("tracking-v1"); }
    private Path day() { return root().resolve("2026-10-27"); }
    private Path first() { return day().resolve("00000001.json"); }
    private ObjectNode payload(int cell) { return JSON.createObjectNode().put("cell", cell).put("sortieId", "synthetic"); }
    private PortArrivalJournal journal() { return new PortArrivalJournal(root()); }
    private PortArrivalJournal.Entry start() throws IOException { return journal().append(null, "START", T, payload(2), "observed start"); }
    private void corrupt(String bytes) throws Exception { start(); Files.writeString(first(), bytes); }

    @Test void absentReadDoesNotCreateAnything() throws Exception {
        assertTrue(journal().readLatest().isEmpty());
        assertFalse(Files.exists(root()));
    }
    @Test void appendRoundTripPreservesEveryPreviousByteAndChain() throws Exception {
        var first = start(); byte[] bytes = Files.readAllBytes(first());
        var second = journal().append(first, "ADVANCE", T + 1, payload(4), "observed next");
        assertEquals(List.of(first, second), journal().readLatest());
        assertEquals(1, first.sequence()); assertEquals(2, second.sequence());
        assertEquals(first.digest(), second.previousDigest());
        assertArrayEquals(bytes, Files.readAllBytes(first()));
        assertThrows(UnsupportedOperationException.class, () -> journal().readLatest().clear());
    }
    @Test void staleWriterCannotForkOrOverwriteTheLatestState() throws Exception {
        var first = start(); var writer = journal();
        var next = writer.append(first, "ADVANCE", T + 1, payload(4), "ok");
        assertThrows(IOException.class, () -> journal().append(first, "ADVANCE", T + 2, payload(5), "stale"));
        assertThrows(IOException.class, () -> journal().append(null, "START", T + 2, payload(2), "stale"));
        assertEquals(List.of(first, next), journal().readLatest());
    }
    @Test void payloadIsDetachedFromBothInputAndReturnedEntries() throws Exception {
        var input = payload(2); var first = journal().append(null, "START", T, input, "ok");
        input.put("cell", 99); ((ObjectNode) first.payload()).put("cell", 88);
        assertEquals(2, first.payload().get("cell").intValue());
        assertEquals(2, journal().readLatest().getFirst().payload().get("cell").intValue());
    }
    @Test void clockRollbackCannotAppendAfterNewerObservation() throws Exception {
        var first = start(); var next = journal().append(first, "ADVANCE", T + 10, payload(4), "ok");
        assertThrows(IOException.class, () -> journal().append(next, "ADVANCE", T + 9, payload(5), "backwards"));
        assertEquals(next, journal().readLatest().getLast());
    }
    @Test void aNewDayStartsANewChainAndKeepsTheOldBytes() throws Exception {
        var first = start(); byte[] bytes = Files.readAllBytes(first());
        long nextDay = Instant.parse("2026-10-27T15:00:00Z").toEpochMilli();
        var boundary = journal().append(first, "BOUNDARY", nextDay, null, "new JST day");
        assertEquals(1, boundary.sequence()); assertEquals("", boundary.previousDigest());
        assertEquals(List.of(boundary), journal().readLatest());
        assertArrayEquals(bytes, Files.readAllBytes(first()));
        assertTrue(Files.exists(root().resolve("2026-10-28/00000001.json")));
        assertThrows(IOException.class, () -> journal().append(boundary, "START", T + 1, payload(2), "old day"));
    }
    @Test void futureDayIsReturnedRatherThanHiddenFromRuntimeClockChecks() throws Exception {
        var first = start(); var future = journal().append(first, "BOUNDARY", T + 86_400_000L, null, "future");
        assertEquals(List.of(future), journal().readLatest());
    }
    @Test void truncatedWriteBlocksBothRecoveryAndAppendWithoutRepairingIt() throws Exception {
        corrupt("{\"format\":"); byte[] before = Files.readAllBytes(first());
        assertThrows(IOException.class, () -> journal().readLatest());
        assertThrows(IOException.class, () -> journal().append(null, "START", T + 1, payload(2), "unsafe retry"));
        assertArrayEquals(before, Files.readAllBytes(first()));
    }
    @Test void missingSequenceIsNotSilentlyBridged() throws Exception {
        var first = start(); journal().append(first, "ADVANCE", T + 1, payload(4), "ok");
        Files.move(first(), day().resolve("00000003.json"));
        assertThrows(IOException.class, () -> journal().readLatest());
    }
    @Test void duplicateJsonKeysAreRejected() throws Exception {
        start(); String body = Files.readString(first());
        Files.writeString(first(), body.replaceFirst("\\{", "{\"sequence\":1,"));
        assertThrows(IOException.class, () -> journal().readLatest());
    }
    @Test void trailingJsonIsRejected() throws Exception {
        start(); Files.writeString(first(), Files.readString(first()) + " {}");
        assertThrows(IOException.class, () -> journal().readLatest());
    }
    @Test void unknownFormatIsRejected() throws Exception {
        start(); var json = (ObjectNode) JSON.readTree(first().toFile()); json.put("format", "future-format");
        Files.writeString(first(), json.toString());
        assertThrows(IOException.class, () -> journal().readLatest());
    }
    @Test void changedPayloadOrDigestIsRejected() throws Exception {
        start(); var json = (ObjectNode) JSON.readTree(first().toFile());
        ((ObjectNode) json.get("payload")).put("cell", 99); Files.writeString(first(), json.toString());
        assertThrows(IOException.class, () -> journal().readLatest());
    }
    @Test void filenameAndEmbeddedSequenceMustAgree() throws Exception {
        start(); var json = (ObjectNode) JSON.readTree(first().toFile()); json.put("sequence", 2);
        Files.writeString(first(), json.toString()); assertThrows(IOException.class, () -> journal().readLatest());
    }
    @Test void oversizedRecordsAreRejectedBeforeParsing() throws Exception {
        Files.createDirectories(day()); Files.write(first(), new byte[16_385]);
        assertThrows(IOException.class, () -> journal().readLatest());
    }
    @Test void oversizedAppendDoesNotCreateAnEmptyOrPartialEvent() throws Exception {
        var first = start(); var huge = JSON.createObjectNode().put("huge", "x".repeat(20_000));
        assertThrows(IOException.class, () -> journal().append(first, "ADVANCE", T + 1, huge, "oversized"));
        assertEquals(List.of(first), journal().readLatest());
    }
    @Test void symlinkRootIsRejectedWithoutFollowingIt() throws Exception {
        Path actual = temp.resolve("actual"); Files.createDirectory(actual); Files.createSymbolicLink(root(), actual);
        assertThrows(IOException.class, () -> journal().readLatest());
        assertThrows(IOException.class, () -> journal().append(null, "START", T, payload(2), "bad"));
        try (var entries = Files.list(actual)) { assertEquals(0, entries.count()); }
    }
    @Test void symlinkDayOrEntryIsRejected() throws Exception {
        Path actual = temp.resolve("actual"); Files.createDirectory(actual); Files.createDirectories(root());
        Files.createSymbolicLink(day(), actual); assertThrows(IOException.class, () -> journal().readLatest());
        Path another = temp.resolve("other"); Files.createDirectories(another.resolve("2026-10-27"));
        Files.writeString(actual.resolve("fixture.json"), "{}");
        Files.createSymbolicLink(another.resolve("2026-10-27/00000001.json"), actual.resolve("fixture.json"));
        assertThrows(IOException.class, () -> new PortArrivalJournal(another).readLatest());
    }
    @Test void symlinkLockCannotRedirectTheWriter() throws Exception {
        Files.createDirectories(root()); Path target = temp.resolve("untouched"); Files.writeString(target, "sentinel");
        Files.createSymbolicLink(root().resolve(".lock"), target);
        assertThrows(IOException.class, () -> journal().append(null, "START", T, payload(2), "bad"));
        assertEquals("sentinel", Files.readString(target));
    }
    @Test void unknownEntriesAndEmptyDayAreNotIgnored() throws Exception {
        Files.createDirectories(day()); assertThrows(IOException.class, () -> journal().readLatest());
        Files.writeString(day().resolve("unexpected.txt"), "bad"); assertThrows(IOException.class, () -> journal().readLatest());
    }
    @Test void impossibleCalendarDirectoryIsRejected() throws Exception {
        Files.createDirectories(root().resolve("2026-02-30"));
        assertThrows(IOException.class, () -> journal().readLatest());
    }
    @Test void nullAndCoercedNumericRecordsAreRejected() throws Exception {
        corrupt("null"); assertThrows(IOException.class, () -> journal().readLatest());
        Path another = temp.resolve("coerced"); var j = new PortArrivalJournal(another);
        j.append(null, "START", T, payload(2), "ok"); Path target = another.resolve("2026-10-27/00000001.json");
        var json = (ObjectNode) JSON.readTree(target.toFile()); json.put("sequence", "1");
        Files.writeString(target, json.toString()); assertThrows(IOException.class, j::readLatest);
    }
    @Test void unknownFieldsAreRejectedInsteadOfDroppingResumeInformation() throws Exception {
        start(); var json = (ObjectNode) JSON.readTree(first().toFile()); json.put("futureResumeMode", true);
        Files.writeString(first(), json.toString()); assertThrows(IOException.class, () -> journal().readLatest());
    }
    @Test void nonDirectoryRootIsRejected() throws Exception {
        Files.writeString(root(), "sentinel");
        assertThrows(IOException.class, () -> journal().readLatest());
        assertThrows(IOException.class, () -> journal().append(null, "START", T, payload(2), "bad"));
        assertEquals("sentinel", Files.readString(root()));
    }
    @Test void heldWriterLockRefusesConcurrentAppendWithoutPartialOutput() throws Exception {
        var first = start();
        try (var channel = java.nio.channels.FileChannel.open(root().resolve(".lock"), StandardOpenOption.WRITE);
                var lock = channel.lock()) {
            assertThrows(IOException.class, () -> journal().append(first, "ADVANCE", T + 1, payload(4), "contended"));
        }
        assertEquals(List.of(first), journal().readLatest());
        assertFalse(Files.exists(day().resolve("00000002.json")));
    }
    @Test void newDayCasStillRequiresThePreviousDaysLatestEntry() throws Exception {
        var first = start();
        assertThrows(IOException.class, () -> journal().append(null, "BOUNDARY", T + 86_400_000L, null, "stale day"));
        assertEquals(List.of(first), journal().readLatest());
        assertFalse(Files.exists(root().resolve("2026-10-28")));
    }
    @Test void smallLongValuesFromRecordPayloadsRoundTripWithoutFalseCorruption() throws Exception {
        var first = journal().append(null, "START", T, payload(2).put("captureAt", 0L), "record payload");
        assertEquals(List.of(first), journal().readLatest());
        var second = journal().append(first, "ADVANCE", T + 1, payload(4).put("captureAt", 1L), "record payload");
        assertEquals(List.of(first, second), journal().readLatest());
    }
}
