package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GameDefinitionLoaderTest {
    @TempDir Path root;
    private static final ObjectMapper JSON = logbook.internal.json.JsonMappers.builder().build();
    private Path stage(String id, Map<String, String> files) throws Exception {
        Path generation = root.resolve("generations").resolve(id);
        Files.createDirectories(generation.resolve("definitions"));
        List<Map<String, Object>> manifest = new ArrayList<>();
        for (var item : files.entrySet()) {
            byte[] bytes = item.getValue().getBytes(StandardCharsets.UTF_8);
            Path target = generation.resolve("definitions/" + item.getKey());
            Files.createDirectories(target.getParent()); Files.write(target, bytes);
            manifest.add(Map.of("path", "definitions/" + item.getKey(), "bytes", bytes.length,
                    "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))));
        }
        JSON.writeValue(generation.resolve("manifest.json").toFile(), Map.of("format", DefinitionPackInstaller.FORMAT, "files", manifest));
        JSON.writeValue(root.resolve("active.json").toFile(), Map.of("schema", DefinitionPackInstaller.POINTER_FORMAT, "generation", id));
        return generation;
    }
    @Test void loadsAllSixManagedCategoriesWithoutWritingRoot() throws Exception {
        stage("a".repeat(64), Map.of("quest/1.json", "{}", "eventquest/2.json", "[]", "mission/1/01.json", "{}",
                "map/mapping.json", "{}", "kousyou/kousyou.json", "[]", "supplemental/ships.json", "{}"));
        byte[] before = Files.readAllBytes(root.resolve("active.json"));
        var snapshot = GameDefinitionLoader.load(root);
        assertEquals(6, snapshot.resourceNames().size());
        assertEquals("a".repeat(64), snapshot.generation());
        assertArrayEquals(before, Files.readAllBytes(root.resolve("active.json")));
    }
    @Test void badHashKeepsCurrentSnapshot() throws Exception {
        Path generation = stage("a".repeat(64), Map.of("quest/1.json", "{}"));
        var service = new GameDefinitionService(e -> fail(e));
        var old = service.reload(root);
        Files.writeString(generation.resolve("definitions/quest/1.json"), "[]");
        assertThrows(java.io.IOException.class, () -> service.reload(root));
        assertSame(old, service.snapshot());
    }
    @Test void malformedJsonIsRejectedEvenWithMatchingHash() throws Exception {
        stage("a".repeat(64), Map.of("quest/1.json", "{broken"));
        assertThrows(java.io.IOException.class, () -> GameDefinitionLoader.load(root));
    }
    @Test void trailingJsonAndDuplicateKeysAreRejected() throws Exception {
        stage("a".repeat(64), Map.of("quest/1.json", "{} {}"));
        assertThrows(java.io.IOException.class, () -> GameDefinitionLoader.load(root));
        stage("b".repeat(64), Map.of("quest/1.json", "{\"x\":1,\"x\":2}"));
        assertThrows(java.io.IOException.class, () -> GameDefinitionLoader.load(root));
    }
    @Test void newPointerDoesNotMutatePreviouslyHeldBytes() throws Exception {
        stage("a".repeat(64), Map.of("quest/1.json", "{\"v\":1}"));
        var old = GameDefinitionLoader.load(root);
        stage("b".repeat(64), Map.of("quest/1.json", "{\"v\":2}"));
        var next = GameDefinitionLoader.load(root);
        assertNotEquals(old.generation(), next.generation());
        try (var in = old.openResource("logbook/quest/1.json").orElseThrow()) {
            assertEquals("{\"v\":1}", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    @Test void noPointerRequiresExplicitStagedGeneration() {
        assertThrows(java.io.IOException.class, () -> GameDefinitionLoader.load(root));
    }
    @Test void symlinkedDefinitionIsRejected() throws Exception {
        Path generation = stage("a".repeat(64), Map.of("quest/1.json", "{}"));
        Path target = generation.resolve("definitions/quest/1.json");
        Path retained = generation.resolve("original.json");
        Files.copy(target, retained);
        Files.delete(target); // only this test's newly-created temporary fixture
        Files.createSymbolicLink(target, retained);
        assertThrows(java.io.IOException.class, () -> GameDefinitionLoader.load(root));
    }
    @Test void duplicateManifestEntryIsRejected() throws Exception {
        Path generation = stage("a".repeat(64), Map.of("quest/1.json", "{}"));
        var manifest = JSON.readTree(generation.resolve("manifest.json").toFile());
        var files = (tools.jackson.databind.node.ArrayNode) manifest.path("files");
        files.add(files.get(0).deepCopy());
        JSON.writeValue(generation.resolve("manifest.json").toFile(), manifest);
        assertThrows(java.io.IOException.class, () -> GameDefinitionLoader.load(root));
    }
}
