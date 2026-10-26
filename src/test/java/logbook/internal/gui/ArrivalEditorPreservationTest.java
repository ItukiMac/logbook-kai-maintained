package logbook.internal.gui;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class ArrivalEditorPreservationTest {
    @Test void hiddenArrivalRequirementSurvivesCountEditWithoutChangingOriginal() throws Exception {
        var json=QuestEditorDocument.mapper();
        var original=json.readTree("{\"conditions\":[{\"area\":[\"1-6\"],\"arrival\":true,\"count\":1}]}");
        var before=json.readTree("{\"conditions\":[{\"area\":[\"1-6\"],\"count\":1}]}");
        var after=before.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)after.path("conditions").get(0)).put("count",2);
        var edited=QuestEditorDocument.merge(original,before,after);
        QuestEditorDocument.validate(edited);
        assertTrue(edited.path("conditions").get(0).path("arrival").asBoolean());
        assertEquals(2,edited.path("conditions").get(0).path("count").asInt());
        assertEquals(1,original.path("conditions").get(0).path("count").asInt());
    }
    @Test void editorCannotSilentlyConvertArrivalIntoBattleOrAnotherMap() throws Exception {
        var json=QuestEditorDocument.mapper();
        for(String extra:new String[]{",\"rank\":[\"S\"]",",\"boss\":true",",\"cells\":[\"B\"]"}) {
            var doc=json.readTree("{\"conditions\":[{\"area\":[\"1-6\"],\"arrival\":true,\"count\":1"+extra+"}]}");
            assertThrows(java.io.IOException.class,()->QuestEditorDocument.validate(doc));
        }
        assertThrows(java.io.IOException.class,()->QuestEditorDocument.validate(json.readTree("{\"conditions\":[{\"area\":[\"1-5\"],\"arrival\":true,\"count\":1}]}")));
    }
}
