package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import logbook.plugin.api.GameDefinitionSnapshot;

class QuestBattleRequirementSchemaTest {
    private static void prepare(String filter) {
        String data = "{\"conditions\":[{\"count\":1}],\"filter\":" + filter + "}";
        PreparedGameDefinitions.prepare(new GameDefinitionSnapshot("test",
                Map.of("logbook/quest/1.json", data.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void validRequirementsAndLegacyDefinitionsLoad() {
        for (String filter : java.util.List.of("{}", "{\"deckIds\":[1]}", "{\"deckIds\":[1,2,3,4],\"noRetreat\":true}",
                "{\"noRetreat\":false}"))
            assertDoesNotThrow(() -> prepare(filter));
    }

    @Test
    void invalidOrCoercedFleetIdsAreRejectedBeforePublication() {
        for (String value : java.util.List.of("null", "[]", "[0]", "[5]", "[1,1]", "[1.0]", "[\"1\"]", "[true]",
                "[null]", "1"))
            assertThrows(IllegalArgumentException.class, () -> prepare("{\"deckIds\":" + value + "}"), value);
    }

    @Test
    void retreatFlagMustBeBoolean() {
        for (String value : java.util.List.of("null", "1", "\"true\"", "[]"))
            assertThrows(IllegalArgumentException.class, () -> prepare("{\"noRetreat\":" + value + "}"), value);
    }
}
