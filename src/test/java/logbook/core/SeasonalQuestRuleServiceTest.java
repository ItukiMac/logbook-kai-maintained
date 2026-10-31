package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import static logbook.core.SeasonalQuestRuleService.Status.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import logbook.core.SeasonalQuestRuleService.Observation;
import org.junit.jupiter.api.Test;

class SeasonalQuestRuleServiceTest {
    static final ObjectMapper JSON = logbook.internal.json.JsonMappers.builder().build();
    static final Instant FROM = Instant.parse("2025-01-28T15:00:00Z");
    static final Instant UNTIL = Instant.parse("2025-02-27T15:00:00Z");
    static final Instant NOW = FROM.plusSeconds(3_600);
    static final byte[] BASE = "preserved old bytes".getBytes(StandardCharsets.UTF_8);
    static final byte[] RULE = "{\"type\":\"出撃\",\"resetType\":\"ウィークリー\",\"conditions\":[{\"area\":[\"1-1\"],\"boss\":true,\"rank\":[\"S\"],\"count\":1}]}"
            .getBytes(StandardCharsets.UTF_8);
    static final String RESOURCE = "logbook/seasonal/editions/test-2025.json";

    static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static ObjectNode edition() {
        var row = JSON.createObjectNode();
        row.put("editionId", "test-2025");
        row.put("questId", 840);
        row.put("editionYear", 2025);
        row.put("origin", "eventquest");
        row.put("title", "edition title");
        row.put("detail", "first and second ships");
        row.put("category", 2);
        row.put("type", 2);
        row.put("validFrom", FROM.toString());
        row.put("validUntil", UNTIL.toString());
        row.put("baseDefinitionSha256", SeasonalQuestRuleService.sha256(BASE));
        row.put("definitionResource", RESOURCE);
        row.put("definitionSha256", SeasonalQuestRuleService.sha256(RULE));
        row.put("identityVerified", true);
        row.put("windowVerified", true);
        row.put("modeledConditionsVerified", true);
        row.putArray("selectionBlockers");
        for (String url : List.of("https://wikiwiki.jp/kancolle/fixture", "https://zekamashi.net/fixture"))
            row.withArray("sources").addObject().put("url", url).put("accessedOn", "2026-10-27").put("manuallyReviewed",
                    true);
        return row;
    }

    static byte[] catalog(ObjectNode... rows) throws Exception {
        var root = JSON.createObjectNode().put("format", SeasonalQuestRuleService.FORMAT);
        root.putArray("managedQuestIds").add(840);
        for (var row : rows)
            root.withArray("editions").add(row);
        if (rows.length == 0)
            root.putArray("editions");
        return JSON.writeValueAsBytes(root);
    }

    static Observation observation(Instant at) {
        return new Observation(840, "edition title", "first and second ships", 2, 2, at);
    }

    static SeasonalQuestRuleService service(Clock clock, ObjectNode... rows) throws Exception {
        return new SeasonalQuestRuleService(catalog(rows), name -> RESOURCE.equals(name) ? RULE : null, clock);
    }

    static SeasonalQuestRuleService.Status resolve(SeasonalQuestRuleService service, Observation observed) {
        return service.resolve(840, "eventquest", BASE, observed).selection().status();
    }

    @Test
    void exactIdentityYearWindowEvidenceAndBytesSelectAnIndependentCopy() throws Exception {
        var result = service(new MutableClock(NOW), edition()).resolve(840, "eventquest", BASE, observation(NOW));
        assertEquals(SELECTED, result.selection().status());
        assertArrayEquals(RULE, result.definition());
        byte[] copy = result.definition();
        copy[0] = 0;
        assertArrayEquals(RULE, result.definition());
        assertEquals(2025, result.selection().editionYear());
        assertEquals("test-2025", result.selection().editionId());
    }

    @Test
    void dateWindowIsInclusiveAtStartAndExclusiveAtEnd() throws Exception {
        for (Instant at : List.of(FROM.minusNanos(1), FROM, UNTIL.minusNanos(1), UNTIL)) {
            var expected = at.isBefore(FROM) || !at.isBefore(UNTIL) ? OUTSIDE_WINDOW : SELECTED;
            assertEquals(expected, resolve(service(new MutableClock(at), edition()), observation(at)), at.toString());
        }
    }

    @Test
    void missingObservationCannotBeReplacedByAnIdOrCalendarYear() throws Exception {
        var service = service(new MutableClock(NOW), edition());
        assertEquals(OBSERVATION_MISSING, resolve(service, null));
        assertEquals(OBSERVATION_MISSING, resolve(service, observation(null)));
        assertEquals(OBSERVATION_MISSING, resolve(service, new Observation(840, "edition title", null, 2, 2, NOW)));
    }

    @Test
    void reusedIdRequiresBothTitleAndDetailAndApiCategoryAndPeriod() throws Exception {
        var service = service(new MutableClock(NOW), edition());
        for (Observation wrong : List.of(new Observation(841, "edition title", "first and second ships", 2, 2, NOW),
                new Observation(840, "new title", "first and second ships", 2, 2, NOW),
                new Observation(840, "edition title", "new roster", 2, 2, NOW),
                new Observation(840, "edition title", "first and second ships", 4, 2, NOW),
                new Observation(840, "edition title", "first and second ships", 2, 4, NOW)))
            assertEquals(IDENTITY_MISMATCH, resolve(service, wrong));
    }

    @Test
    void formattingOnlyLineBreaksDoNotRelaxTheActualTextComparison() throws Exception {
        var service = service(new MutableClock(NOW), edition());
        assertEquals(SELECTED,
                resolve(service, new Observation(840, "edition　title", "first<br/> and\n second ships", 2, 2, NOW)));
        assertEquals(IDENTITY_MISMATCH,
                resolve(service, new Observation(840, "edition title", "first or second ships", 2, 2, NOW)));
    }

    @Test
    void yearRepetitionNeverActivatesTheOldEdition() throws Exception {
        Instant nextYear = NOW.atZone(ZoneOffset.UTC).plusYears(1).toInstant();
        assertEquals(OUTSIDE_WINDOW, resolve(service(new MutableClock(nextYear), edition()), observation(nextYear)));
    }

    @Test
    void missingSemanticOrIdentityEvidenceAndUnresolvedBlockersStayUnconfirmed() throws Exception {
        for (String field : List.of("identityVerified", "windowVerified", "modeledConditionsVerified")) {
            var row = edition().put(field, false);
            assertEquals(EVIDENCE_INCOMPLETE, resolve(service(new MutableClock(NOW), row), observation(NOW)));
        }
        var row = edition();
        row.withArray("selectionBlockers").add("flagship position disputed");
        assertEquals(EVIDENCE_INCOMPLETE, resolve(service(new MutableClock(NOW), row), observation(NOW)));
    }

    @Test
    void fetchedSourcesWithoutManualReviewOrOneNamedSiteAreInsufficient() throws Exception {
        var row = edition();
        ((ObjectNode) row.withArray("sources").get(1)).put("manuallyReviewed", false);
        assertEquals(EVIDENCE_INCOMPLETE, resolve(service(new MutableClock(NOW), row), observation(NOW)));
        row = edition();
        row.withArray("sources").remove(1);
        assertEquals(EVIDENCE_INCOMPLETE, resolve(service(new MutableClock(NOW), row), observation(NOW)));
        row = edition();
        ((ObjectNode) row.withArray("sources").get(1)).put("manuallyReviewed", "true");
        assertEquals(EVIDENCE_INCOMPLETE, resolve(service(new MutableClock(NOW), row), observation(NOW)));
    }

    @Test
    void decisionCannotBeReusedForAnotherQuestOrAChangedObservation() throws Exception {
        var selected = service(new MutableClock(NOW), edition()).resolve(840, "eventquest", BASE, observation(NOW))
                .selection();
        assertTrue(selected.matches(observation(NOW)));
        assertFalse(selected.matches(new Observation(841, "edition title", "first and second ships", 2, 2, NOW)));
        assertFalse(selected.matches(new Observation(840, "edition title", "changed detail", 2, 2, NOW)));
        assertFalse(selected.matches(observation(NOW.plusSeconds(1))));
        assertFalse(selected.matches(null));
    }

    @Test
    void conflictingEditionsNeverUseListOrderEvenIfOneIsUnreviewed() throws Exception {
        var other = edition().put("editionId", "other").put("modeledConditionsVerified", false);
        assertEquals(EDITION_CONFLICT, resolve(service(new MutableClock(NOW), edition(), other), observation(NOW)));
    }

    @Test
    void nonoverlappingEditionUpdateSelectsTheMatchingBoundedRevision() throws Exception {
        var old = edition().put("validUntil", NOW.toString());
        var revised = edition().put("editionId", "revision-2").put("validFrom", NOW.toString());
        var result = service(new MutableClock(NOW), old, revised).resolve(840, "eventquest", BASE, observation(NOW));
        assertEquals(SELECTED, result.selection().status());
        assertEquals("revision-2", result.selection().editionId());
    }

    @Test
    void oldObservationFromBeforeTheEditionDoesNotProveItsIdentity() throws Exception {
        assertEquals(OUTSIDE_WINDOW,
                resolve(service(new MutableClock(FROM), edition()), observation(FROM.minusNanos(1))));
    }

    @Test
    void observationsExpireAtTwentyFourHoursAndFutureClocksFailClosed() throws Exception {
        var clock = new MutableClock(NOW.plus(SeasonalQuestRuleService.MAX_OBSERVATION_AGE));
        assertEquals(OBSERVATION_STALE, resolve(service(clock, edition()), observation(NOW)));
        assertEquals(OBSERVATION_STALE,
                resolve(service(new MutableClock(NOW), edition()), observation(NOW.plusNanos(1))));
    }

    @Test
    void alreadySelectedObjectExpiresWithoutAnotherLoad() throws Exception {
        var clock = new MutableClock(UNTIL.minusSeconds(1));
        var selection = service(clock, edition()).resolve(840, "eventquest", BASE, observation(clock.now)).selection();
        assertTrue(selection.usable());
        assertEquals(UNTIL, selection.refreshAt());
        clock.now = UNTIL;
        assertFalse(selection.usable());
        assertEquals(OUTSIDE_WINDOW, selection.status());
    }

    @Test
    void alreadySelectedObjectLosesFreshnessAtTheExactBoundary() throws Exception {
        var clock = new MutableClock(NOW);
        var selection = service(clock, edition()).resolve(840, "eventquest", BASE, observation(NOW)).selection();
        clock.now = NOW.plus(SeasonalQuestRuleService.MAX_OBSERVATION_AGE);
        assertEquals(OBSERVATION_STALE, selection.status());
        assertFalse(selection.usable());
    }

    @Test
    void changedBaseBytesOrOriginCannotFallBackToTheHistoricalBundle() throws Exception {
        var service = service(new MutableClock(NOW), edition());
        assertEquals(DEFINITION_CHANGED,
                service.resolve(840, "eventquest", RULE, observation(NOW)).selection().status());
        assertEquals(DEFINITION_CHANGED,
                service.resolve(840, "customquest", BASE, observation(NOW)).selection().status());
        assertEquals(DEFINITION_CHANGED, service.resolve(840, "quest", BASE, observation(NOW)).selection().status());
    }

    @Test
    void missingOrChangedSelectedBytesAreNotApplied() throws Exception {
        for (byte[] bytes : new byte[][] { null, BASE }) {
            var service = new SeasonalQuestRuleService(catalog(edition()), name -> bytes, new MutableClock(NOW));
            assertEquals(DEFINITION_CHANGED, resolve(service, observation(NOW)));
        }
    }

    @Test
    void typedValidationStillRejectsHashMatchedInvalidRule() throws Exception {
        byte[] invalid = "{\"conditions\":[]}".getBytes(StandardCharsets.UTF_8);
        var row = edition().put("definitionSha256", SeasonalQuestRuleService.sha256(invalid));
        var service = new SeasonalQuestRuleService(catalog(row), name -> invalid, new MutableClock(NOW));
        assertEquals(DEFINITION_CHANGED, resolve(service, observation(NOW)));
    }

    @Test
    void matchingHashDoesNotPermitPeriodOrCategoryContradictions() throws Exception {
        for (String changed : List.of(new String(RULE, StandardCharsets.UTF_8).replace("ウィークリー", "マンスリー"),
                new String(RULE, StandardCharsets.UTF_8).replace("出撃", "遠征"))) {
            byte[] invalid = changed.getBytes(StandardCharsets.UTF_8);
            var row = edition().put("definitionSha256", SeasonalQuestRuleService.sha256(invalid));
            var service = new SeasonalQuestRuleService(catalog(row), name -> invalid, new MutableClock(NOW));
            assertEquals(DEFINITION_CHANGED, resolve(service, observation(NOW)));
        }
    }

    @Test
    void malformedOrMissingCatalogCannotEnableKnownOrUnknownEventRules() {
        for (byte[] invalid : new byte[][] { null, "[]".getBytes(StandardCharsets.UTF_8),
                "{}".getBytes(StandardCharsets.UTF_8) }) {
            var service = new SeasonalQuestRuleService(invalid, name -> RULE, new MutableClock(NOW));
            assertEquals(REGISTRY_INVALID, resolve(service, observation(NOW)));
            assertEquals(REGISTRY_INVALID,
                    service.resolve(9999, "eventquest", BASE, observation(NOW)).selection().status());
            assertEquals(NOT_SEASONAL, service.resolve(257, "quest", BASE, null).selection().status());
        }
    }

    @Test
    void badPathsDuplicateRowsAndImpossibleYearsInvalidateCatalog() throws Exception {
        for (ObjectNode row : List.of(edition().put("definitionResource", "../../config/private.json"),
                edition().put("editionYear", 2026), edition().put("validUntil", FROM.toString()),
                edition().put("baseDefinitionSha256", "bad")))
            assertEquals(REGISTRY_INVALID, resolve(service(new MutableClock(NOW), row), observation(NOW)));
        assertEquals(REGISTRY_INVALID, resolve(service(new MutableClock(NOW), edition(), edition()), observation(NOW)));
    }

    @Test
    void aMissingRegistryEntryStaysUnconfirmedAndNormalQuestsKeepTheirBytes() throws Exception {
        var service = service(new MutableClock(NOW));
        assertEquals(EVIDENCE_INCOMPLETE, resolve(service, observation(NOW)));
        var normal = service.resolve(257, "quest", BASE, null);
        assertEquals(NOT_SEASONAL, normal.selection().status());
        assertTrue(normal.selection().usable());
        assertArrayEquals(BASE, normal.definition());
    }

    @Test
    void logsMustBelongToTheEditionAndNotBeFromTheFuture() throws Exception {
        var selection = service(new MutableClock(NOW), edition()).resolve(840, "eventquest", BASE, observation(NOW))
                .selection();
        assertFalse(selection.permitsLog(FROM.minusNanos(1)));
        assertTrue(selection.permitsLog(FROM));
        assertTrue(selection.permitsLog(NOW));
        assertFalse(selection.permitsLog(NOW.plusNanos(1)));
        assertFalse(selection.permitsLog(UNTIL));
        assertFalse(selection.permitsLog("2025-02-30 00:00:00"));
        assertFalse(selection.permitsLog((String) null));
        assertTrue(selection.permitsLog("2025-01-29 00:00:00"));
    }
}
