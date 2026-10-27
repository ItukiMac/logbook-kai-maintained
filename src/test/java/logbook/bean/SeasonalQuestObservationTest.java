package logbook.bean;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Instant;

import tools.jackson.databind.ObjectMapper;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import org.junit.jupiter.api.Test;

/** Actual API decoding is freshness evidence; saved or hand-built beans are not. */
class SeasonalQuestObservationTest {
    private static final ObjectMapper JSON = logbook.internal.json.JsonMappers.builder().build();

    private static JsonObject apiQuest() {
        return Json.createObjectBuilder()
                .add("api_no", 840)
                .add("api_category", 2)
                .add("api_type", 2)
                .add("api_state", 2)
                .add("api_title", "Synthetic seasonal quest")
                .add("api_detail", "Synthetic observed detail")
                .build();
    }

    // Reflection keeps the same regression tests compilable before the new
    // observation field exists, so the original failure can be recorded.
    private static Instant observedAt(QuestList.Quest quest) throws Exception {
        return (Instant) QuestList.Quest.class.getMethod("getObservedAt").invoke(quest);
    }

    private static void observedAt(QuestList.Quest quest, Instant value) throws Exception {
        QuestList.Quest.class.getMethod("setObservedAt", Instant.class).invoke(quest, value);
    }

    @Test void actualApiDecodeRecordsReceiptTimeAndRetainsObservedFields() throws Exception {
        Instant before = Instant.now();
        QuestList.Quest quest = QuestList.Quest.toQuest(apiQuest());
        Instant after = Instant.now();
        Instant observation = observedAt(quest);
        assertNotNull(observation);
        assertFalse(observation.isBefore(before));
        assertFalse(observation.isAfter(after));
        assertEquals(840, quest.getNo());
        assertEquals("Synthetic seasonal quest", quest.getTitle());
        assertEquals("Synthetic observed detail", quest.getDetail());
        assertEquals(2, quest.getCategory());
        assertEquals(2, quest.getType());
        assertEquals(observation, observedAt(quest), "Reading a retained quest must not refresh its receipt time");
    }

    @Test void manuallyConstructedAndSavedBeansDoNotInventAReceiptTime() throws Exception {
        QuestList.Quest manual = new QuestList.Quest();
        manual.setNo(840);
        manual.setTitle("Synthetic seasonal quest");
        assertNull(observedAt(manual));
        QuestList.Quest restored = JSON.readValue("{\"no\":840,\"title\":\"Saved quest\"}", QuestList.Quest.class);
        assertNull(observedAt(restored));
    }

    @Test void configJsonRoundtripDoesNotCarryLiveObservationIntoRestart() throws Exception {
        QuestList.Quest live = QuestList.Quest.toQuest(apiQuest());
        assertNotNull(observedAt(live));
        String saved = JSON.writeValueAsString(live);
        assertFalse(saved.contains("observedAt"), saved);
        QuestList.Quest restored = JSON.readValue(saved, QuestList.Quest.class);
        assertNull(observedAt(restored));
        assertEquals(live, restored, "Observation metadata must not change the saved quest value");
    }

    @Test void suppliedJsonTimestampCannotPretendToBeALiveReceipt() throws Exception {
        QuestList.Quest restored = JSON.readValue(
                "{\"no\":840,\"observedAt\":\"2026-10-27T01:00:00Z\"}", QuestList.Quest.class);
        assertEquals(840, restored.getNo());
        assertNull(observedAt(restored));
    }

    @Test void javaSerializationAlsoRequiresNewObservationAfterRestore() throws Exception {
        QuestList.Quest live = QuestList.Quest.toQuest(apiQuest());
        assertNotNull(observedAt(live));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(live);
        }
        QuestList.Quest restored;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (QuestList.Quest) in.readObject();
        }
        assertNull(observedAt(restored));
        assertEquals(live, restored);
    }

    @Test void receiptMetadataDoesNotChangeEqualityHashOrDiagnosticString() throws Exception {
        QuestList.Quest first = QuestList.Quest.toQuest(apiQuest());
        QuestList.Quest second = QuestList.Quest.toQuest(apiQuest());
        observedAt(first, Instant.parse("2026-01-01T00:00:00Z"));
        observedAt(second, Instant.parse("2026-10-27T00:00:00Z"));
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(first.toString(), second.toString());
        assertFalse(first.toString().contains("observedAt"));
    }

    @Test void listApiDecoderStampsRealEntriesAndIgnoresEmptySlots() throws Exception {
        QuestList list = QuestList.toQuestList(Json.createObjectBuilder()
                .add("api_count", 1)
                .add("api_list", Json.createArrayBuilder().add(apiQuest()).add(-1))
                .build());
        assertEquals(2, list.getList().size());
        assertNotNull(observedAt(list.getList().get(0)));
        assertNull(list.getList().get(1));
        assertNull(QuestList.Quest.toQuest(JsonValue.NULL));
        assertNull(QuestList.Quest.toQuest(Json.createValue(-1)));
    }
}
