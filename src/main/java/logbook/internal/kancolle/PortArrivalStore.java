package logbook.internal.kancolle;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;

/** Append-only, bounded new journal; never traverses or rewrites the existing battle log. */
public final class PortArrivalStore {
    private static final int MAX_BYTES = 16 * 1024;
    private static final int MAX_RECORDS = 20_000;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Path root;
    public PortArrivalStore(Path root) {
        this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
        if (root.toAbsolutePath().toString().contains("..")) throw new IllegalArgumentException("Unsafe journal root");
    }
    public void append(PortArrival record) throws IOException {
        Objects.requireNonNull(record);
        safe(root); Files.createDirectories(root); safe(root);
        Path month = root.resolve(YearMonth.from(Instant.ofEpochMilli(record.arrivedAt())
                .atZone(ZoneId.of("Asia/Tokyo"))).toString());
        safe(month); Files.createDirectories(month); safe(month);
        Path target = month.resolve(record.sortieId() + ".json");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!record.equals(read(target))) throw new IOException("Existing arrival identity has different content");
            return;
        }
        byte[] data = JSON.writeValueAsBytes(record);
        if (data.length > MAX_BYTES) throw new IOException("Arrival record too large");
        Path pending = month.resolve("." + record.sortieId() + "-" + UUID.randomUUID() + ".pending");
        try (FileChannel file = FileChannel.open(pending, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer bytes = ByteBuffer.wrap(data);
            while (bytes.hasRemaining()) file.write(bytes);
            file.force(true);
        }
        if (!record.equals(read(pending))) throw new IOException("Arrival preparation verification failed");
        // Link creation atomically publishes an already-written file without replacing a destination.
        // Unsupported filesystems fail closed. The original pending bytes are deliberately retained.
        try { Files.createLink(target, pending); }
        catch (FileAlreadyExistsException e) {
            if (!record.equals(read(target))) throw new IOException("Conflicting arrival publication", e);
        }
    }
    public List<PortArrival> readRange(long fromInclusive, long toExclusive) throws IOException {
        if (fromInclusive < 0 || toExclusive <= fromInclusive) return List.of();
        safe(root);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Journal root is not a directory");
        String fromMonth = month(fromInclusive), toMonth = month(toExclusive - 1);
        Map<String, PortArrival> records = new HashMap<>();
        int checked = 0;
        try (DirectoryStream<Path> months = Files.newDirectoryStream(root)) {
            for (Path dir : months) {
                String name = dir.getFileName().toString();
                if (!name.matches("[0-9]{4}-[0-9]{2}") || name.compareTo(fromMonth) < 0 || name.compareTo(toMonth) > 0) continue;
                safe(dir);
                if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid arrival month");
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, "*.json")) {
                    for (Path entry : entries) {
                        if (++checked > MAX_RECORDS) throw new IOException("Arrival query exceeds bounded record count");
                        PortArrival value = read(entry);
                        if (!entry.getFileName().toString().equals(value.sortieId() + ".json")
                                || !name.equals(month(value.arrivedAt()))) throw new IOException("Misplaced arrival record");
                        if (value.arrivedAt() < fromInclusive || value.arrivedAt() >= toExclusive) continue;
                        PortArrival prior = records.putIfAbsent(value.sortieId(), value);
                        if (prior != null && !prior.equals(value)) throw new IOException("Conflicting arrival identity");
                    }
                }
            }
        }
        return records.values().stream().sorted(Comparator.comparingLong(PortArrival::arrivedAt)).toList();
    }
    private static String month(long epoch) {
        return YearMonth.from(Instant.ofEpochMilli(epoch).atZone(ZoneId.of("Asia/Tokyo"))).toString();
    }
    private static PortArrival read(Path path) throws IOException {
        safe(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a regular arrival record");
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Oversized arrival record");
            try {
                PortArrival value = JSON.readValue(bytes, PortArrival.class);
                if (value == null) throw new IOException("Null arrival record");
                return value;
            }
            catch (RuntimeException e) { throw new IOException("Invalid arrival record", e); }
        }
    }
    private static void safe(Path path) throws IOException {
        for (Path part = path; part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part)) throw new IOException("Symlink in arrival journal path");
        }
    }
}
