package logbook.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in real-world Release check. Uses the production GitHub Releases API
 * and download route, but writes exclusively under the JUnit temp directory.
 * No user data, game API, running application or plugins are accessed.
 */
class PublicDataReleaseIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path temp;

    private String previousOptIn;
    private String previousRoot;

    @AfterEach
    void restoreProperties() {
        if (previousOptIn != null) {
            System.setProperty(DataPackReleaseClient.AUTO_UPDATE_PROPERTY, previousOptIn);
        } else {
            System.clearProperty(DataPackReleaseClient.AUTO_UPDATE_PROPERTY);
        }
        if (previousRoot != null) {
            System.setProperty(ExternalQuestDefinitions.ROOT_PROPERTY, previousRoot);
        } else {
            System.clearProperty(ExternalQuestDefinitions.ROOT_PROPERTY);
        }
    }

    @Test
    void downloadsAndActivatesPublicJsonReleaseOnceWithoutChangingApplication() throws Exception {
        assumeTrue(Boolean.getBoolean("logbook.test.publicRelease"),
                "Opt-in network test; run only from dedicated CI workflow");

        previousOptIn = System.getProperty(DataPackReleaseClient.AUTO_UPDATE_PROPERTY);
        previousRoot = System.getProperty(ExternalQuestDefinitions.ROOT_PROPERTY);
        Path root = temp.resolve("data-only");
        System.setProperty(DataPackReleaseClient.AUTO_UPDATE_PROPERTY, "true");
        System.setProperty(ExternalQuestDefinitions.ROOT_PROPERTY, root.toString());

        // The real Release must already be published. This does not create one.
        assertTrue(DataPackReleaseClient.refreshConfiguredData(),
                "Could not activate the latest stable data-v GitHub Release");
        Path active = DefinitionPackInstaller.activeDefinitionsRoot(root);
        assertTrue(Files.isDirectory(active));
        Path manifestFile = active.getParent().resolve("manifest.json");
        assertTrue(Files.isRegularFile(manifestFile));
        JsonNode manifest = JSON.readTree(Files.readAllBytes(manifestFile));
        DefinitionPackCompatibility.requireManifest(manifest);
        assertTrue(manifest.path("files").isArray());
        assertTrue(manifest.path("files").size() >= 85);

        Path quest = active.resolve("quest/1045.json");
        assertTrue(Files.isRegularFile(quest));
        assertEquals("出撃", JSON.readTree(quest.toFile()).path("type").asText());
        assertTrue(Files.isRegularFile(active.resolve("eventquest/441.json")));
        assertTrue(Files.isDirectory(active.resolve("mission")));
        assertTrue(Files.isRegularFile(active.resolve("kousyou/kousyou.json")));

        // Repeated startup should never re-apply unchanged data.
        String pointerBefore = Files.readString(root.resolve("active.json"));
        assertFalse(DataPackReleaseClient.refreshConfiguredData());
        assertEquals(pointerBefore, Files.readString(root.resolve("active.json")));

        // Generation identity must be a hash and not a mutable remote path.
        String generation = active.getParent().getFileName().toString();
        assertTrue(generation.matches("[0-9a-f]{64}"));
        assertEquals(generation, JSON.readTree(pointerBefore).path("generation").asText());
        assertFalse(Files.exists(root.resolve("logbook-kai.jar")));
        assertFalse(Files.exists(root.resolve("auto.sh")));
        assertFalse(Files.exists(root.resolve("plugins")));
        System.out.println("Public GitHub Release verified and activated in an isolated directory: "
                + generation + "; files=" + manifest.path("files").size());
    }
}
