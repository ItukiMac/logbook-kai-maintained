package logbook.internal.kancolle;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.util.*;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.*;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Immutable observation checkpoints, separate from credited arrivals. Each JST day is an
 * independent bounded hash chain. Reading never creates files; incomplete writes are kept
 * and prevent recovery. Hashes detect accidental corruption, not a malicious local writer.
 * Sortie/event semantics and day-boundary invalidation belong to the runtime.
 */
final class PortArrivalJournal {
    private static final String FORMAT = "logbook-port-arrival-tracking-v1";
    private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");
    private static final int MAX_BYTES = 16 * 1024;
    private static final int MAX_RECORDS = 20_000;
    private static final int MAX_DAYS = 8_000;
    private static final Set<String> FIELDS = Set.of("format", "sequence", "previousDigest", "kind",
            "observedAt", "payload", "reason", "digest");
    private static final ObjectMapper JSON = logbook.internal.json.JsonMappers.builder()
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final Path root;

    record Entry(long sequence, String previousDigest, String kind, long observedAt,
            JsonNode payload, String reason, String digest) {
        Entry {
            payload = payload == null ? NullNode.instance : payload.deepCopy();
        }

        @Override
        public JsonNode payload() {
            return payload.deepCopy();
        }
    }

    PortArrivalJournal(Path root) {
        Objects.requireNonNull(root, "journal root");
        for (Path component : root) {
            if (component.toString().equals(".."))
                throw new IllegalArgumentException("Unsafe tracking journal root");
        }
        this.root = root.toAbsolutePath().normalize();
    }

    /** Returns the entire latest day, even when its timestamps are in the caller's future. */
    List<Entry> readLatest() throws IOException {
        safe(root);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS))
            return List.of();
        directory(root);
        Path latest = null;
        int days = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (name.equals(".lock")) {
                    regular(entry);
                    continue;
                }
                if (++days > MAX_DAYS)
                    throw new IOException("Tracking journal exceeds day limit");
                safe(entry);
                if (!name.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
                    throw new IOException("Unknown tracking journal entry");
                try {
                    LocalDate.parse(name);
                } catch (DateTimeException e) {
                    throw new IOException("Invalid tracking journal day", e);
                }
                directory(entry);
                if (latest == null || name.compareTo(latest.getFileName().toString()) > 0)
                    latest = entry;
            }
        }
        if (latest == null)
            return List.of();
        return readDay(latest);
    }

    /**
     * Compare-and-append. The caller must pass the exact last entry it observed, or null for
     * a new journal. A second process cannot append using a stale checkpoint. A failed or
     * interrupted write is never removed or replaced.
     */
    Entry append(Entry expectedLast, String kind, long observedAt, JsonNode payload, String reason) throws IOException {
        try {
            validateText(kind, reason);
            if (observedAt <= 0)
                throw new IOException("Invalid tracking observation time");
            // valueToTree can produce a LongNode(0) that parses as IntNode(0). Normalize through
            // the wire representation so CAS and verification compare the same JSON values.
            JsonNode normalized = payload == null ? NullNode.instance : JSON.readTree(JSON.writeValueAsBytes(payload));
            safe(root);
            Files.createDirectories(root);
            directory(root);
            Path lockPath = root.resolve(".lock");
            safe(lockPath);
            if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS))
                regular(lockPath);
            try (FileChannel lockFile = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                try (var lock = lockFile.tryLock()) {
                    if (lock == null)
                        throw new IOException("Tracking journal is being written by another process");
                    List<Entry> existing = readLatest();
                    Entry actual = existing.isEmpty() ? null : existing.getLast();
                    if (!Objects.equals(expectedLast, actual))
                        throw new IOException("Tracking journal changed since observation");
                    if (actual != null && observedAt < actual.observedAt())
                        throw new IOException("Tracking journal clock moved backwards");
                    String date = date(observedAt);
                    boolean sameDay = actual != null && date.equals(date(actual.observedAt()));
                    long sequence = sameDay ? actual.sequence() + 1 : 1;
                    if (sequence > MAX_RECORDS)
                        throw new IOException("Tracking journal exceeds daily record limit");
                    String previous = sameDay ? actual.digest() : "";
                    Entry unsigned = new Entry(sequence, previous, kind, observedAt, normalized, reason, "");
                    Entry value = new Entry(sequence, previous, kind, observedAt, normalized, reason, digest(unsigned));
                    byte[] bytes = JSON.writeValueAsBytes(document(value, true));
                    if (bytes.length > MAX_BYTES)
                        throw new IOException("Tracking checkpoint exceeds size limit");
                    Path day = root.resolve(date);
                    if (!Files.exists(day, LinkOption.NOFOLLOW_LINKS)) {
                        // Refuse the new day before creating any output that a later read must reject.
                        int days = 0;
                        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
                            for (Path entry : entries) {
                                if (!entry.getFileName().toString().equals(".lock") && ++days >= MAX_DAYS)
                                    throw new IOException("Tracking journal exceeds day limit");
                            }
                        }
                    }
                    safe(day);
                    Files.createDirectories(day);
                    directory(day);
                    Path target = day.resolve(String.format(Locale.ROOT, "%08d.json", sequence));
                    safe(target);
                    // CREATE_NEW is deliberate: a collision or partial prior write must remain intact.
                    try (FileChannel file = FileChannel.open(target, StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                        ByteBuffer buffer = ByteBuffer.wrap(bytes);
                        while (buffer.hasRemaining())
                            file.write(buffer);
                        file.force(true);
                    }
                    if (!value.equals(read(target)))
                        throw new IOException("Tracking checkpoint write verification failed");
                    return value;
                } catch (OverlappingFileLockException e) {
                    throw new IOException("Tracking journal is already being written", e);
                }
            }
        } catch (tools.jackson.core.JacksonException jsonCompatibilityFailure) {
            throw new java.io.IOException("Invalid or unreadable JSON", jsonCompatibilityFailure);
        }
    }

    private List<Entry> readDay(Path day) throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(day)) {
            for (Path entry : entries) {
                if (files.size() >= MAX_RECORDS)
                    throw new IOException("Tracking journal exceeds daily record limit");
                if (!entry.getFileName().toString().matches("[0-9]{8}\\.json"))
                    throw new IOException("Unknown checkpoint file in tracking journal");
                regular(entry);
                files.add(entry);
            }
        }
        if (files.isEmpty())
            throw new IOException("Tracking day has no complete checkpoint");
        files.sort(Comparator.comparing(path -> path.getFileName().toString()));
        List<Entry> result = new ArrayList<>();
        String previous = "";
        long previousTime = 0;
        for (int index = 0; index < files.size(); index++) {
            Path file = files.get(index);
            long sequence = index + 1L;
            if (!file.getFileName().toString().equals(String.format(Locale.ROOT, "%08d.json", sequence)))
                throw new IOException("Tracking checkpoint sequence is missing or inconsistent");
            Entry value = read(file);
            if (value.sequence() != sequence || !value.previousDigest().equals(previous)
                    || value.observedAt() < previousTime
                    || !day.getFileName().toString().equals(date(value.observedAt())))
                throw new IOException("Tracking checkpoint chain is inconsistent");
            result.add(value);
            previous = value.digest();
            previousTime = value.observedAt();
        }
        return List.copyOf(result);
    }

    private static Entry read(Path file) throws IOException {
        try {
            regular(file);
            byte[] bytes;
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(MAX_BYTES + 1);
            }
            if (bytes.length > MAX_BYTES)
                throw new IOException("Tracking checkpoint exceeds size limit");
            JsonNode tree = JSON.readTree(bytes);
            if (tree == null || !tree.isObject())
                throw new IOException("Invalid tracking checkpoint object");
            Set<String> keys = new HashSet<>();
            tree.propertyNames().iterator().forEachRemaining(keys::add);
            if (!keys.equals(FIELDS) || !tree.get("format").isTextual()
                    || !FORMAT.equals(tree.get("format").textValue()))
                throw new IOException("Unknown tracking checkpoint format or fields");
            if (!tree.get("sequence").isIntegralNumber() || !tree.get("sequence").canConvertToLong()
                    || !tree.get("observedAt").isIntegralNumber() || !tree.get("observedAt").canConvertToLong()
                    || !tree.get("previousDigest").isTextual() || !tree.get("digest").isTextual()
                    || !tree.get("kind").isTextual() || !tree.get("reason").isTextual())
                throw new IOException("Invalid tracking checkpoint field types");
            Entry value = new Entry(tree.get("sequence").longValue(), tree.get("previousDigest").textValue(),
                    tree.get("kind").textValue(), tree.get("observedAt").longValue(), tree.get("payload"),
                    tree.get("reason").textValue(), tree.get("digest").textValue());
            validateText(value.kind(), value.reason());
            if (value.sequence() < 1 || value.sequence() > MAX_RECORDS || value.observedAt() <= 0
                    || !(value.previousDigest().isEmpty() || value.previousDigest().matches("[0-9a-f]{64}"))
                    || !value.digest().matches("[0-9a-f]{64}") || !value.digest().equals(digest(value)))
                throw new IOException("Invalid or corrupted tracking checkpoint");
            return value;
        } catch (tools.jackson.core.JacksonException jsonCompatibilityFailure) {
            throw new java.io.IOException("Invalid or unreadable JSON", jsonCompatibilityFailure);
        }
    }

    private static void validateText(String kind, String reason) throws IOException {
        if (kind == null || kind.isBlank() || kind.length() > 64 || reason == null || reason.length() > 256)
            throw new IOException("Invalid tracking checkpoint reason or kind");
    }

    private static ObjectNode document(Entry value, boolean withDigest) {
        ObjectNode tree = JSON.createObjectNode().put("format", FORMAT)
                .put("sequence", value.sequence()).put("previousDigest", value.previousDigest())
                .put("kind", value.kind()).put("observedAt", value.observedAt());
        tree.set("payload", value.payload());
        tree.put("reason", value.reason());
        if (withDigest)
            tree.put("digest", value.digest());
        return tree;
    }

    private static String digest(Entry value) throws IOException {
        try {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(JSON.writeValueAsBytes(document(value, false))));
            } catch (NoSuchAlgorithmException e) {
                throw new IOException("SHA-256 unavailable", e);
            }
        } catch (tools.jackson.core.JacksonException jsonCompatibilityFailure) {
            throw new java.io.IOException("Invalid or unreadable JSON", jsonCompatibilityFailure);
        }
    }

    private static String date(long time) throws IOException {
        String date = Instant.ofEpochMilli(time).atZone(ZONE).toLocalDate().toString();
        if (!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
            throw new IOException("Tracking date outside supported range");
        return date;
    }

    private static void directory(Path path) throws IOException {
        safe(path);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Tracking path is not a directory");
    }

    private static void regular(Path path) throws IOException {
        safe(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Tracking path is not a regular file");
    }

    private static void safe(Path path) throws IOException {
        for (Path part = path; part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part))
                throw new IOException("Symlink in tracking journal path");
        }
    }
}
