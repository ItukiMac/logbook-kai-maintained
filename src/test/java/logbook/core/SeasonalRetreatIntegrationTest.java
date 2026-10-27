package logbook.core;

import static logbook.core.SeasonalQuestRuleServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import logbook.bean.*;
import logbook.bean.QuestList.Quest;
import logbook.internal.kancolle.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual C edition loader + D on-disk fleet evidence + shared collector/predicate.
 * All identities, clocks, and fleet masters below are synthetic test fixtures.
 */
class SeasonalRetreatIntegrationTest {
    @TempDir Path root;
    private record Fixture(AppQuest quest, AppQuestCondition condition, MutableClock clock) {}

    private Fixture selected(int slot, boolean noRetreat) throws Exception {
        String rule = "{\"type\":\"出撃\",\"resetType\":\"ウィークリー\",\"filter\":{\"deckIds\":[1],"
                + "\"noRetreat\":" + noRetreat + ",\"fleet\":{\"name\":[\"required\"],\"order\":" + slot + "}},"
                + "\"conditions\":[{\"area\":[\"1-6\"],\"arrival\":true,\"count\":1}]}";
        byte[] base;
        try (var in = getClass().getResourceAsStream("/logbook/eventquest/840.json")) {
            assertNotNull(in); base = in.readAllBytes();
        }
        byte[] candidate = rule.getBytes(StandardCharsets.UTF_8);
        var row = edition().put("baseDefinitionSha256",SeasonalQuestRuleService.sha256(base))
                .put("definitionSha256",SeasonalQuestRuleService.sha256(candidate));
        var clock = new MutableClock(NOW);
        var rules = new SeasonalQuestRuleService(catalog(row), n -> RESOURCE.equals(n) ? candidate : null, clock);
        var observed = new Quest(); observed.setNo(840); observed.setTitle("edition title");
        observed.setDetail("first and second ships"); observed.setCategory(2); observed.setType(2);
        observed.setState(2); observed.setObservedAt(NOW);
        var quest = new AppQuest(); quest.setNo(840); quest.setQuest(observed); quest.setActive(true);
        quest.setExpire(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Tokyo")).format(UNTIL));
        var container = mock(LogBookCoreContainer.class);
        when(container.getClassLoader()).thenReturn(getClass().getClassLoader());
        try (var external = mockStatic(ExternalQuestDefinitions.class); var containers = mockStatic(LogBookCoreContainer.class)) {
            containers.when(LogBookCoreContainer::getInstance).thenReturn(container);
            var condition = AppQuestCondition.loadFromResource(observed,rules);
            assertEquals(SeasonalQuestRuleService.Status.SELECTED,condition.getSeasonalSelection().status());
            return new Fixture(quest,condition,clock);
        }
    }

    private PortArrival saved(Instant start, Instant end) throws Exception {
        var arrival = new PortArrival(PortArrival.FORMAT,UUID.randomUUID().toString(),start.toEpochMilli(),
                end.toEpochMilli(),"1-6",1,List.of(101,102,103),false);
        var store = new PortArrivalStore(root.resolve("arrivals"));
        store.appendFleet(new PortArrivalFleet(PortArrivalFleet.FORMAT,arrival,List.of(
                new PortArrivalFleet.Member(1,11,101,false),new PortArrivalFleet.Member(2,12,102,true),
                new PortArrivalFleet.Member(3,13,103,false))));
        store.append(arrival);
        return arrival;
    }

    private QuestCollect collect(Fixture f, PortArrival arrival) throws Exception {
        var periods = mock(AppQuestDuration.class);
        when(periods.getCondition(f.quest())).thenReturn(Optional.empty());
        when(periods.getArrivalCondition(f.quest())).thenReturn(List.of(arrival));
        var masters = new ShipMstCollection();
        for(int id=101;id<=103;id++) {
            var ship=new ShipMst();ship.setId(id);ship.setName(id==103?"required":"other"+id);
            masters.getShipMap().put(id,ship);
        }
        var store = new PortArrivalStore(root.resolve("arrivals"));
        try(var duration=mockStatic(AppQuestDuration.class);var master=mockStatic(ShipMstCollection.class);
            var maps=mockStatic(MapinfoMstCollection.class);var disk=mockStatic(PortArrivalRuntime.class)) {
            duration.when(AppQuestDuration::get).thenReturn(periods);
            master.when(ShipMstCollection::get).thenReturn(masters);
            maps.when(MapinfoMstCollection::get).thenReturn(new MapinfoMstCollection());
            disk.when(()->PortArrivalRuntime.readFleet(arrival)).thenAnswer(x->store.readFleet(arrival));
            return QuestCollect.collect(f.quest(),f.condition());
        }
    }
    @Test void selectedEditionCountsSurvivorAtItsOriginalThirdSlot() throws Exception {
        var f=selected(3,false);var result=collect(f,saved(NOW.minusSeconds(20),NOW.minusSeconds(1)));
        assertEquals(1,result.getTotal().getArrivals());assertTrue(f.condition().test(result));
    }
    @Test void retiredSecondSlotDoesNotPromoteTheThirdShip() throws Exception {
        var f=selected(2,false);var result=collect(f,saved(NOW.minusSeconds(20),NOW.minusSeconds(1)));
        assertEquals(0,result.getTotal().getArrivals());assertFalse(f.condition().test(result));
    }
    @Test void selectedNoRetreatRuleCannotBeBypassedByProvenSurvivors() throws Exception {
        var f=selected(3,true);var result=collect(f,saved(NOW.minusSeconds(20),NOW.minusSeconds(1)));
        assertEquals(0,result.getTotal().getArrivals());assertFalse(f.condition().test(result));
    }
    @Test void departureBeforeSelectedWindowIsExcludedEvenWithValidFleetEvidence() throws Exception {
        var f=selected(3,false);var result=collect(f,saved(FROM.minusSeconds(1),NOW.minusSeconds(1)));
        assertEquals(0,result.getTotal().getArrivals());assertFalse(f.condition().test(result));
    }
    @Test void arrivalAtWindowEndIsExcludedEvenWithValidFleetEvidence() throws Exception {
        var f=selected(3,false);var result=collect(f,saved(UNTIL.minusSeconds(30),UNTIL));
        assertEquals(0,result.getTotal().getArrivals());assertFalse(f.condition().test(result));
    }
    @Test void changedObservationCannotReviveAPreviouslySuccessfulSurvivorResult() throws Exception {
        var f=selected(3,false);var arrival=saved(NOW.minusSeconds(20),NOW.minusSeconds(1));
        assertTrue(f.condition().test(collect(f,arrival)));
        f.quest().getQuest().setTitle("another season");
        assertFalse(f.condition().test(collect(f,arrival)));assertNull(f.condition().getResult());
    }
    @Test void expirationAfterCollectionInvalidatesThePredicateResult() throws Exception {
        var f=selected(3,false);var result=collect(f,saved(NOW.minusSeconds(20),NOW.minusSeconds(1)));
        f.clock().now=UNTIL;
        assertFalse(f.condition().test(result));assertNull(f.condition().getResult());
    }
    @Test void incompleteFleetPublicationDoesNotFallBackToDepartureFormation() throws Exception {
        var f=selected(3,false);var arrival=saved(NOW.minusSeconds(20),NOW.minusSeconds(1));
        // Deliberately corrupt only this test's newly created published evidence.
        Path file;
        try(var paths=Files.walk(root.resolve("arrivals/fleet-evidence-v1"))) {
            file=paths.filter(p->p.toString().endsWith(".json")).findFirst().orElseThrow();
        }
        Files.writeString(file,"{broken");
        var result=collect(f,arrival);assertTrue(result.isArrivalEvidenceUnavailable());
        assertFalse(f.condition().test(result));
    }
}
