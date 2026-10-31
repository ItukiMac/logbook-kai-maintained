package logbook.internal.json;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import logbook.bean.AppConfig;
import logbook.internal.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;

/** Both real Jackson engines, never a renamed self-comparison. Synthetic files only. */
class LegacyJsonCompatibilityTest {
    @TempDir
    Path root;
    private final com.fasterxml.jackson.databind.ObjectMapper old = new com.fasterxml.jackson.databind.ObjectMapper();

    public static final class WireBean {
        public String label = "艦隊・記録\n日本語 \"引用\" \\ /";
        public String absent = null;
        public long epoch = 1793091600000L;
        public double ratio = 0.125;
        public List<Integer> slots = List.of(1, 3, 6);
        public Map<String, Object> options = new LinkedHashMap<>();

        public WireBean() {
            options.put("enabled", true);
            options.put("limit", 3);
        }
    }

    @Test
    void bothEnginesRemainAvailableWithoutPackageCollision() {
        assertEquals("com.fasterxml.jackson.databind.ObjectMapper", old.getClass().getName());
        assertTrue(JsonMappers.builder().build().getClass().getName().startsWith("tools.jackson."));
        assertNotEquals(old.getClass(), JsonMappers.builder().build().getClass());
    }

    @Test
    void beanDefaultsAndUnicodeRoundTripInBothDirections() throws Exception {
        var modern = JsonMappers.builder().build();
        byte[] original = old.writeValueAsBytes(new WireBean());
        var restored = modern.readValue(original, WireBean.class);
        byte[] rewritten = modern.writeValueAsBytes(restored);
        assertEquals(old.readTree(original), old.readTree(rewritten));
        var legacyRestored = old.readValue(rewritten, WireBean.class);
        assertEquals(restored.label, legacyRestored.label);
        assertEquals(restored.slots, legacyRestored.slots);
        assertEquals(restored.epoch, legacyRestored.epoch);
        assertNull(legacyRestored.absent);
    }

    @Test
    void realAppConfigRemainsReadableByTheOldEngineAfterNewConfigStore() throws Exception {
        var value = new AppConfig();
        byte[] before = old.writeValueAsBytes(value);
        Path file = root.resolve(AppConfig.class.getCanonicalName() + ".json");
        Files.write(file, before);
        var store = new Config(root);
        var loaded = store.get(AppConfig.class, () -> {
            throw new AssertionError("unexpected fallback");
        });
        assertNotNull(loaded);
        store.store();
        byte[] after = Files.readAllBytes(file);
        assertEquals(old.readTree(before), old.readTree(after));
        assertNotNull(old.readValue(after, AppConfig.class));
        assertArrayEquals(before, Files.readAllBytes(file.resolveSibling(file.getFileName() + ".backup")));
    }

    @Test
    void realConfigReadKeepsUnknownLegacyFieldsAndDoesNotWriteInput() throws Exception {
        Path file = root.resolve(AppConfig.class.getCanonicalName() + ".json");
        var tree = old.valueToTree(new AppConfig());
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree).put("futureUnrecognizedField", "retained on read");
        byte[] bytes = old.writeValueAsBytes(tree);
        Files.write(file, bytes);
        assertNotNull(new Config(root).get(AppConfig.class, () -> {
            throw new AssertionError("unknown field fallback");
        }));
        assertArrayEquals(bytes, Files.readAllBytes(file));
    }

    @Test
    void corruptPrimaryStillUsesTheOldBackupWithoutRepairingEitherFile() throws Exception {
        Path file = root.resolve(AppConfig.class.getCanonicalName() + ".json");
        byte[] corrupt = "{interrupted".getBytes(StandardCharsets.UTF_8);
        byte[] backup = old.writeValueAsBytes(new AppConfig());
        Files.write(file, corrupt);
        Path saved = file.resolveSibling(file.getFileName() + ".backup");
        Files.write(saved, backup);
        assertNotNull(new Config(root).get(AppConfig.class, () -> {
            throw new AssertionError("lost backup fallback");
        }));
        assertArrayEquals(corrupt, Files.readAllBytes(file));
        assertArrayEquals(backup, Files.readAllBytes(saved));
    }

    @Test
    void strictReadersStillRejectDuplicateAndTrailingDocuments() {
        var strict = JsonMappers.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
        assertThrows(JacksonException.class, () -> strict.readTree("{\"slot\":1,\"slot\":2}"));
        assertThrows(JacksonException.class, () -> strict.readTree("{\"slot\":1} {}"));
        assertThrows(JacksonException.class, () -> strict.readTree("/* forbidden */ {}"));
    }

    @Test
    void sourceBoundaryDoesNotAccidentallyReintroduceHostJackson2() throws Exception {
        Path source = Path.of("src/main/java");
        assertTrue(Files.isDirectory(source));
        try (var paths = Files.walk(source)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(path);
                assertFalse(text.contains("com.fasterxml.jackson.databind"), path.toString());
                assertFalse(text.contains("com.fasterxml.jackson.core"), path.toString());
            }
        }
    }
}
