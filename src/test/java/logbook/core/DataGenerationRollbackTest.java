package logbook.core;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Validate restoration only after verifying every retained definition. */
class DataGenerationRollbackTest {
    @TempDir Path temporary;

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record Pack(Path path, String sha256) {}

    private Pack createPack(String value) throws Exception {
        byte[] json = ("{\"type\":\"" + value + "\"}").getBytes(StandardCharsets.UTF_8);
        String sha = digest(json);
        byte[] manifest = ("""
                {"format":"logbook-data-pack-v1","source_revision":"test",
                "files":[{"path":"definitions/quest/1045.json",
                  "sha256":"%s","source_sha256":"%s","bytes":%d}]}
                """.formatted(sha, sha, json.length)).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(bytes)) {
            archive.putNextEntry(new ZipEntry("manifest.json"));
            archive.write(manifest);
            archive.closeEntry();
            archive.putNextEntry(new ZipEntry("definitions/quest/1045.json"));
            archive.write(json);
            archive.closeEntry();
        }
        Path file = Files.createTempFile(temporary, "definitions-", ".zip");
        Files.write(file, bytes.toByteArray());
        return new Pack(file, digest(bytes.toByteArray()));
    }

    @Test
    void verifiedRollbackRestoresOldVersionWithoutDiscardingNewVersion() throws Exception {
        Path store = temporary.resolve("store");
        Pack one = createPack("before");
        Pack two = createPack("after");
        String before = DefinitionPackInstaller.install(store, one.path, one.sha256);
        String after = DefinitionPackInstaller.install(store, two.path, two.sha256);
        assertNotEquals(before, after);
        assertEquals("after", activeType(store));

        assertEquals(before, DefinitionPackInstaller.restoreGeneration(store, before));
        assertEquals("before", activeType(store));
        assertTrue(Files.isDirectory(store.resolve("generations").resolve(after)));
        assertEquals(after, DefinitionPackInstaller.restoreGeneration(store, after));
        assertEquals("after", activeType(store));
    }

    @Test
    void tamperedOldDefinitionDoesNotChangeActivePointer() throws Exception {
        Path store = temporary.resolve("store");
        Pack one = createPack("before");
        Pack two = createPack("after");
        String before = DefinitionPackInstaller.install(store, one.path, one.sha256);
        String after = DefinitionPackInstaller.install(store, two.path, two.sha256);
        Files.writeString(store.resolve("generations").resolve(before)
                .resolve("definitions/quest/1045.json"), "{\"type\":\"TAMPERED\"}");

        assertThrows(IOException.class, () ->
                DefinitionPackInstaller.restoreGeneration(store, before));
        assertEquals("after", activeType(store));
        assertEquals(after,
                DefinitionPackInstaller.activeDefinitionsRoot(store).getParent().getFileName().toString());
    }

    @Test
    void symlinkedRetainedJsonIsRejected() throws Exception {
        Path store = temporary.resolve("store");
        Pack one = createPack("before");
        Pack two = createPack("after");
        String before = DefinitionPackInstaller.install(store, one.path, one.sha256);
        DefinitionPackInstaller.install(store, two.path, two.sha256);
        Path old = store.resolve("generations").resolve(before).resolve("definitions/quest/1045.json");
        Path outside = temporary.resolve("outside.json");
        Files.writeString(outside, "{\"type\":\"before\"}");
        Files.delete(old);
        Files.createSymbolicLink(old, outside);

        assertThrows(IOException.class, () ->
                DefinitionPackInstaller.restoreGeneration(store, before));
        assertEquals("after", activeType(store));
    }

    @Test
    void unknownGenerationCannotChangeActive() throws Exception {
        Path store = temporary.resolve("store");
        Pack one = createPack("after");
        DefinitionPackInstaller.install(store, one.path, one.sha256);
        assertThrows(IOException.class, () ->
                DefinitionPackInstaller.restoreGeneration(store, "f".repeat(64)));
        assertThrows(IOException.class, () ->
                DefinitionPackInstaller.restoreGeneration(store, "../outside"));
        assertEquals("after", activeType(store));
    }

    private String activeType(Path store) throws Exception {
        Path root = DefinitionPackInstaller.activeDefinitionsRoot(store);
        return logbook.internal.json.JsonMappers.builder().build().readTree(
                Files.readString(root.resolve("quest/1045.json"))).path("type").asText();
    }
}
