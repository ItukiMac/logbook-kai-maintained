package logbook.internal.kancolle;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PortArrivalStoreTest {
    @TempDir
    Path temp;
    private static final long T = 1_700_000_000_000L;

    private PortArrival arrival(String id) {
        return new PortArrival(PortArrival.FORMAT, id, T, T + 1000, "1-6", 1, List.of(101, 102), true);
    }

    private PortArrival value() {
        return arrival(UUID.randomUUID().toString());
    }

    @Test
    void roundTripPersistsImmutableEvidenceAndRangeIsHalfOpen() throws Exception {
        var v = value();
        var s = new PortArrivalStore(temp.resolve("journal"));
        s.append(v);
        assertEquals(List.of(v), s.readRange(T, T + 1001));
        assertTrue(s.readRange(T, T + 1000).isEmpty());
        assertTrue(s.readRange(T + 1001, T + 2000).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> v.shipMasterIds().clear());
    }

    @Test
    void duplicateWriteIsIdempotentAndNeverReplacesOriginal() throws Exception {
        var v = value();
        var s = new PortArrivalStore(temp);
        s.append(v);
        s.append(v);
        assertEquals(List.of(v), s.readRange(T, T + 2000));
        var changed = new PortArrival(v.format(), v.sortieId(), T, T + 1001, "1-6", 1, List.of(101, 102), true);
        assertThrows(java.io.IOException.class, () -> s.append(changed));
        assertEquals(List.of(v), s.readRange(T, T + 2000));
    }

    @Test
    void preparationAndUnrelatedDataAreRetainedAndIgnored() throws Exception {
        Path sentinel = temp.resolve("unrelated.txt");
        Files.writeString(sentinel, "keep");
        var s = new PortArrivalStore(temp);
        s.append(value());
        try (var files = Files.walk(temp)) {
            assertEquals(1, files.filter(p -> p.toString().endsWith(".pending")).count());
        }
        assertEquals("keep", Files.readString(sentinel));
        assertEquals(1, s.readRange(T, T + 2000).size());
    }

    @Test
    void malformedRecordDoesNotProducePartialSuccessfulCount() throws Exception {
        var v = value();
        var s = new PortArrivalStore(temp);
        s.append(v);
        Files.writeString(temp.resolve("2023-11").resolve(UUID.randomUUID() + ".json"), "{broken");
        assertThrows(java.io.IOException.class, () -> s.readRange(T, T + 2000));
    }

    @Test
    void oversizedRecordIsRejectedBeforeParsing() throws Exception {
        Files.createDirectories(temp.resolve("2023-11"));
        Files.write(temp.resolve("2023-11").resolve(UUID.randomUUID() + ".json"), new byte[16385]);
        assertThrows(java.io.IOException.class, () -> new PortArrivalStore(temp).readRange(T, T + 2000));
    }

    @Test
    void symlinkRootMonthAndFilesAreRejected() throws Exception {
        Path actual = temp.resolve("actual");
        Files.createDirectory(actual);
        Path alias = temp.resolve("alias");
        Files.createSymbolicLink(alias, actual);
        assertThrows(java.io.IOException.class, () -> new PortArrivalStore(alias).append(value()));
        Files.createSymbolicLink(temp.resolve("2023-11"), actual);
        assertThrows(java.io.IOException.class, () -> new PortArrivalStore(temp).readRange(T, T + 2000));
    }

    @Test
    void mismatchedFilenameIsNotAcceptedAsAnotherSortie() throws Exception {
        var v = value();
        var s = new PortArrivalStore(temp);
        s.append(v);
        Files.copy(temp.resolve("2023-11").resolve(v.sortieId() + ".json"),
                temp.resolve("2023-11").resolve(UUID.randomUUID() + ".json"));
        assertThrows(java.io.IOException.class, () -> s.readRange(T, T + 2000));
    }

    @Test
    void readingAbsentJournalDoesNotCreateIt() throws Exception {
        Path absent = temp.resolve("absent");
        assertTrue(new PortArrivalStore(absent).readRange(T, T + 2000).isEmpty());
        assertFalse(Files.exists(absent));
    }

    @Test
    void rejectsInvalidRecordMetadata() {
        assertThrows(IllegalArgumentException.class,
                () -> new PortArrival("bad", UUID.randomUUID().toString(), T, T + 1, "1-6", 1, List.of(1), true));
        assertThrows(IllegalArgumentException.class, () -> new PortArrival(PortArrival.FORMAT,
                UUID.randomUUID().toString(), T, T - 1, "1-6", 1, List.of(1), true));
        assertThrows(IllegalArgumentException.class, () -> new PortArrival(PortArrival.FORMAT,
                UUID.randomUUID().toString(), T, T + 1, "1-5", 1, List.of(1), true));
    }

    @Test
    void literalNullRecordIsAnIoFailureNotAnUncheckedCrash() throws Exception {
        Path month = temp.resolve("2023-11");
        Files.createDirectories(month);
        Files.writeString(month.resolve(UUID.randomUUID() + ".json"), "null");
        assertThrows(java.io.IOException.class,
                () -> new PortArrivalStore(temp).readRange(1_699_000_000_000L, 1_702_000_000_000L));
    }

}
