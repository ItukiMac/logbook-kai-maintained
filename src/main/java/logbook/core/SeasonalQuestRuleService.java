package logbook.core;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import logbook.bean.QuestList.Quest;
import logbook.plugin.api.GameDefinitionSnapshot;

/**
 * Selects an explicitly reviewed edition. No network, writes, title-based year inference,
 * or fallback from an updated/missing data-pack rule to a bundled historical rule.
 * The catalog ships with the application; data packs cannot silently replace its evidence.
 */
public final class SeasonalQuestRuleService {
    public static final String CATALOG = "logbook/seasonal/catalog-v1.json";
    public static final String FORMAT = "logbook-seasonal-rules-v1";
    public static final Duration MAX_OBSERVATION_AGE = Duration.ofHours(24);
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    private static final int MAX_BYTES = 1_048_576;
    private static final Set<Integer> KNOWN_IDS = Set.of(441, 840, 841, 843, 906, 907, 909,
        953, 954, 955, 987, 1039, 1040);
    private static final ObjectMapper JSON = logbook.internal.json.JsonMappers.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final DateTimeFormatter LOG_DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
        .withResolverStyle(ResolverStyle.STRICT);

    public enum Status {
        NOT_SEASONAL("通常定義"), SELECTED("季節版確認済み"),
        OBSERVATION_MISSING("未確定：任務の再観測が必要です"),
        OBSERVATION_STALE("未確定：任務の観測が古いか日時が不正です"),
        OUTSIDE_WINDOW("未確定：確認済みの有効期間外です"),
        IDENTITY_MISMATCH("未確定：観測した任務と確認済みの版が一致しません"),
        EVIDENCE_INCOMPLETE("未確定：年度・内部ID・条件の根拠が不足しています"),
        EDITION_CONFLICT("未確定：適用候補の版が競合しています"),
        DEFINITION_CHANGED("未確定：定義更新後の再照合が必要です"),
        REGISTRY_INVALID("未確定：季節版の根拠記録を読み込めません");
        private final String message;
        Status(String message) { this.message = message; }
        public String message() { return message; }
    }

    /** Only live quest-list decoding supplies observedAt; persisted quest data does not. */
    public record Observation(Integer questId, String title, String detail, Integer category,
            Integer type, Instant observedAt) {
        public static Observation from(Quest quest) {
            return quest == null ? null : new Observation(quest.getNo(), quest.getTitle(), quest.getDetail(),
                quest.getCategory(), quest.getType(), quest.getObservedAt());
        }
        boolean complete() {
            return questId != null && title != null && !title.isBlank() && detail != null && !detail.isBlank()
                && category != null && type != null && observedAt != null;
        }
    }

    /** Immutable decision with a live clock guard, so a previously selected rule can expire. */
    public static final class Selection {
        private final Status initial;
        private final String editionId;
        private final int year;
        private final Instant from, until, observedAt;
        private final Clock clock;
        private final Observation identity;
        private Selection(Status initial, Edition edition, Observation observation, Clock clock) {
            this.initial = initial; this.clock = clock;
            this.identity = observation;
            this.editionId = edition == null ? null : edition.editionId;
            this.year = edition == null ? 0 : edition.year;
            this.from = edition == null ? null : edition.from;
            this.until = edition == null ? null : edition.until;
            this.observedAt = observation == null ? null : observation.observedAt;
        }
        public Status status() {
            if (initial != Status.SELECTED) return initial;
            Instant now = clock.instant();
            if (!within(now, from, until)) return Status.OUTSIDE_WINDOW;
            if (!fresh(observedAt, now)) return Status.OBSERVATION_STALE;
            return Status.SELECTED;
        }
        public boolean usable() { return status() == Status.NOT_SEASONAL || status() == Status.SELECTED; }
        public String message() {
            return status() == Status.SELECTED ? status().message() + "：" + editionId : status().message();
        }
        public String editionId() { return editionId; }
        public int editionYear() { return year; }
        public Instant validFrom() { return from; }
        public Instant validUntil() { return until; }
        public boolean matches(Observation observed) {
            return initial == Status.NOT_SEASONAL || observed != null && observed.complete() && identity != null
                && Objects.equals(identity.questId, observed.questId)
                && Objects.equals(identity.category, observed.category) && Objects.equals(identity.type, observed.type)
                && Objects.equals(identity.observedAt, observed.observedAt)
                && normalize(identity.title).equals(normalize(observed.title))
                && normalize(identity.detail).equals(normalize(observed.detail));
        }
        public Integer questId() { return identity == null ? null : identity.questId; }
        public Instant refreshAt() {
            if (initial != Status.SELECTED) return null;
            Instant stale = observedAt.plus(MAX_OBSERVATION_AGE);
            return until.isBefore(stale) ? until : stale;
        }
        public boolean permitsLog(Instant instant) {
            if (initial == Status.NOT_SEASONAL) return true;
            return usable() && instant != null && within(instant, from, until) && !instant.isAfter(clock.instant());
        }
        public boolean permitsLog(String jstDate) {
            if (initial == Status.NOT_SEASONAL) return true;
            try { return permitsLog(LocalDateTime.parse(jstDate, LOG_DATE).atZone(JST).toInstant()); }
            catch (RuntimeException invalid) { return false; }
        }
    }

    public record Resolution(Selection selection, byte[] definition) {
        public Resolution { definition = definition == null ? null : definition.clone(); }
        @Override public byte[] definition() { return definition == null ? null : definition.clone(); }
    }

    private record Edition(String editionId, int questId, int year, String origin, String title,
        String detail, int category, int type, Instant from, Instant until, String baseHash,
        String resource, String ruleHash, boolean evidenceComplete) { }

    private final List<Edition> editions;
    private final Set<Integer> managedIds;
    private final boolean registryValid;
    private final Function<String, byte[]> resources;
    private final Clock clock;

    /** Injection boundary for deterministic, synthetic tests; it never writes or downloads. */
    public SeasonalQuestRuleService(byte[] catalog, Function<String, byte[]> resources, Clock clock) {
        this.resources = Objects.requireNonNull(resources); this.clock = Objects.requireNonNull(clock);
        List<Edition> parsed = new ArrayList<>(); Set<Integer> ids = new HashSet<>(KNOWN_IDS);
        boolean valid = false;
        try {
            if (catalog == null || catalog.length > MAX_BYTES) throw new IOException("Missing/large catalog");
            JsonNode root = JSON.readTree(catalog);
            require(root != null && root.isObject() && FORMAT.equals(root.path("format").asText()), "format");
            require(root.path("managedQuestIds").isArray() && root.path("editions").isArray(), "arrays");
            require(root.path("editions").size() <= 128, "too many editions");
            for (JsonNode id : root.path("managedQuestIds")) {
                require(id.isIntegralNumber() && id.canConvertToInt() && id.intValue() > 0, "ID");
                ids.add(id.intValue());
            }
            Set<String> names = new HashSet<>();
            for (JsonNode row : root.path("editions")) {
                String name = text(row, "editionId");
                require(names.add(name), "duplicate edition ID");
                int id = integer(row, "questId"), year = integer(row, "editionYear");
                require(ids.contains(id) && year >= 2013 && year <= 9999, "unmanaged ID/year");
                String origin = text(row, "origin");
                require(Set.of("quest", "eventquest").contains(origin), "origin");
                String resource = text(row, "definitionResource");
                require(resource.matches("logbook/seasonal/editions/[a-zA-Z0-9-]+\\.json"), "resource path");
                Instant from = Instant.parse(text(row, "validFrom")), until = Instant.parse(text(row, "validUntil"));
                require(from.isBefore(until) && from.atZone(JST).getYear() == year
                    && until.minusNanos(1).atZone(JST).getYear() == year, "edition window/year");
                String baseHash = text(row, "baseDefinitionSha256"), ruleHash = text(row, "definitionSha256");
                require(baseHash.matches("[a-f0-9]{64}") && ruleHash.matches("[a-f0-9]{64}"), "hash");
                int category = integer(row, "category"), type = integer(row, "type");
                require((category == 2 || category == 4) && type >= 1 && type <= 5, "quest observation type");
                boolean evidence = row.path("identityVerified").isBoolean() && row.path("identityVerified").booleanValue()
                    && row.path("windowVerified").isBoolean() && row.path("windowVerified").booleanValue()
                    && row.path("modeledConditionsVerified").isBoolean() && row.path("modeledConditionsVerified").booleanValue()
                    && row.path("selectionBlockers").isArray() && row.path("selectionBlockers").isEmpty()
                    && hasSources(row.path("sources"));
                parsed.add(new Edition(name, id, year, origin, text(row, "title"), text(row, "detail"),
                    category, type, from, until, baseHash, resource, ruleHash, evidence));
            }
            valid = true;
        } catch (IOException | RuntimeException invalid) { parsed.clear(); }
        editions = List.copyOf(parsed); managedIds = Set.copyOf(ids); registryValid = valid;
    }

    private static final class Holder {
        static final SeasonalQuestRuleService INSTANCE = new SeasonalQuestRuleService(readBundled(CATALOG),
            SeasonalQuestRuleService::readBundled, Clock.systemUTC());
    }
    public static SeasonalQuestRuleService bundled() { return Holder.INSTANCE; }

    public Resolution resolve(int questId, String origin, byte[] original, Observation observed) {
        boolean seasonal = managedIds.contains(questId) || "eventquest".equals(origin);
        if (!seasonal) return result(Status.NOT_SEASONAL, null, observed, original);
        if (!registryValid) return result(Status.REGISTRY_INVALID, null, observed, null);
        if (observed == null || !observed.complete()) return result(Status.OBSERVATION_MISSING, null, observed, null);
        if (observed.questId != questId) return result(Status.IDENTITY_MISMATCH, null, observed, null);
        Instant now = clock.instant();
        if (!fresh(observed.observedAt, now)) return result(Status.OBSERVATION_STALE, null, observed, null);
        List<Edition> byId = editions.stream().filter(e -> e.questId == questId).toList();
        if (byId.isEmpty()) return result(Status.EVIDENCE_INCOMPLETE, null, observed, null);
        List<Edition> inPeriod = byId.stream().filter(e -> e.year == observed.observedAt.atZone(JST).getYear()
            && within(now, e.from, e.until) && within(observed.observedAt, e.from, e.until)).toList();
        if (inPeriod.isEmpty()) return result(Status.OUTSIDE_WINDOW, null, observed, null);
        List<Edition> matching = inPeriod.stream().filter(e -> normalize(e.title).equals(normalize(observed.title))
            && normalize(e.detail).equals(normalize(observed.detail))
            && e.category == observed.category && e.type == observed.type).toList();
        if (matching.isEmpty()) return result(Status.IDENTITY_MISMATCH, null, observed, null);
        // Even an incompletely reviewed competing edition prevents a silent arbitrary winner.
        if (matching.size() != 1) return result(Status.EDITION_CONFLICT, null, observed, null);
        Edition chosen = matching.getFirst();
        if (!chosen.evidenceComplete) return result(Status.EVIDENCE_INCOMPLETE, null, observed, null);
        if (!chosen.origin.equals(origin) || original == null || !chosen.baseHash.equals(sha256(original)))
            return result(Status.DEFINITION_CHANGED, null, observed, null);
        try {
            byte[] candidate = resources.apply(chosen.resource);
            if (candidate == null || candidate.length > MAX_BYTES || !chosen.ruleHash.equals(sha256(candidate)))
                return result(Status.DEFINITION_CHANGED, null, observed, null);
            // Reuse the real typed validator, including fleet operands and port-arrival invariants.
            PreparedGameDefinitions.prepare(new GameDefinitionSnapshot("seasonal-" + chosen.editionId,
                Map.of("logbook/" + chosen.origin + "/" + questId + ".json", candidate)));
            JsonNode rule = logbook.internal.json.JsonMappers.builder().enable(tools.jackson.core.json.JsonReadFeature.ALLOW_JAVA_COMMENTS).build().readTree(candidate);
            require((chosen.category == 2 ? "出撃" : "遠征").equals(rule.path("type").asText()), "category differs from rule");
            String reset = switch (chosen.type) {
                case 1 -> "デイリー"; case 2 -> "ウィークリー"; case 3 -> "マンスリー";
                case 4 -> "単発"; default -> null;
            };
            require(reset != null && reset.equals(rule.path("resetType").asText()), "period differs from observed type");
            return result(Status.SELECTED, chosen, observed, candidate);
        } catch (RuntimeException invalid) { return result(Status.DEFINITION_CHANGED, null, observed, null); }
    }

    private Resolution result(Status status, Edition edition, Observation observed, byte[] bytes) {
        return new Resolution(new Selection(status, edition, observed, clock), bytes);
    }
    private static boolean fresh(Instant observed, Instant now) {
        return observed != null && !observed.isAfter(now)
            && Duration.between(observed, now).compareTo(MAX_OBSERVATION_AGE) < 0;
    }
    private static boolean within(Instant value, Instant from, Instant until) {
        return !value.isBefore(from) && value.isBefore(until);
    }
    private static String normalize(String value) {
        return Normalizer.normalize(value.replaceAll("(?i)<br\\s*/?>", ""), Normalizer.Form.NFC)
            .replaceAll("[\\s\\u3000]+", "");
    }
    private static boolean hasSources(JsonNode sources) {
        if (!sources.isArray()) return false;
        boolean wiki = false, zekamashi = false;
        for (JsonNode source : sources) {
            String url = source.path("url").asText();
            if (!source.path("accessedOn").isTextual() || !source.path("manuallyReviewed").isBoolean()
                    || !source.path("manuallyReviewed").booleanValue()) continue;
            try { java.time.LocalDate.parse(source.path("accessedOn").textValue()); }
            catch (RuntimeException invalid) { continue; }
            wiki |= url.startsWith("https://wikiwiki.jp/kancolle/");
            zekamashi |= url.startsWith("https://zekamashi.net/");
        }
        return wiki && zekamashi;
    }
    private static String text(JsonNode row, String field) {
        require(row.path(field).isTextual() && !row.path(field).textValue().isBlank(), field);
        return row.path(field).textValue();
    }
    private static int integer(JsonNode row, String field) {
        require(row.path(field).isIntegralNumber() && row.path(field).canConvertToInt(), field);
        return row.path(field).intValue();
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static byte[] readBundled(String resource) {
        try (InputStream in = SeasonalQuestRuleService.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) return null;
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            return bytes.length <= MAX_BYTES ? bytes : null;
        } catch (IOException invalid) { return null; }
    }
}
