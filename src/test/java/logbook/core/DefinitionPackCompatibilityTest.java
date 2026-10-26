package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefinitionPackCompatibilityTest {
    static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;
    record Pack(Path path, String sha) { }
    static String hash(byte[] data) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
    static Pack pack(Path folder, String format, Object capabilities, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Map<String,Object> manifest = new LinkedHashMap<>();
        manifest.put("format", format);
        if (capabilities != null) manifest.put("required_capabilities", capabilities);
        manifest.put("files", List.of(Map.of("path", "definitions/quest/854.json", "sha256", hash(bytes), "bytes", bytes.length)));
        Path zip = Files.createTempFile(folder, "pack-", ".zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            output.putNextEntry(new ZipEntry("manifest.json")); output.write(JSON.writeValueAsBytes(manifest)); output.closeEntry();
            output.putNextEntry(new ZipEntry("definitions/quest/854.json")); output.write(bytes); output.closeEntry();
        }
        return new Pack(zip, hash(Files.readAllBytes(zip)));
    }
    private Pack legacy() throws Exception { return pack(temp, DefinitionPackCompatibility.LEGACY_FORMAT, null, "{}"); }
    @Test void newHostReadsBothFormatsAndRestoresRetainedLegacy() throws Exception {
        Path root = temp.resolve("store");
        Pack old = legacy(); DefinitionPackInstaller.install(root, old.path(), old.sha());
        Pack next = pack(temp, DefinitionPackCompatibility.CAPABILITY_FORMAT, List.of(DefinitionPackCompatibility.DECK_IDS, DefinitionPackCompatibility.NO_RETREAT),
                "{\"filter\":{\"deckIds\":[1],\"noRetreat\":true}}");
        DefinitionPackInstaller.install(root, next.path(), next.sha());
        assertEquals(next.sha(), GameDefinitionLoader.load(root).generation());
        DefinitionPackInstaller.restoreGeneration(root, old.sha());
        assertEquals(old.sha(), GameDefinitionLoader.load(root).generation());
        assertTrue(Files.exists(root.resolve("generations").resolve(next.sha())));
    }
    @Test void unsupportedCapabilitiesAndMalformedManifestsKeepActiveBytes() throws Exception {
        Path root = temp.resolve("store"); Pack old = legacy(); DefinitionPackInstaller.install(root, old.path(), old.sha());
        byte[] pointer = Files.readAllBytes(root.resolve("active.json"));
        for (Object required : List.of(List.of("future-feature"), List.of(), "quest-filter-deck-ids-v1",
                List.of(DefinitionPackCompatibility.DECK_IDS, DefinitionPackCompatibility.DECK_IDS), List.of(1))) {
            Pack invalid = pack(temp, DefinitionPackCompatibility.CAPABILITY_FORMAT, required, "{}");
            assertThrows(IOException.class, () -> DefinitionPackInstaller.install(root, invalid.path(), invalid.sha()));
            assertArrayEquals(pointer, Files.readAllBytes(root.resolve("active.json")));
        }
    }
    @Test void legacyFormatCannotSmuggleNewFields() throws Exception {
        for (String body : List.of("{\"filter\":{\"deckIds\":[1]}}", "{\"filter\":{\"noRetreat\":false}}", "{\"filter\":{\"noRetreat\":null}}")) {
            Pack invalid = pack(temp, DefinitionPackCompatibility.LEGACY_FORMAT, null, body);
            Path root = temp.resolve(UUID.randomUUID().toString());
            assertThrows(IOException.class, () -> DefinitionPackInstaller.install(root, invalid.path(), invalid.sha()));
            assertFalse(Files.exists(root.resolve("active.json")));
        }
    }
    @Test void capabilityFormatCannotOmitUsedRequirement() throws Exception {
        Pack invalid = pack(temp, DefinitionPackCompatibility.CAPABILITY_FORMAT, List.of(DefinitionPackCompatibility.DECK_IDS),
                "{\"filter\":{\"deckIds\":[1],\"noRetreat\":true}}");
        assertThrows(IOException.class, () -> DefinitionPackInstaller.install(temp.resolve("store"), invalid.path(), invalid.sha()));
    }
    @Test void legacyMetadataCannotPretendToGateOldHosts() throws Exception {
        Pack invalid = pack(temp, DefinitionPackCompatibility.LEGACY_FORMAT, List.of(DefinitionPackCompatibility.DECK_IDS), "{}");
        assertThrows(IOException.class, () -> DefinitionPackInstaller.install(temp.resolve("store"), invalid.path(), invalid.sha()));
    }
    @Test void reloadAndRollbackRecheckManifestCapabilities() throws Exception {
        Path root = temp.resolve("store");
        Pack valid = pack(temp, DefinitionPackCompatibility.CAPABILITY_FORMAT, List.of(DefinitionPackCompatibility.DECK_IDS), "{\"filter\":{\"deckIds\":[1]}}");
        DefinitionPackInstaller.install(root, valid.path(), valid.sha());
        var service = new GameDefinitionService(error -> fail(error)); var pinned = service.reload(root);
        Path manifest = root.resolve("generations").resolve(valid.sha()).resolve("manifest.json");
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(manifest.toFile());
        node.set("required_capabilities", JSON.valueToTree(List.of("future-feature")));
        JSON.writeValue(manifest.toFile(), node);
        byte[] pointer = Files.readAllBytes(root.resolve("active.json"));
        assertThrows(IOException.class, () -> service.reload(root));
        assertSame(pinned, service.snapshot());
        assertThrows(IOException.class, () -> DefinitionPackInstaller.restoreGeneration(root, valid.sha()));
        assertArrayEquals(pointer, Files.readAllBytes(root.resolve("active.json")));
    }
    @Test void ordinaryJsonKeysOutsideQuestFilterDoNotInventRequirements() throws Exception {
        Pack legacy = pack(temp, DefinitionPackCompatibility.LEGACY_FORMAT, null, "{\"description\":{\"deckIds\":[1]}}");
        DefinitionPackInstaller.install(temp.resolve("store"), legacy.path(), legacy.sha());
    }
    @Test void duplicateAndTrailingDefinitionDocumentsAreRejectedBeforeActivation() throws Exception {
        for (String body : List.of("{\"filter\":{\"deckIds\":[1]},\"filter\":{}}", "{} {}")) {
            Pack bad = pack(temp, DefinitionPackCompatibility.LEGACY_FORMAT, null, body);
            Path root = temp.resolve(UUID.randomUUID().toString());
            assertThrows(IOException.class, () -> DefinitionPackInstaller.install(root, bad.path(), bad.sha()));
            assertFalse(Files.exists(root.resolve("active.json")));
        }
    }
}
