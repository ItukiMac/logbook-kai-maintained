package logbook.core.quest;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import logbook.core.LogBookCoreServices;
import logbook.plugin.api.QuestListV1;
import logbook.core.quest.QuestLedger.*;

public final class QuestManagement {
    private QuestManagement() {
    }

    public record CatalogEntry(int questId, String title, String edition, String period, String source) {
        public CatalogEntry {
            new Key(questId, edition, period);
            if (title == null || title.isBlank() || source == null || source.isBlank())
                throw new IllegalArgumentException("Title and source are required");
        }

        public Key key() {
            return new Key(questId, edition, period);
        }
    }

    public static List<CatalogEntry> readCatalog(Path file) throws java.io.IOException {
        if (java.nio.file.Files.size(file) > 8 * 1024 * 1024)
            throw new java.io.IOException("Catalog too large");
        var entries = logbook.internal.json.JsonMappers.builder().build()
                .readValue(java.nio.file.Files.readAllBytes(file), CatalogEntry[].class);
        if (entries.length > 10000)
            throw new IllegalArgumentException("Too many catalog entries");
        Set<Key> seen = new HashSet<>();
        for (var entry : entries)
            if (entry == null || !seen.add(entry.key()))
                throw new IllegalArgumentException("Duplicate or missing catalog identity");
        return List.of(entries);
    }

    /** Idempotent, resumable import. Each entry is durable before the next; errors are propagated. */
    public static void register(QuestLedger ledger, List<CatalogEntry> entries) throws java.io.IOException {
        var json = logbook.internal.json.JsonMappers.builder().build();
        List<QuestLedger.CatalogRecord> records = new ArrayList<>();
        for (var entry : entries) {
            String body = json.writeValueAsString(entry);
            String id = "catalog-" + UUID.nameUUIDFromBytes(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            records.add(new QuestLedger.CatalogRecord(id, entry.key(), body));
        }
        ledger.registerCatalog(records);
    }

    public static void rememberObserved(QuestLedger ledger, Key key, String title) throws java.io.IOException {
        if (title != null && !title.isBlank())
            register(ledger, List
                    .of(new CatalogEntry(key.questId(), title, key.edition(), key.period(), "observed game quest")));
    }

    public record Row(Key key, String title, String source, State observed, State manual, State effective,
            boolean conflict, String recordedAt) {
    }

    public record Table(long revision, List<Row> rows, List<Event> history, String coverage) {
        public Table {
            rows = List.copyOf(rows);
            history = List.copyOf(history);
        }
    }

    public static QuestLedger ledger() {
        return new QuestLedger(Path.of("config", "quest-ledger"));
    }

    public static Table snapshot() throws java.io.IOException {
        try (var input = LogBookCoreServices.getResourceAsStream("logbook/catalog/quests-v1.json")) {
            if (input != null)
                register(ledger(), List.of(
                        logbook.internal.json.JsonMappers.builder().build().readValue(input, CatalogEntry[].class)));
        }
        Map<Key, String> catalog = new LinkedHashMap<>();
        try (var scope = LogBookCoreServices.pinDefinitions()) {
            for (String name : scope.snapshot().resourceNames()) {
                var match = java.util.regex.Pattern.compile("logbook/(?:eventquest|quest)/([0-9]+)\\.json")
                        .matcher(name);
                if (match.matches()) {
                    int id = Integer.parseInt(match.group(1));
                    catalog.put(new Key(id, "unresolved", "unresolved"), "任務 #" + id);
                }
            }
        }
        for (var quest : QuestListV1.snapshot().quests()) {
            Key key = QuestIdentity.resolve(quest.no(),
                    logbook.bean.AppQuestCollection.get().getQuest().get(quest.no()));
            catalog.put(key, quest.title());
        }
        List<CatalogEntry> registrations = new ArrayList<>();
        catalog.forEach((key, title) -> registrations
                .add(new CatalogEntry(key.questId(), title == null ? "任務 #" + key.questId() : title, key.edition(),
                        key.period(), "bundled definitions / observed quest list")));
        register(ledger(), registrations);
        return project(ledger().snapshot(), catalog);
    }

    public static Table project(Snapshot snapshot, Map<Key, String> catalog) {
        Map<Key, String> names = new LinkedHashMap<>(catalog);
        Map<Key, String> sources = new HashMap<>();
        for (var event : snapshot.history()) {
            names.putIfAbsent(event.key(), "任務 #" + event.key().questId());
            if (event.source() == Source.CATALOG) {
                var entry = logbook.internal.json.JsonMappers.builder().build().readValue(event.reason(),
                        CatalogEntry.class);
                names.put(event.key(), entry.title());
                sources.put(event.key(), entry.source());
            }
        }
        List<Row> rows = new ArrayList<>();
        for (var item : names.entrySet()) {
            var game = snapshot.latest(item.getKey(), Source.GAME);
            var manual = snapshot.latest(item.getKey(), Source.MANUAL);
            State observed = game.map(Event::state).orElse(State.UNKNOWN),
                    edited = manual.map(Event::state).orElse(null);
            boolean conflict = game.isPresent() && manual.isPresent() && game.get().revision() > manual.get().revision()
                    && observed != edited;
            rows.add(new Row(item.getKey(), item.getValue(),
                    sources.getOrDefault(item.getKey(), "game observation / manual entry"), observed, edited,
                    edited == null ? observed : edited, conflict,
                    manual.or(() -> game).map(Event::effectiveAt).orElse("")));
        }
        rows.sort(Comparator.comparingInt((Row r) -> r.key().questId()).thenComparing(r -> r.key().edition())
                .thenComparing(r -> r.key().period()));
        return new Table(snapshot.revision(), rows, snapshot.history(),
                "同梱定義・観測済み・履歴・手動追加の任務。未登録の全ゲーム任務を網羅した台帳ではありません。unresolved は開催・周期の未照合です。");
    }

    public static Event correct(long revision, Key key, State state, Instant effectiveAt, String reason)
            throws java.io.IOException {
        return ledger().append(revision, UUID.randomUUID().toString(), key, state, Source.MANUAL, effectiveAt, reason);
    }
}
