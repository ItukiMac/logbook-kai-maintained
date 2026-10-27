package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class QuestEditorDocumentTest {
    private static JsonNode json(String text) throws Exception { return QuestEditorDocument.mapper().readTree(text); }

    @Test void fleetNumbersHaveStrictRangeDuplicateAndEmptyValidation() {
        assertEquals(Set.of(1, 3), QuestEditorDocument.deckIds(" 1,3 "));
        assertNull(QuestEditorDocument.deckIds(" "));
        for (String text : new String[] {"0", "5", "1,1", "1,", ",1", "1.0", "１", "-1", "a"})
            assertThrows(IllegalArgumentException.class, () -> QuestEditorDocument.deckIds(text), text);
    }
    @Test void sourceFieldsRejectCoercionInsteadOfDroppingRequirements() throws Exception {
        for (String filter : new String[] {"[]", "{\"deckIds\":[]}", "{\"deckIds\":[\"1\"]}",
                "{\"deckIds\":[1,1]}", "{\"deckIds\":[4294967297]}", "{\"noRetreat\":\"true\"}"}) {
            var doc = json("{\"filter\":" + filter + "}");
            assertThrows(IOException.class, () -> QuestEditorDocument.validate(doc));
        }
        QuestEditorDocument.validate(json("{\"filter\":{\"deckIds\":[1,4],\"noRetreat\":false}}"));
    }
    @Test void strictParserRefusesDuplicateFieldsAndTrailingDocuments() {
        assertThrows(IOException.class, () -> json("{\"filter\":{},\"filter\":{}}"));
        assertThrows(IOException.class, () -> json("{} {}"));
    }
    @Test void noEditPreservesUnknownAndUnrepresentedValues() throws Exception {
        var original = json("{\"future\":{\"x\":1},\"filter\":{\"deckIds\":[1],\"noRetreat\":true,\"futureRule\":8},\"conditions\":[{\"start\":true,\"rank\":[\"C\",\"D\"]}]}");
        var form = json("{\"filter\":{\"deckIds\":[1],\"noRetreat\":true},\"conditions\":[]}");
        assertEquals(original, QuestEditorDocument.merge(original, form, form));
    }
    @Test void unrelatedEditRetainsNestedFleetAlternativesAndDifference() throws Exception {
        var original = json("{\"customName\":\"old\",\"filter\":{\"fleet\":{\"operator\":\"OR\",\"conditions\":[{\"difference\":true,\"name\":[\"a\"]}]}}}");
        var before = json("{\"customName\":\"old\",\"filter\":{\"fleet\":{\"operator\":\"AND\",\"conditions\":[]}}}");
        var after = json("{\"customName\":\"new\",\"filter\":{\"fleet\":{\"operator\":\"AND\",\"conditions\":[]}}}");
        var result = QuestEditorDocument.merge(original, before, after);
        assertEquals("new", result.path("customName").asText());
        assertEquals(original.path("filter"), result.path("filter"));
        assertEquals("old", original.path("customName").asText());
    }
    @Test void countChangePreservesUnsupportedRanksAndRowProperties() throws Exception {
        var original = json("{\"conditions\":[{\"count\":1,\"rank\":[\"S\",\"C\"],\"start\":true}]}");
        var before = json("{\"conditions\":[{\"count\":1,\"rank\":[\"S\"]}]}");
        var after = json("{\"conditions\":[{\"count\":2,\"rank\":[\"S\"]}]}");
        var result = QuestEditorDocument.merge(original, before, after);
        assertEquals(2, result.path("conditions").get(0).path("count").asInt());
        assertEquals(original.path("conditions").get(0).path("rank"), result.path("conditions").get(0).path("rank"));
        assertTrue(result.path("conditions").get(0).path("start").asBoolean());
    }
    @Test void ambiguousRowDeletionIsRejectedRatherThanLosingHiddenConditions() throws Exception {
        var original = json("{\"conditions\":[{\"count\":1,\"start\":true}]}");
        var before = json("{\"conditions\":[{\"count\":1}]}");
        assertThrows(IOException.class, () -> QuestEditorDocument.merge(original, before, json("{\"conditions\":[]}")));
    }
    @Test void explicitNewGuardAndUnsetAreRoundTripped() throws Exception {
        var original = json("{\"filter\":{\"deckIds\":[1],\"noRetreat\":true,\"unknown\":4}}");
        var before = json("{\"filter\":{\"deckIds\":[1],\"noRetreat\":true}}");
        var after = json("{\"filter\":{\"deckIds\":[2,4]}}");
        var result = QuestEditorDocument.merge(original, before, after);
        assertEquals(json("[2,4]"), result.path("filter").path("deckIds"));
        assertFalse(result.path("filter").has("noRetreat"));
        assertEquals(4, result.path("filter").path("unknown").asInt());
    }
    @Test void falseAndNullGuardsCanBeExplicitlyEnabled() throws Exception {
        for (String originalValue : new String[] {"false", "null"}) {
            var original = json("{\"filter\":{\"noRetreat\":" + originalValue + ",\"future\":1}}");
            var before = json("{\"filter\":{}}");
            var after = json("{\"filter\":{\"noRetreat\":true}}");
            var result = QuestEditorDocument.merge(original, before, after);
            assertTrue(result.path("filter").path("noRetreat").asBoolean());
            assertEquals(1, result.path("filter").path("future").asInt());
        }
    }
}
