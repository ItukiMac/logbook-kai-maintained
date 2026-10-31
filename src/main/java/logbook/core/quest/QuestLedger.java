package logbook.core.quest;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.ObjectMapper;
import logbook.internal.json.JsonMappers;

/** Append-only evidence. Unknown editions must remain unresolved, never guessed. */
public final class QuestLedger {
    public enum State { UNKNOWN, AVAILABLE, ACCEPTED, ACHIEVED_UNCLAIMED, CLAIMED, EXPIRED }
    public enum Source { GAME, MANUAL, CATALOG }
    public record Key(int questId, String edition, String period) {
        public Key {
            if (questId <= 0) throw new IllegalArgumentException("questId");
            Objects.requireNonNull(edition); Objects.requireNonNull(period);
            if (edition.isBlank() || period.isBlank()) throw new IllegalArgumentException("identity");
        }
    }
    public record Event(int schema, long revision, String eventId, Key key, State state,
                        Source source, String effectiveAt, String recordedAt, String reason) {
        public Event {
            if (schema != 1 || revision < 1) throw new IllegalArgumentException("schema/revision");
            Objects.requireNonNull(eventId); Objects.requireNonNull(key);
            Objects.requireNonNull(state); Objects.requireNonNull(source); Objects.requireNonNull(reason);
            if (eventId.isBlank()) throw new IllegalArgumentException("eventId");
            Instant.parse(effectiveAt); Instant.parse(recordedAt);
            if (source == Source.MANUAL && reason.isBlank()) throw new IllegalArgumentException("correction reason required");
        }
    }
    public record Snapshot(long revision, List<Event> history) {
        public Snapshot { history = List.copyOf(history); }
        /** Both evidence streams remain visible; UI reconciliation is deliberately explicit. */
        public Optional<Event> latest(Key key, Source source) {
            return history.stream().filter(e -> e.key().equals(key) && e.source() == source)
                    .reduce((a,b) -> b);
        }
    }
    private static final Object JVM_LOCK = new Object();
    private final Path directory;
    private final ObjectMapper mapper = JsonMappers.builder().build();
    public QuestLedger(Path directory) { this.directory = directory; }

    public Snapshot snapshot() throws IOException {
        synchronized (JVM_LOCK) {
            Files.createDirectories(directory);
            try (var channel = FileChannel.open(directory.resolve("ledger.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.lock()) { return read(); }
        }
    }
    private Snapshot read() throws IOException {
        List<Event> events = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path p : files.filter(p -> p.getFileName().toString().matches("[0-9]{20}\\.json")).sorted().toList()) {
                Event e = mapper.readValue(Files.readAllBytes(p), Event.class);
                long expected = events.size() + 1L;
                if (e.revision() != expected || !p.getFileName().toString().equals(String.format(Locale.ROOT,"%020d.json", expected)))
                    throw new IOException("Quest ledger revision gap: " + p);
                events.add(e);
            }
        }
        return new Snapshot(events.size(), events);
    }
    public Event append(long expectedRevision, String eventId, Key key, State state,
                        Source source, Instant effectiveAt, String reason) throws IOException {
        synchronized (JVM_LOCK) {
            Files.createDirectories(directory);
            try (var channel = FileChannel.open(directory.resolve("ledger.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.lock()) {
                Snapshot before = read();
                for (Event e : before.history()) if (e.eventId().equals(eventId)) {
                    if (!e.key().equals(key) || e.state()!=state || e.source()!=source || !e.effectiveAt().equals(effectiveAt.toString()) || !e.reason().equals(reason))
                        throw new IllegalArgumentException("Event ID reused with different content");
                    return e;
                }
                if (expectedRevision < 0 && !(expectedRevision == -1 && source == Source.GAME))
                    throw new IllegalArgumentException("Only game observations may append against the latest revision");
                if (expectedRevision != -1 && before.revision() != expectedRevision) throw new ConcurrentModificationException("Quest ledger changed; refresh before editing");
                Event e = new Event(1, before.revision()+1, eventId, key, state, source,
                        effectiveAt.toString(), Instant.now().toString(), reason);
                write(e);
                return e;
            }
        }
    }
    private void write(Event e) throws IOException {
                byte[] bytes = mapper.writeValueAsBytes(e);
                Path temporary = Files.createTempFile(directory,"pending-",".tmp");
                try {
                    try (var output = FileChannel.open(temporary,StandardOpenOption.WRITE)) {
                        ByteBuffer buffer = ByteBuffer.wrap(bytes);
                        while (buffer.hasRemaining()) output.write(buffer);
                        output.force(true);
                    }
                    Files.move(temporary,directory.resolve(String.format(Locale.ROOT,"%020d.json",e.revision())),StandardCopyOption.ATOMIC_MOVE);
                } finally { Files.deleteIfExists(temporary); }
    }
    public record CatalogRecord(String eventId,Key key,String json) { }
    /** Catalog import locks and scans once. A failed import can resume without duplicate rows. */
    public void registerCatalog(List<CatalogRecord> records) throws IOException {
        synchronized(JVM_LOCK) {
            Files.createDirectories(directory);
            try(var channel=FileChannel.open(directory.resolve("ledger.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                var lock=channel.lock()) {
                var before=read();long revision=before.revision();Map<String,Event> existing=new HashMap<>();
                before.history().forEach(e->existing.put(e.eventId(),e));
                List<Event> pending=new ArrayList<>();
                for(var record:records) {
                    var old=existing.get(record.eventId());
                    if(old!=null) {
                        if(old.source()!=Source.CATALOG||!old.key().equals(record.key())||!old.reason().equals(record.json()))throw new IllegalArgumentException("Conflicting catalog identity");
                        continue;
                    }
                    String now=Instant.now().toString();
                    Event next=new Event(1,++revision,record.eventId(),record.key(),State.UNKNOWN,Source.CATALOG,now,now,record.json());
                    existing.put(next.eventId(),next);pending.add(next);
                }
                for(var event:pending)write(event);
            }
        }
    }
}
