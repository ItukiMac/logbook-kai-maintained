package logbook.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

class ManagedGameJsonResourceTest {
    @TempDir Path temp;

    @AfterEach
    void clearConfiguration() {
        System.clearProperty(ExternalQuestDefinitions.ROOT_PROPERTY);
    }

    @Test
    void activePackSuppliesMissionMappingImprovementAndSupplemental() throws Exception {
        Path root = temp.resolve("data");
        Path definitions = root.resolve("generations")
                .resolve("a".repeat(64)).resolve("definitions");
        String[] names = {
            "mission/1/1.json",
            "map/mapping.json",
            "kousyou/kousyou.json",
            "supplemental/ships.json"
        };
        for (String name : names) {
            Path file = definitions.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "{\"source\":\"external\"}");
        }
        Files.writeString(root.resolve("active.json"),
                "{\"schema\":\"logbook-active-data-v1\",\"generation\":\""
                + "a".repeat(64) + "\"}");
        System.setProperty(ExternalQuestDefinitions.ROOT_PROPERTY, root.toString());

        for (String name : names) {
            String resourceName = "logbook/" + name;
            URL url = LogBookCoreServices.getResource(resourceName);
            assertNotNull(url, "Missing external URL: " + resourceName);
            try (InputStream input = LogBookCoreServices.getResourceAsStream(resourceName)) {
                assertNotNull(input, "Missing external stream: " + resourceName);
                assertEquals("{\"source\":\"external\"}",
                        new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    void missingFileInActivatedGenerationCannotResurrectJarResource() throws Exception {
        Path root = temp.resolve("data");
        Path definitions = root.resolve("generations")
                .resolve("b".repeat(64)).resolve("definitions");
        Files.createDirectories(definitions.resolve("map"));
        Files.writeString(root.resolve("active.json"),
                "{\"schema\":\"logbook-active-data-v1\",\"generation\":\""
                + "b".repeat(64) + "\"}");
        System.setProperty(ExternalQuestDefinitions.ROOT_PROPERTY, root.toString());

        assertNull(LogBookCoreServices.getResource("logbook/map/mapping.json"));
        assertNull(LogBookCoreServices.getResourceAsStream("logbook/map/mapping.json"));
    }

    @Test
    void userOrCaptureOptionsAreNeverManagedByRemoteDefinitions() throws Exception {
        assertFalse(ExternalQuestDefinitions.isManagedGameDataResource(
                "logbook/capture_options/list.json"));
        assertFalse(ExternalQuestDefinitions.isManagedGameDataResource(
                "logbook/bouyomi/settings.json"));
        assertFalse(ExternalQuestDefinitions.isManagedGameDataResource("../mission/a.json"));
        assertFalse(ExternalQuestDefinitions.isManagedGameDataResource(
                "logbook/mission/../map/mapping.json"));
        assertTrue(ExternalQuestDefinitions.isManagedGameDataResource(
                "logbook/mission/1/1.json"));
    }

    @Test
    void bundledMapResourceAvailableWithExternalUpdatingDisabled() {
        assertNotNull(LogBookCoreServices.getResourceAsStream("logbook/map/mapping.json"));
    }
}
