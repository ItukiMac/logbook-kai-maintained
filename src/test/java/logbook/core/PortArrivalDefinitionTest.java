package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import tools.jackson.databind.ObjectMapper;
import logbook.bean.AppQuestCondition;
import logbook.internal.kancolle.QuestCollect;
import logbook.plugin.api.GameDefinitionSnapshot;
import org.junit.jupiter.api.Test;

class PortArrivalDefinitionTest {
    private static final ObjectMapper JSON = logbook.internal.json.JsonMappers.builder()
            .enable(tools.jackson.core.json.JsonReadFeature.ALLOW_JAVA_COMMENTS).build();

    private static String rule(String extra) {
        return "{\"conditions\":[{\"area\":[\"1-6\"],\"arrival\":true,\"count\":1" + extra + "}]}";
    }

    @Test
    void precedingBattleNeverSatisfiesTheNewArrivalCondition() throws Exception {
        var c = JSON.readValue(rule(""), AppQuestCondition.class).getConditions().getFirst();
        var collected = new QuestCollect();
        var count = new QuestCollect.Count();
        count.getAll().setS(99);
        count.getBoss().setS(99);
        collected.getArea().put("1-6", count);
        var ranks = new QuestCollect.Rank();
        ranks.setD(99);
        count.getCell().put("1-6-B", ranks);
        assertFalse(c.test(collected));
        count.setArrivals(1);
        assertTrue(c.test(collected));
        collected.setArrivalEvidenceUnavailable(true);
        assertFalse(c.test(collected));
        assertTrue(c.getCurrent().contains("確認できません"));
    }

    @Test
    void typedConsumersRejectMixedArrivalOperandsAndOtherMaps() {
        for (String extra : List.of(",\"boss\":true", ",\"start\":true", ",\"rank\":[\"S\"]", ",\"cells\":[\"B\"]",
                ",\"missions\":[]")) {
            var snapshot = new GameDefinitionSnapshot("test",
                    Map.of("logbook/quest/1.json", rule(extra).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertThrows(IllegalArgumentException.class, () -> PreparedGameDefinitions.prepare(snapshot));
        }
        var wrong = new GameDefinitionSnapshot("test",
                Map.of("logbook/quest/1.json", rule("").replace("1-6", "1-5").getBytes()));
        assertThrows(IllegalArgumentException.class, () -> PreparedGameDefinitions.prepare(wrong));
    }

    @Test
    void capabilityMustBeDeclaredAndSupported() throws Exception {
        var definition = JSON.readTree(rule(""));
        assertThrows(java.io.IOException.class,
                () -> DefinitionPackCompatibility.requireDefinition("logbook/quest/1.json", definition, Set.of()));
        DefinitionPackCompatibility.requireDefinition("logbook/quest/1.json", definition,
                Set.of(DefinitionPackCompatibility.PORT_ARRIVAL));
        var manifest = JSON.readTree(
                "{\"format\":\"logbook-data-pack-v2\",\"required_capabilities\":[\"quest-port-arrival-v1\"]}");
        assertEquals(Set.of(DefinitionPackCompatibility.PORT_ARRIVAL),
                DefinitionPackCompatibility.requireManifest(manifest));
    }

    @Test
    void threeRecurringDefinitionsUseObservedArrivalWithUnchangedTargets() throws Exception {
        for (int id : List.of(905, 912, 945))
            try (var in = getClass().getResourceAsStream("/logbook/quest/" + id + ".json")) {
                var c = JSON.readValue(in, AppQuestCondition.class);
                var arrival = c.getConditions().stream().filter(AppQuestCondition.Condition::isArrival).toList();
                assertEquals(1, arrival.size());
                assertEquals(id == 945 ? 2 : 1, arrival.getFirst().getCount());
                assertNull(arrival.getFirst().getCells());
                assertTrue(arrival.getFirst().toString().contains("観測記録のみ"));
            }
    }
}
