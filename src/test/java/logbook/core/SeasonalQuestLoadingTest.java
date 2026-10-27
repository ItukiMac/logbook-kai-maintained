package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import logbook.bean.AppQuest;
import logbook.bean.AppQuestCondition;
import logbook.bean.QuestList.Quest;
import logbook.internal.util.DateUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class SeasonalQuestLoadingTest {
    static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path root;
    String previous;
    @BeforeEach void start() throws Exception {
        previous = System.getProperty(ExternalQuestDefinitions.ROOT_PROPERTY);
        System.clearProperty(ExternalQuestDefinitions.ROOT_PROPERTY);
        GameDefinitionRuntime.stopForTest(); GameDefinitionRuntime.initialize();
    }
    @AfterEach void stop() {
        GameDefinitionRuntime.stopForTest();
        if (previous == null) System.clearProperty(ExternalQuestDefinitions.ROOT_PROPERTY);
        else System.setProperty(ExternalQuestDefinitions.ROOT_PROPERTY, previous);
    }
    static byte[] resource(String name) {
        try (var in = SeasonalQuestLoadingTest.class.getClassLoader().getResourceAsStream(name)) {
            return in == null ? null : in.readAllBytes();
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    static JsonNode row(int id) throws Exception {
        for (var row : JSON.readTree(resource(SeasonalQuestRuleService.CATALOG)).path("editions"))
            if (row.path("questId").asInt() == id) return row;
        throw new IllegalArgumentException("Missing edition " + id);
    }
    static Quest observed(JsonNode row, Instant at) {
        Quest quest = new Quest(); quest.setNo(row.path("questId").asInt());
        quest.setTitle(row.path("title").asText()); quest.setDetail(row.path("detail").asText());
        quest.setCategory(row.path("category").asInt()); quest.setType(row.path("type").asInt());
        quest.setState(2); quest.setObservedAt(at); return quest;
    }
    static SeasonalQuestRuleService rules(Instant now) {
        return new SeasonalQuestRuleService(resource(SeasonalQuestRuleService.CATALOG),
            SeasonalQuestLoadingTest::resource, Clock.fixed(now, ZoneOffset.UTC));
    }
    @Test void unobservedReusedSeasonalIdMustNotOverrideObservedWeeklyPeriod() {
        Quest observed = new Quest();
        observed.setNo(843); observed.setType(2); observed.setCategory(2);
        observed.setState(2); observed.setTitle("a later edition"); observed.setDetail("different conditions");
        var day = ZonedDateTime.now(ZoneId.of("GMT+04:00")).truncatedTo(ChronoUnit.DAYS);
        var weekly = day.plusWeeks(1).minusDays(day.getDayOfWeek().getValue() - 1)
            .withZoneSameInstant(ZoneId.of("Asia/Tokyo"));
        assertEquals(DateUtil.DATE_FORMAT.format(weekly), AppQuest.toAppQuest(observed).getExpire(),
            "A historical monthly definition must not override an unverified live weekly quest");
        var original = AppQuestCondition.loadFromResource(843);
        assertNotNull(original); assertEquals("マンスリー", original.getResetType());
        assertNotNull(original.getSeasonalSelection()); assertFalse(original.isRuleUsable());
    }
    @Test void everyReviewedEditionUsesTheRealLoaderAndKeepsTheOriginalBytes() throws Exception {
        for (int id : List.of(840, 841, 843, 906, 954)) {
            var row = row(id); var now = Instant.parse(row.path("validFrom").asText()).plusSeconds(3600);
            byte[] before = resource("logbook/eventquest/" + id + ".json");
            var condition = AppQuestCondition.loadFromResource(observed(row, now), rules(now));
            assertNotNull(condition); assertTrue(condition.isRuleUsable(), condition.getRuleStatusMessage());
            assertEquals(row.path("editionId").asText(), condition.getSeasonalSelection().editionId());
            assertEquals("ウィークリー", condition.getResetType());
            assertSame(GameDefinitionRuntime.snapshot(), condition.getDefinitionSnapshot());
            assertArrayEquals(before, resource("logbook/eventquest/" + id + ".json"));
        }
    }
    @Test void allFiveHistoricalRulesAreUnavailableForAFresh2026Observation() throws Exception {
        Instant now = Instant.parse("2026-10-27T03:00:00Z");
        for (int id : List.of(840, 841, 843, 906, 954)) {
            var condition = AppQuestCondition.loadFromResource(observed(row(id), now), rules(now));
            assertNotNull(condition); assertFalse(condition.isRuleUsable());
            assertEquals(SeasonalQuestRuleService.Status.OUTSIDE_WINDOW, condition.getSeasonalSelection().status());
        }
    }
    @Test void rawTitleMatchWithoutLiveTimestampIsNotAnObservation() throws Exception {
        var row = row(840); var now = Instant.parse(row.path("validFrom").asText()).plusSeconds(3600);
        var quest = observed(row, null);
        var condition = AppQuestCondition.loadFromResource(quest, rules(now));
        assertFalse(condition.isRuleUsable());
        assertEquals(SeasonalQuestRuleService.Status.OBSERVATION_MISSING, condition.getSeasonalSelection().status());
    }
    @Test void changingAnActiveGenerationInvalidatesHeldAndReloadedSeasonalRules() throws Exception {
        var row = row(843); var now = Instant.parse(row.path("validFrom").asText()).plusSeconds(3600);
        var quest = observed(row, now); var rule = AppQuestCondition.loadFromResource(quest, rules(now));
        assertTrue(rule.isRuleUsable());
        Path changed = root.resolve("eventquest/843.json"); Files.createDirectories(changed.getParent());
        byte[] original = resource("logbook/eventquest/843.json");
        Files.writeString(changed, new String(original, StandardCharsets.UTF_8) + "\n");
        System.setProperty(ExternalQuestDefinitions.ROOT_PROPERTY, root.toString());
        GameDefinitionRuntime.reloadConfigured();
        assertFalse(rule.isRuleUsable()); assertTrue(rule.getRuleStatusMessage().contains("再照合"));
        var updated = AppQuestCondition.loadFromResource(quest, rules(now));
        assertFalse(updated.isRuleUsable());
        assertEquals(SeasonalQuestRuleService.Status.DEFINITION_CHANGED, updated.getSeasonalSelection().status());
        assertArrayEquals(original, resource("logbook/eventquest/843.json"));
        assertTrue(Files.readString(changed).endsWith("\n"));
    }
    @Test void missingSeasonalRuleInCurrentPackDoesNotResurrectABundledEdition() throws Exception {
        String generation = "a".repeat(64); Path base = root.resolve("generations/" + generation);
        Files.createDirectories(base.resolve("definitions/map")); byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
        Files.write(base.resolve("definitions/map/mapping.json"), bytes);
        JSON.writeValue(base.resolve("manifest.json").toFile(), Map.of("format", "logbook-data-pack-v1", "files", List.of(
            Map.of("path", "definitions/map/mapping.json", "bytes", bytes.length, "sha256", SeasonalQuestRuleService.sha256(bytes)))));
        JSON.writeValue(root.resolve("active.json").toFile(), Map.of("schema", DefinitionPackInstaller.POINTER_FORMAT, "generation", generation));
        GameDefinitionRuntime.reload(root);
        var row = row(840); var now = Instant.parse(row.path("validFrom").asText()).plusSeconds(3600);
        assertNull(AppQuestCondition.loadFromResource(observed(row, now), rules(now)));
        assertTrue(Files.exists(base.resolve("definitions/map/mapping.json")));
    }
    @Test void nonseasonalLoaderDoesNotNeedAnObservationAndRuntimeMetadataIsNotSerialized() throws Exception {
        var normal = AppQuestCondition.loadFromResource(257);
        assertTrue(normal.isRuleUsable()); assertNull(normal.getSeasonalSelection());
        var blocked = AppQuestCondition.loadFromResource(843);
        var saved = JSON.readTree(JSON.writeValueAsBytes(blocked));
        for (String field : List.of("seasonalSelection", "definitionSnapshot", "ruleUsable", "ruleStatusMessage"))
            assertFalse(saved.has(field), field);
        assertEquals("マンスリー", saved.path("resetType").asText());
    }
}
