package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.zip.ZipFile;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Cross-language integration: run after tools/build_data_pack.py created a
 * real data ZIP from the checked out resource definitions (no game traffic).
 */
class RealDefinitionPackIntegrationTest {
    private static final ObjectMapper JSON = logbook.internal.json.JsonMappers.builder().build();
    private static final String PACK_PROPERTY = "logbook.test.dataPackPath";

    @TempDir
    Path isolated;

    @AfterEach
    void reset() {
        System.clearProperty(ExternalQuestDefinitions.ROOT_PROPERTY);
    }

    @Test
    void installsEntireRealPackAndReadsQuestWithoutReplacingApplicationJar() throws Exception {
        String name = System.getProperty(PACK_PROPERTY);
        assumeTrue(name != null && !name.isBlank(), "real pack integration is enabled by CI only");
        Path pack = Path.of(name).toAbsolutePath();
        assertTrue(Files.isRegularFile(pack), "CI must generate a data pack before running Maven");

        Set<String> listed;
        int count;
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            JsonNode manifest = JSON.readTree(zip.getInputStream(zip.getEntry("manifest.json")));
            assertNotNull(DefinitionPackCompatibility.requireManifest(manifest));
            var entries = manifest.path("files");
            assertTrue(entries.isArray());
            count = entries.size();
            assertTrue(count >= 85, "Expected complete checked-in game definition set");
            var paths = new java.util.HashSet<String>();
            for (JsonNode entry : entries) {
                String item = entry.path("path").asText();
                assertTrue(paths.add(item), "Duplicate manifest entry: " + item);
                assertNotNull(zip.getEntry(item), "Missing entry: " + item);
            }
            assertEquals(count + 1, zip.size());
            listed = Set.copyOf(paths);
        }
        assertTrue(listed.contains("definitions/quest/1045.json"));
        assertTrue(listed.contains("definitions/eventquest/441.json"));

        byte[] bytes = Files.readAllBytes(pack);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Path installation = isolated.resolve("data");
        String generation = DefinitionPackInstaller.install(installation, pack, digest);
        assertEquals(digest, generation);
        assertTrue(Files.isRegularFile(installation.resolve("active.json")));

        System.setProperty(ExternalQuestDefinitions.ROOT_PROPERTY, installation.toString());
        try (InputStream quest = LogBookCoreServices.getQuestResourceAsStream(1045, true)) {
            assertNotNull(quest);
            JsonNode doc = JSON.readTree(quest);
            assertEquals("出撃", doc.path("type").asText());
        }
        try (InputStream event = LogBookCoreServices.getQuestResourceAsStream(441, false)) {
            assertNotNull(event);
            assertEquals("遠征", JSON.readTree(event).path("type").asText());
        }
        assertEquals(generation, DefinitionPackInstaller.install(installation, pack, digest));
        assertFalse(Files.exists(isolated.resolve("logbook-kai.jar")),
                "Data installation must never create/replace the application JAR");
    }

    @Test
    void activeGenerationMissingLegacyIdMustNotUseBundledDefinition() throws Exception {
        // This quest is in the original application JAR, but it can be
        // intentionally retired from an updated generation.
        assertNotNull(LogBookCoreServices.getQuestResourceAsStream(1005, true),
                "Baseline fixture needs bundled quest 1005");

        Path root = isolated.resolve("state");
        Path managed = root.resolve("generations").resolve("f".repeat(64)).resolve("definitions");
        Files.createDirectories(managed.resolve("quest"));
        Files.writeString(root.resolve("active.json"),
                "{\"schema\":\"logbook-active-data-v1\",\"generation\":\"" + "f".repeat(64) + "\"}");
        Files.writeString(managed.resolve("quest/1045.json"), "{\"type\":\"出撃\"}");
        System.setProperty(ExternalQuestDefinitions.ROOT_PROPERTY, root.toString());
        assertNull(LogBookCoreServices.getQuestResourceAsStream(1005, true),
                "Removed quests must not come back from packaged resources");
        assertNull(LogBookCoreServices.getQuestResource(1005, true));
        assertNotNull(LogBookCoreServices.getQuestResourceAsStream(1045, true));
    }

    @Test
    void withoutDataRootBundledDefinitionsStillWork() {
        System.clearProperty(ExternalQuestDefinitions.ROOT_PROPERTY);
        assertNotNull(LogBookCoreServices.getQuestResourceAsStream(1005, true));
    }
}
