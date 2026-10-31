package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import logbook.plugin.api.QuestListV1;
import org.junit.jupiter.api.Test;

class QuestProgressInputTest {
    private static QuestListV1.Quest row(int id, String expiry, Instant observed) {
        return new QuestListV1.Quest(id, 2, 4, 2, "title", "detail", List.of(1, 2), 0, 0, 0, observed, expiry, true);
    }

    @Test
    void actualObservedQuestKeepsItsSavedTiming() {
        var input = row(905, "saved", Instant.parse("2026-10-29T00:00:00Z"));
        assertEquals(input, QuestListReadService.copy(QuestListReadService.toProgressInput(input)));
    }

    @Test
    void unknownOrdinaryTimingIsNotInvented() {
        var result = QuestListReadService.toProgressInput(row(905, null, null));
        assertNull(result.getExpire());
        assertNull(result.getQuest().getObservedAt());
    }

    @Test
    void customWithSavedTimingDoesNotReloadOrReplaceIt() {
        var input = row(10001, "saved custom expiry", null);
        assertEquals(input, QuestListReadService.copy(QuestListReadService.toProgressInput(input)));
    }

    @Test
    void pureLegacyConversionStillPreservesNullCustomTiming() {
        var input = row(10001, null, null);
        var result = QuestListReadService.toLegacy(input);
        assertNull(result.getExpire());
        assertNull(result.getQuest().getObservedAt());
        result.getQuest().getGetMaterial().clear();
        assertEquals(List.of(1, 2), input.materials());
    }
}
