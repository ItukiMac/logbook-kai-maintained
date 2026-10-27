package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import logbook.bean.AppQuestCondition;
import logbook.internal.kancolle.QuestCollect;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Independent source transcriptions exercise actual mission-union and whole-quest predicates. */
class ExpeditionQuestContractTest {
    private static final ObjectMapper JSON = logbook.internal.json.JsonMappers.builder().enable(tools.jackson.core.json.JsonReadFeature.ALLOW_JAVA_COMMENTS).build();
    private static byte[] bytes(String name) throws Exception {
        try (InputStream in = ExpeditionQuestContractTest.class.getResourceAsStream('/' + name)) {
            assertNotNull(in, name); return in.readAllBytes();
        }
    }
    private static List<String> names(JsonNode node) {
        List<String> values = new ArrayList<>(); node.forEach(n -> values.add(n.asText())); return values;
    }
    @TestFactory Stream<DynamicTest> reviewedExpeditionPredicates() throws Exception {
        JsonNode facts = JSON.readTree(bytes("maintenance/r5-expedition-contracts.json"));
        assertEquals(13, facts.path("definitions").size());
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode record : facts.path("definitions")) {
            int id = record.path("id").asInt();
            tests.add(DynamicTest.dynamicTest("expedition quest " + id, () -> {
                byte[] raw = bytes("logbook/quest/" + id + ".json");
                assertEquals(record.path("definition_sha256").asText(),
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
                var quest = JSON.readValue(raw, AppQuestCondition.class);
                assertEquals(AppQuestCondition.Type.遠征, quest.getType());
                assertEquals(record.path("period").asText(), quest.getResetType());
                Integer month = record.path("yearly_reset_month").isNull() ? null : record.path("yearly_reset_month").asInt();
                assertEquals(month, quest.getYearlyResetMonth());
                assertEquals(record.path("rules").size(), quest.getConditions().size());
                assertFalse(quest.test(new QuestCollect()));
                var full = new QuestCollect();
                for (int index = 0; index < quest.getConditions().size(); index++) {
                    var actual = quest.getConditions().get(index); var expected = record.path("rules").get(index);
                    var allowed = names(expected.path("missions")); int count = expected.path("count").asInt();
                    assertEquals(new HashSet<>(allowed), new HashSet<>(actual.getMissions()));
                    assertEquals(count, actual.getCount());
                    var c = new QuestCollect(); String first = allowed.isEmpty() ? "arbitrary-expedition" : allowed.get(0);
                    c.getMissions().put(first, (long) count - 1); assertFalse(actual.test(c));
                    c.getMissions().put(first, (long) count); assertTrue(actual.test(c));
                    c.getMissions().clear(); c.getMissions().put("unrelated", 999L);
                    assertEquals(allowed.isEmpty(), actual.test(c));
                    if (allowed.size() > 1) {
                        // Either alternative suffices; mixed counts are summed, not required separately.
                        for (String alternative : allowed) {
                            c.getMissions().clear(); c.getMissions().put(alternative, (long) count); assertTrue(actual.test(c));
                        }
                        c.getMissions().clear(); c.getMissions().put(allowed.get(0), (long) count - 1);
                        c.getMissions().put(allowed.get(1), 1L); assertTrue(actual.test(c));
                    }
                    full.getMissions().put(first, (long) count);
                }
                assertTrue(quest.test(full));
                // Independent required expeditions are AND, never one interchangeable aggregate.
                if (quest.getConditions().size() > 1) {
                    for (var required : quest.getConditions()) {
                        String name = required.getMissions().get(0); Long old = full.getMissions().remove(name);
                        assertFalse(quest.test(full), "Missing required expedition " + name);
                        full.getMissions().put(name, old);
                    }
                }
            }));
        }
        return tests.stream();
    }
}
