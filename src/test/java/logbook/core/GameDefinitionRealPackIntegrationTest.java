package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;
import tools.jackson.databind.ObjectMapper;
import logbook.bean.AppQuestCondition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual committed game pack -> installer -> immutable plugin API; isolated temporary storage only. */
class GameDefinitionRealPackIntegrationTest {
    private static final ObjectMapper JSON = logbook.internal.json.JsonMappers.builder().build();
    @TempDir
    Path isolated;

    private Path pack() {
        String name = System.getProperty("logbook.test.dataPackPath");
        assumeTrue(name != null && !name.isBlank(), "Requires the real CI-generated game data pack");
        Path pack = Path.of(name).toAbsolutePath();
        assertTrue(Files.isRegularFile(pack));
        return pack;
    }

    private String install(Path root, Path pack) throws Exception {
        String checksum;
        try (InputStream input = Files.newInputStream(pack)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) != -1;)
                digest.update(buffer, 0, read);
            checksum = HexFormat.of().formatHex(digest.digest());
        }
        return DefinitionPackInstaller.install(root, pack, checksum);
    }

    @Test
    void allRealDefinitionsMatchPackBytesAndAllSixCategories() throws Exception {
        Path pack = pack();
        Path root = isolated.resolve("data");
        String generation = install(root, pack);
        var service = new GameDefinitionService(error -> fail(error));
        byte[] pointer = Files.readAllBytes(root.resolve("active.json"));
        var snapshot = service.reload(root);
        assertEquals(generation, snapshot.generation());
        Set<String> expected = new HashSet<>();
        Set<String> categories = new HashSet<>();
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            var manifest = JSON.readTree(zip.getInputStream(zip.getEntry("manifest.json")));
            for (var entry : manifest.path("files")) {
                String path = entry.path("path").asText();
                String resource = "logbook/" + path.substring("definitions/".length());
                assertTrue(expected.add(resource));
                categories.add(resource.split("/")[1]);
                try (InputStream actual = snapshot.openResource(resource).orElseThrow();
                        InputStream original = zip.getInputStream(zip.getEntry(path))) {
                    assertArrayEquals(original.readAllBytes(), actual.readAllBytes(), resource);
                }
            }
        }
        assertEquals(Set.of("quest", "eventquest", "mission", "map", "kousyou", "supplemental"), categories);
        assertEquals(expected, snapshot.resourceNames());
        assertTrue(expected.size() >= 152, "Committed game definition coverage unexpectedly shrank");
        assertArrayEquals(pointer, Files.readAllBytes(root.resolve("active.json")));
        assertFalse(Files.exists(isolated.resolve("logbook-kai2.jar")));
        System.out.println("SNAPSHOT_REAL_PACK_BYTE_EQUIVALENCE " + expected.size());
    }

    @Test
    void allRealQuestDefinitionsDecodeThroughSnapshotStreams() throws Exception {
        Path root = isolated.resolve("data");
        install(root, pack());
        var snapshot = new GameDefinitionService(error -> fail(error)).reload(root);
        int count = 0;
        for (String name : snapshot.resourceNames()) {
            if (!name.startsWith("logbook/quest/") && !name.startsWith("logbook/eventquest/"))
                continue;
            try (InputStream input = snapshot.openResource(name).orElseThrow()) {
                assertNotNull(JSON.readValue(input, AppQuestCondition.class), name);
            }
            count++;
        }
        assertTrue(count >= 85);
        System.out.println("SNAPSHOT_REAL_QUEST_MODEL_DECODE " + count);
    }

    @Test
    void corruptInstalledFileDoesNotReplacePreviouslyPublishedRealPack() throws Exception {
        Path root = isolated.resolve("data");
        String generation = install(root, pack());
        var service = new GameDefinitionService(error -> fail(error));
        var old = service.reload(root);
        String name = "logbook/quest/1045.json";
        byte[] retained;
        try (var input = old.openResource(name).orElseThrow()) {
            retained = input.readAllBytes();
        }
        // Change this test's isolated installation only, never the repository or a real installation.
        Files.writeString(root.resolve("generations").resolve(generation).resolve("definitions/quest/1045.json"),
                "{broken");
        assertThrows(java.io.IOException.class, () -> service.reload(root));
        assertSame(old, service.snapshot());
        try (var input = service.snapshot().openResource(name).orElseThrow()) {
            assertArrayEquals(retained, input.readAllBytes());
        }
    }

    @Test
    void repeatLoadIsANoOpAndUserSentinelsAreUnchanged() throws Exception {
        Path root = isolated.resolve("data");
        install(root, pack());
        for (String path : new String[] { "config/user.json", "plugins/plugin.jar", "history/battle.csv" }) {
            Path file = isolated.resolve(path);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "keep:" + path, StandardCharsets.UTF_8);
        }
        var service = new GameDefinitionService(error -> fail(error));
        AtomicInteger events = new AtomicInteger();
        service.subscribe(event -> events.incrementAndGet());
        var first = service.reload(root);
        assertSame(first, service.reload(root));
        assertEquals(1, events.get());
        for (String path : new String[] { "config/user.json", "plugins/plugin.jar", "history/battle.csv" }) {
            assertEquals("keep:" + path, Files.readString(isolated.resolve(path), StandardCharsets.UTF_8));
        }
    }
}
