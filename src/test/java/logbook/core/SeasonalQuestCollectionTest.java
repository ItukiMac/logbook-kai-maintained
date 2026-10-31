package logbook.core;

import static logbook.core.SeasonalQuestRuleService.Status.SELECTED;
import static logbook.core.SeasonalQuestRuleServiceTest.FROM;
import static logbook.core.SeasonalQuestRuleServiceTest.NOW;
import static logbook.core.SeasonalQuestRuleServiceTest.RESOURCE;
import static logbook.core.SeasonalQuestRuleServiceTest.UNTIL;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import logbook.bean.AppQuest;
import logbook.bean.AppQuestCondition;
import logbook.bean.AppQuestDuration;
import logbook.bean.MapinfoMst;
import logbook.bean.MapinfoMstCollection;
import logbook.bean.QuestList.Quest;
import logbook.internal.kancolle.BattleLogs;
import logbook.internal.kancolle.BattleLogs.SimpleBattleLog;
import logbook.internal.kancolle.MissionLogs;
import logbook.internal.kancolle.MissionLogs.SimpleMissionLog;
import logbook.internal.kancolle.PortArrival;
import logbook.internal.kancolle.PortArrivalRuntime;
import logbook.internal.kancolle.QuestCollect;
import org.junit.jupiter.api.Test;

/** Real resource loader, edition selection, collector and predicates; all player evidence is synthetic. */
class SeasonalQuestCollectionTest {
    private static final DateTimeFormatter JST_DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Tokyo"));
    private static final String BATTLE_RULE = """
            {"type":"出撃","resetType":"ウィークリー",
             "conditions":[{"area":["1-1"],"boss":true,"rank":["S"],"count":2}]}
            """;
    private static final String MISSION_RULE = """
            {"type":"遠征","resetType":"ウィークリー",
             "conditions":[{"missions":["fixture mission"],"count":2}]}
            """;
    private static final String ARRIVAL_RULE = """
            {"type":"出撃","resetType":"ウィークリー",
             "conditions":[{"area":["1-6"],"arrival":true,"count":2}]}
            """;

    private record Fixture(AppQuest quest, AppQuestCondition condition,
            SeasonalQuestRuleServiceTest.MutableClock clock) {
    }

    private static Quest observation(int category, Instant at) {
        var observed = new Quest();
        observed.setNo(840);
        observed.setTitle("edition title");
        observed.setDetail("first and second ships");
        observed.setCategory(category);
        observed.setType(2);
        observed.setState(2);
        observed.setObservedAt(at);
        return observed;
    }

    private static AppQuest quest(Quest observed) {
        var quest = new AppQuest();
        quest.setNo(observed.getNo());
        quest.setQuest(observed);
        quest.setActive(true);
        quest.setExpire(JST_DATE.format(UNTIL));
        return quest;
    }

    private static Fixture load(String rule, int category, Instant now, Instant observedAt,
            boolean reviewed) throws Exception {
        byte[] base;
        try (var resource = SeasonalQuestCollectionTest.class.getResourceAsStream("/logbook/eventquest/840.json")) {
            assertNotNull(resource, "The base hash must bind the real bundled definition");
            base = resource.readAllBytes();
        }
        byte[] candidate = rule.getBytes(StandardCharsets.UTF_8);
        var row = SeasonalQuestRuleServiceTest.edition()
                .put("category", category)
                .put("baseDefinitionSha256", SeasonalQuestRuleService.sha256(base))
                .put("definitionSha256", SeasonalQuestRuleService.sha256(candidate))
                .put("modeledConditionsVerified", reviewed);
        var clock = new SeasonalQuestRuleServiceTest.MutableClock(now);
        var rules = new SeasonalQuestRuleService(SeasonalQuestRuleServiceTest.catalog(row),
                name -> RESOURCE.equals(name) ? candidate : null, clock);
        var observed = observation(category, observedAt);
        // Resolve real bundled bytes through the real loader, with no external overlays or plugins.
        var container = mock(LogBookCoreContainer.class);
        when(container.getClassLoader()).thenReturn(SeasonalQuestCollectionTest.class.getClassLoader());
        try (var externalDefinitions = mockStatic(ExternalQuestDefinitions.class);
                var containers = mockStatic(LogBookCoreContainer.class)) {
            containers.when(LogBookCoreContainer::getInstance).thenReturn(container);
            var condition = AppQuestCondition.loadFromResource(observed, rules);
            assertNotNull(condition);
            return new Fixture(quest(observed), condition, clock);
        }
    }

    private static Fixture selected(String rule, int category) throws Exception {
        var fixture = load(rule, category, NOW, NOW, true);
        assertEquals(SELECTED, fixture.condition().getSeasonalSelection().status());
        assertTrue(fixture.condition().isRuleUsableFor(fixture.quest()));
        assertEquals(1, fixture.condition().getConditions().size(),
                "The selected candidate must replace the base conditions");
        assertEquals(2, fixture.condition().getConditions().getFirst().getCount());
        return fixture;
    }

    private static SimpleBattleLog battle(String date) {
        var row = mock(SimpleBattleLog.class);
        when(row.getDateString()).thenReturn(date);
        when(row.getArea()).thenReturn("fixture sea");
        when(row.getBoss()).thenReturn("ボス");
        when(row.getCell()).thenReturn("A");
        when(row.getRank()).thenReturn("S");
        return row;
    }

    private static SimpleMissionLog mission(String date, String result) {
        var row = mock(SimpleMissionLog.class);
        when(row.getDateString()).thenReturn(date);
        when(row.getName()).thenReturn("fixture mission");
        when(row.getResult()).thenReturn(result);
        return row;
    }

    private static PortArrival arrival(Instant startedAt, Instant arrivedAt) {
        return new PortArrival(PortArrival.FORMAT, UUID.randomUUID().toString(),
                startedAt.toEpochMilli(), arrivedAt.toEpochMilli(), "1-6", 1, List.of(101, 102), true);
    }

    private static QuestCollect collect(Fixture fixture, List<SimpleBattleLog> battleRows,
            List<SimpleMissionLog> missionRows, List<PortArrival> arrivals) throws Exception {
        var duration = mock(AppQuestDuration.class);
        when(duration.getCondition(fixture.quest())).thenReturn(Optional.of(battleRows));
        when(duration.getMissionCondition(fixture.quest())).thenReturn(Optional.of(missionRows));
        when(duration.getArrivalCondition(fixture.quest())).thenReturn(arrivals);
        var maps = new MapinfoMstCollection();
        var sea = new MapinfoMst();
        sea.setName("fixture sea");
        sea.setMapareaId(1);
        sea.setNo(1);
        maps.getMapinfo().put(11, sea);
        try (var periods = mockStatic(AppQuestDuration.class);
                var mapData = mockStatic(MapinfoMstCollection.class);
                var battleDisk = mockStatic(BattleLogs.class);
                var missionDisk = mockStatic(MissionLogs.class);
                var arrivalDisk = mockStatic(PortArrivalRuntime.class)) {
            periods.when(AppQuestDuration::get).thenReturn(duration);
            mapData.when(MapinfoMstCollection::get).thenReturn(maps);
            var result = QuestCollect.collect(fixture.quest(), fixture.condition());
            assertFalse(result.isSeasonalEvidenceUnavailable());
            battleDisk.verifyNoInteractions();
            missionDisk.verifyNoInteractions();
            arrivalDisk.verifyNoInteractions();
            return result;
        }
    }

    private static void assertMeasured(Fixture fixture, QuestCollect result, boolean complete, int current) {
        assertEquals(complete, fixture.condition().test(result));
        assertEquals(complete, fixture.condition().getResult());
        var condition = fixture.condition().getConditions().getFirst();
        assertEquals(complete, condition.getResult());
        assertEquals(String.valueOf(current), condition.getCurrent());
    }

    private static void assertUnknown(AppQuestCondition condition, QuestCollect result) {
        assertFalse(condition.test(result));
        assertNull(condition.getResult(), "Unavailable evidence must not become a measured false or stale success");
        for (var clause : condition.getConditions()) {
            assertNull(clause.getResult());
            assertTrue(clause.getCurrent().startsWith("未確定"), clause.getCurrent());
        }
    }

    private static void assertUnknownWithoutStoreReads(Fixture fixture, AppQuest quest) {
        // A previously displayed success must also be invalidated when this observation is unusable.
        fixture.condition().setResult(true);
        fixture.condition().getConditions().forEach(clause -> clause.setResult(true));
        try (var periods = mockStatic(AppQuestDuration.class);
                var mapData = mockStatic(MapinfoMstCollection.class);
                var battleDisk = mockStatic(BattleLogs.class);
                var missionDisk = mockStatic(MissionLogs.class);
                var arrivalDisk = mockStatic(PortArrivalRuntime.class)) {
            var result = QuestCollect.collect(quest, fixture.condition());
            assertTrue(result.isSeasonalEvidenceUnavailable());
            assertUnknown(fixture.condition(), result);
            periods.verifyNoInteractions();
            mapData.verifyNoInteractions();
            battleDisk.verifyNoInteractions();
            missionDisk.verifyNoInteractions();
            arrivalDisk.verifyNoInteractions();
        }
    }

    @Test
    void battlesCountTheInclusiveEditionStartAndPresentInstant() throws Exception {
        var fixture = selected(BATTLE_RULE, 2);
        var actual = collect(fixture, List.of(battle(JST_DATE.format(FROM)), battle(JST_DATE.format(NOW))),
                List.of(), List.of());
        assertEquals(2, actual.getTotal().getBoss().getS());
        assertEquals(2, actual.getArea().get("1-1").getBoss().getS());
        assertMeasured(fixture, actual, true, 2);
    }

    @Test
    void battlesRejectPreviousEditionsEndBoundaryFutureAndMalformedDates() throws Exception {
        var fixture = selected(BATTLE_RULE, 2);
        var excluded = List.of(battle("2024-01-29 00:00:00"), battle(JST_DATE.format(FROM.minusSeconds(1))),
                battle(JST_DATE.format(UNTIL)), battle(JST_DATE.format(NOW.plusSeconds(1))),
                battle("2025-02-30 00:00:00"), battle("not a date"), battle(null));
        // No valid rows: the collector's completion early exit cannot conceal a wrongly accepted row.
        var actual = collect(fixture, excluded, List.of(), List.of());
        assertEquals(0, actual.getTotal().getBoss().getS());
        assertTrue(actual.getArea().isEmpty());
        assertMeasured(fixture, actual, false, 0);
        excluded.forEach(row -> verify(row, never()).getArea());
    }

    @Test
    void missionsCountTheInclusiveEditionStartAndPresentInstant() throws Exception {
        var fixture = selected(MISSION_RULE, 4);
        var actual = collect(fixture, List.of(), List.of(mission(JST_DATE.format(FROM), "成功"),
                mission(JST_DATE.format(NOW), "大成功")), List.of());
        assertEquals(Map.of("fixture mission", 2L), actual.getMissions());
        assertMeasured(fixture, actual, true, 2);
    }

    @Test
    void missionsRejectPreviousEditionsEndBoundaryFutureAndMalformedDates() throws Exception {
        var fixture = selected(MISSION_RULE, 4);
        var excluded = List.of(mission("2024-01-29 00:00:00", "成功"),
                mission(JST_DATE.format(FROM.minusSeconds(1)), "大成功"),
                mission(JST_DATE.format(UNTIL), "成功"), mission(JST_DATE.format(NOW.plusSeconds(1)), "大成功"),
                mission("2025-02-30 00:00:00", "成功"), mission("not a date", "成功"), mission(null, "成功"));
        var actual = collect(fixture, List.of(), excluded, List.of());
        assertTrue(actual.getMissions().isEmpty());
        assertMeasured(fixture, actual, false, 0);
        excluded.forEach(row -> verify(row, never()).getResult());
    }

    @Test
    void arrivalsCountOnlyObservedGoalsWithinTheEdition() throws Exception {
        var fixture = selected(ARRIVAL_RULE, 2);
        var actual = collect(fixture, List.of(), List.of(), List.of(arrival(FROM, FROM.plusMillis(1)),
                arrival(NOW.minusSeconds(1), NOW)));
        assertEquals(2, actual.getTotal().getArrivals());
        assertEquals(2, actual.getArea().get("1-6").getArrivals());
        assertEquals(0, actual.getTotal().getAll().getS());
        assertFalse(actual.isArrivalEvidenceUnavailable());
        assertMeasured(fixture, actual, true, 2);
    }

    @Test
    void arrivalsRejectDeparturesBeforeTheEditionEvenWhenGoalsAreInside() throws Exception {
        var fixture = selected(ARRIVAL_RULE, 2);
        var actual = collect(fixture, List.of(), List.of(), List.of(arrival(FROM.minusMillis(1), FROM),
                arrival(FROM.minusSeconds(60), FROM.plusSeconds(1))));
        assertEquals(0, actual.getTotal().getArrivals());
        assertTrue(actual.getArea().isEmpty());
        assertMeasured(fixture, actual, false, 0);
    }

    @Test
    void arrivalsRejectPreviousEditionsEndBoundaryAndFutureGoals() throws Exception {
        var fixture = selected(ARRIVAL_RULE, 2);
        Instant previousYear = Instant.parse("2024-01-28T15:00:00Z");
        var actual = collect(fixture, List.of(), List.of(), List.of(
                arrival(previousYear, previousYear.plusSeconds(60)),
                arrival(FROM.minusSeconds(60), FROM.minusMillis(1)),
                arrival(UNTIL.minusSeconds(1), UNTIL),
                arrival(NOW.minusSeconds(1), NOW.plusMillis(1)),
                arrival(NOW.plusMillis(1), NOW.plusMillis(2))));
        assertEquals(0, actual.getTotal().getArrivals());
        assertTrue(actual.getArea().isEmpty());
        assertFalse(actual.isArrivalEvidenceUnavailable());
        assertMeasured(fixture, actual, false, 0);
    }

    @Test
    void unresolvedEvidenceAndMissingOrStaleObservationReadNoPlayerStores() throws Exception {
        for (String rule : List.of(BATTLE_RULE, MISSION_RULE, ARRIVAL_RULE)) {
            int category = rule.equals(MISSION_RULE) ? 4 : 2;
            for (Fixture fixture : List.of(load(rule, category, NOW, NOW, false),
                    load(rule, category, NOW, null, true),
                    load(rule, category, NOW, NOW.minus(SeasonalQuestRuleService.MAX_OBSERVATION_AGE), true),
                    load(rule, category, NOW, NOW.plusMillis(1), true))) {
                assertFalse(fixture.condition().isRuleUsable());
                assertUnknownWithoutStoreReads(fixture, fixture.quest());
            }
        }
    }

    @Test
    void selectedRuleCannotBeReusedForDifferentQuestIdsDetailsOrObservationTimes() throws Exception {
        for (String rule : List.of(BATTLE_RULE, MISSION_RULE, ARRIVAL_RULE)) {
            int category = rule.equals(MISSION_RULE) ? 4 : 2;
            var fixture = selected(rule, category);
            var wrongOuterId = quest(observation(category, NOW));
            wrongOuterId.setNo(841);
            var wrongInnerId = quest(observation(category, NOW));
            wrongInnerId.getQuest().setNo(841);
            var changedDetail = quest(observation(category, NOW));
            changedDetail.getQuest().setDetail("different required ships");
            var changedObservation = quest(observation(category, NOW.plusMillis(1)));
            var missingObservation = quest(observation(category, null));
            for (var other : List.of(wrongOuterId, wrongInnerId, changedDetail, changedObservation,
                    missingObservation)) {
                assertTrue(fixture.condition().isRuleUsable(),
                        "The edition remains selected for its original observation");
                assertFalse(fixture.condition().isRuleUsableFor(other));
                assertUnknownWithoutStoreReads(fixture, other);
            }
            assertUnknownWithoutStoreReads(fixture, null);
            assertTrue(fixture.condition().isRuleUsableFor(quest(observation(category, NOW))),
                    "Identity is compared by observed values, not Java object identity");
        }
    }

    @Test
    void loadedSelectionStopsReadingStoresAtExpiryAndObservationFreshnessBoundary() throws Exception {
        for (Instant deadline : List.of(UNTIL, NOW.plus(SeasonalQuestRuleService.MAX_OBSERVATION_AGE))) {
            var fixture = selected(BATTLE_RULE, 2);
            fixture.clock().now = deadline;
            assertUnknownWithoutStoreReads(fixture, fixture.quest());
        }
    }

    @Test
    void collectedSuccessBecomesUnknownIfTheSelectedEditionExpiresBeforeDisplay() throws Exception {
        for (Instant deadline : List.of(UNTIL, NOW.plus(SeasonalQuestRuleService.MAX_OBSERVATION_AGE))) {
            var fixture = selected(BATTLE_RULE, 2);
            var actual = collect(fixture, List.of(battle(JST_DATE.format(FROM)), battle(JST_DATE.format(NOW))),
                    List.of(), List.of());
            assertMeasured(fixture, actual, true, 2);
            fixture.clock().now = deadline;
            assertUnknown(fixture.condition(), actual);
        }
    }
}
