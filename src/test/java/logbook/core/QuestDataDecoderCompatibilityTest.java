package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import logbook.bean.AppQuestCondition;
import org.junit.jupiter.api.Test;

/**
 * Ensures every packaged quest definition is deserializable by the same domain
 * model as the production quest-condition loader, not merely valid JSON.
 *
 * The CI workflow generates the entire data ZIP from committed definitions.
 */
class QuestDataDecoderCompatibilityTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void everyQuestDefinitionDecodesThroughProductionConditionModel() throws Exception {
        String fileName = System.getProperty("logbook.test.dataPackPath");
        assumeTrue(fileName != null && !fileName.isBlank(), "Requires CI-generated game data pack");

        Path pack = Path.of(fileName).toAbsolutePath();
        assertTrue(Files.isRegularFile(pack), "Missing game data pack for decoder test");

        int decoded = 0;
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            ZipEntry header = zip.getEntry("manifest.json");
            assertNotNull(header);
            JsonNode manifest;
            try (InputStream in = zip.getInputStream(header)) {
                manifest = MAPPER.readTree(in);
            }
            for (JsonNode item : manifest.path("files")) {
                String path = item.path("path").asText();
                if (!path.startsWith("definitions/quest/")
                        && !path.startsWith("definitions/eventquest/")) {
                    continue;
                }
                ZipEntry entry = zip.getEntry(path);
                assertNotNull(entry, "Missing definition " + path);
                try (InputStream in = zip.getInputStream(entry)) {
                    AppQuestCondition condition = assertDoesNotThrow(
                            () -> MAPPER.readValue(in, AppQuestCondition.class),
                            "Cannot decode existing quest condition: " + path);
                    assertNotNull(condition, "Null quest condition: " + path);
                }
                decoded++;
            }
        }
        assertTrue(decoded >= 75, "Unexpectedly few quest conditions: " + decoded);
        System.out.println("Verified " + decoded + " real quest conditions with AppQuestCondition");
    }
}
