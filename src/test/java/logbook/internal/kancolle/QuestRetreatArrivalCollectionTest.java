package logbook.internal.kancolle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import logbook.bean.AppQuest;
import logbook.bean.AppQuestCondition;
import logbook.bean.AppQuestDuration;
import logbook.bean.MapinfoMstCollection;
import logbook.bean.ShipMst;
import logbook.bean.ShipMstCollection;
import org.junit.jupiter.api.Test;

/** Real aggregation and fleet predicates; I/O and master service use isolated synthetic evidence. */
class QuestRetreatArrivalCollectionTest {
    private static final long T = 1_700_000_000_000L;

    private static PortArrival arrival(boolean noRetreat) {
        return new PortArrival(PortArrival.FORMAT, UUID.randomUUID().toString(), T, T + 1_000,
                "1-6", 1, List.of(101, 102, 103), noRetreat);
    }

    private static PortArrivalFleet survivors(PortArrival arrival) {
        return new PortArrivalFleet(PortArrivalFleet.FORMAT, arrival, List.of(
                new PortArrivalFleet.Member(1, 11, 101, false),
                new PortArrivalFleet.Member(2, 12, 102, true),
                new PortArrivalFleet.Member(3, 13, 103, false)));
    }

    private static AppQuestCondition rule(String filter) throws Exception {
        return logbook.internal.json.JsonMappers.builder().build().readValue("{\"filter\":" + filter
                + ",\"conditions\":[{\"area\":[\"1-6\"],\"arrival\":true,\"count\":1}]}", AppQuestCondition.class);
    }

    private static ShipMst master(int id, String name) {
        var ship = new ShipMst();
        ship.setId(id);
        ship.setName(name);
        return ship;
    }

    private static QuestCollect collect(AppQuestCondition condition, PortArrival arrival,
            Optional<PortArrivalFleet> evidence, boolean corrupt, boolean unknownSurvivor,
            boolean expectFleetRead) throws Exception {
        var quest = new AppQuest();
        quest.setNo(905);
        var durations = mock(AppQuestDuration.class);
        when(durations.getArrivalCondition(quest)).thenReturn(List.of(arrival));
        when(durations.getCondition(quest)).thenReturn(Optional.empty());
        var masters = new ShipMstCollection();
        masters.getShipMap().put(101, master(101, "flag"));
        masters.getShipMap().put(102, master(102, "retired"));
        if (!unknownSurvivor) masters.getShipMap().put(103, master(103, "required"));

        try (var durationService = mockStatic(AppQuestDuration.class);
             var masterService = mockStatic(ShipMstCollection.class);
             var mapService = mockStatic(MapinfoMstCollection.class);
             var disk = mockStatic(PortArrivalRuntime.class)) {
            durationService.when(AppQuestDuration::get).thenReturn(durations);
            masterService.when(ShipMstCollection::get).thenReturn(masters);
            mapService.when(MapinfoMstCollection::get).thenReturn(new MapinfoMstCollection());
            disk.when(() -> PortArrivalRuntime.readFleet(arrival)).thenAnswer(call -> {
                if (corrupt) throw new IOException("Synthetic inconsistent fleet evidence");
                return evidence;
            });
            var result = QuestCollect.collect(quest, condition);
            disk.verify(() -> PortArrivalRuntime.readFleet(arrival), expectFleetRead ? times(1) : never());
            return result;
        }
    }

    @Test void provenRetreatSurvivorsCanSatisfyTheirOriginalPosition() throws Exception {
        var arrival = arrival(false);
        var condition = rule("{\"fleet\":{\"name\":[\"required\"],\"order\":3}}");
        var result = collect(condition, arrival, Optional.of(survivors(arrival)), false, false, true);
        assertEquals(1, result.getTotal().getArrivals());
        assertTrue(condition.test(result));
        assertFalse(result.isArrivalEvidenceUnavailable());
    }

    @Test void provenVacancyNeverPromotesAThirdSlotSurvivor() throws Exception {
        var arrival = arrival(false);
        var condition = rule("{\"fleet\":{\"name\":[\"required\"],\"order\":2}}");
        var result = collect(condition, arrival, Optional.of(survivors(arrival)), false, false, true);
        assertEquals(0, result.getTotal().getArrivals());
        assertFalse(condition.test(result));
    }

    @Test void departureMemberThatRetreatedDoesNotMeetPresenceCondition() throws Exception {
        var arrival = arrival(false);
        var condition = rule("{\"fleet\":{\"name\":[\"retired\"]}}");
        var result = collect(condition, arrival, Optional.of(survivors(arrival)), false, false, true);
        assertEquals(0, result.getTotal().getArrivals());
    }

    @Test void noRetreatRequirementStillRejectsAProvenSurvivingFleet() throws Exception {
        var arrival = arrival(false);
        var condition = rule("{\"noRetreat\":true,\"fleet\":{\"name\":[\"flag\"],\"order\":1}}");
        var result = collect(condition, arrival, Optional.of(survivors(arrival)), false, false, false);
        assertEquals(0, result.getTotal().getArrivals());
        assertFalse(condition.test(result));
    }

    @Test void legacyRetreatRecordWithoutSeparateEvidenceKeepsFailingClosed() throws Exception {
        var arrival = arrival(false);
        var condition = rule("{\"fleet\":{\"name\":[\"flag\"],\"order\":1}}");
        var result = collect(condition, arrival, Optional.empty(), false, false, true);
        assertEquals(0, result.getTotal().getArrivals());
        assertFalse(result.isArrivalEvidenceUnavailable());
    }

    @Test void brokenSurvivorEvidenceIsVisibleAsUnavailable() throws Exception {
        var arrival = arrival(false);
        var condition = rule("{\"fleet\":{\"name\":[\"flag\"],\"order\":1}}");
        var result = collect(condition, arrival, Optional.empty(), true, false, true);
        assertTrue(result.isArrivalEvidenceUnavailable());
        assertFalse(condition.test(result));
        assertTrue(condition.getConditions().getFirst().getCurrent().contains("確認できません"));
    }

    @Test void unknownLivingMasterCannotBeSilentlyRemovedFromTheFleet() throws Exception {
        var arrival = arrival(false);
        var condition = rule("{\"fleet\":{\"name\":[\"flag\"],\"order\":1}}");
        var result = collect(condition, arrival, Optional.of(survivors(arrival)), false, true, true);
        assertEquals(0, result.getTotal().getArrivals());
    }

    @Test void ordinaryLegacyArrivalKeepsItsExistingFleetEvaluation() throws Exception {
        var arrival = arrival(true);
        var condition = rule("{\"fleet\":{\"name\":[\"required\"],\"order\":3}}");
        var result = collect(condition, arrival, Optional.empty(), false, false, false);
        assertEquals(1, result.getTotal().getArrivals());
        assertTrue(condition.test(result));
    }

    @Test void ordinaryLegacyArrivalWithUnknownMasterStillFailsClosed() throws Exception {
        var arrival = arrival(true);
        var condition = rule("{\"fleet\":{\"name\":[\"flag\"],\"order\":1}}");
        var result = collect(condition, arrival, Optional.empty(), false, true, false);
        assertEquals(0, result.getTotal().getArrivals());
    }

    @Test void deckAndAreaRestrictionsRemainAheadOfSurvivorEvaluation() throws Exception {
        var arrival = arrival(false);
        for (String restriction : List.of("\"deckIds\":[2]", "\"area\":[\"1-5\"]")) {
            var condition = rule("{" + restriction + ",\"fleet\":{\"name\":[\"flag\"],\"order\":1}}");
            var result = collect(condition, arrival, Optional.of(survivors(arrival)), false, false, false);
            assertEquals(0, result.getTotal().getArrivals());
        }
    }

    @Test void noFleetConditionDoesNotInventANewSurvivorRequirement() throws Exception {
        var arrival = arrival(false);
        var condition = rule("{}");
        var result = collect(condition, arrival, Optional.empty(), false, false, false);
        assertEquals(1, result.getTotal().getArrivals());
    }
}
