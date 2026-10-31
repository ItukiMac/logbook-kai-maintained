package logbook.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

class DefinitionPackInstallerTest {
    @TempDir
    Path temp;

    @AfterEach
    void clear() {
        System.clearProperty(ExternalQuestDefinitions.ROOT_PROPERTY);
    }

    private String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record Pack(Path zip, String sha256) {
    }

    private Pack makePack(String name, String definition, boolean wrongFileHash,
            boolean extraUnsafeEntry, boolean omitData) throws Exception {
        byte[] body = definition.getBytes(StandardCharsets.UTF_8);
        String checksum = wrongFileHash ? "0".repeat(64) : hash(body);
        String manifest = """
                {"format":"logbook-data-pack-v1","source_revision":"fixture",
                 "files":[{"path":"%s","sha256":"%s","source_sha256":"%s","bytes":%d}]}
                """.formatted(name, checksum, hash(body), body.length);

        Path zip = Files.createTempFile(temp, "pack-", ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            put(out, "manifest.json", manifest.getBytes(StandardCharsets.UTF_8));
            if (!omitData)
                put(out, name, body);
            if (extraUnsafeEntry)
                put(out, "../attack.txt", "malicious".getBytes(StandardCharsets.UTF_8));
        }
        return new Pack(zip, hash(Files.readAllBytes(zip)));
    }

    private void put(ZipOutputStream out, String path, byte[] payload) throws Exception {
        out.putNextEntry(new ZipEntry(path));
        out.write(payload);
        out.closeEntry();
    }

    @Test
    void fullGenerationActivatesAndIsReadableWithoutJarReplacement() throws Exception {
        Path store = temp.resolve("store");
        Pack first = makePack("definitions/quest/1045.json", "{\"type\":\"before\"}", false, false, false);
        String id = DefinitionPackInstaller.install(store, first.zip, first.sha256);
        assertEquals(64, id.length());
        assertTrue(Files.exists(store.resolve("active.json")));
        assertTrue(Files.exists(store.resolve("generations").resolve(id)
                .resolve("definitions/quest/1045.json")));
        System.setProperty(ExternalQuestDefinitions.ROOT_PROPERTY, store.toString());
        assertEquals("{\"type\":\"before\"}", read(ExternalQuestDefinitions.resolve(1045, true)));
        assertNull(ExternalQuestDefinitions.resolve(1045, false));
        assertEquals(id, DefinitionPackInstaller.install(store, first.zip, first.sha256));
    }

    @Test
    void installFailureLeavesPreviousGenerationActive() throws Exception {
        Path store = temp.resolve("store");
        Pack first = makePack("definitions/quest/1045.json", "{\"type\":\"before\"}", false, false, false);
        String previous = DefinitionPackInstaller.install(store, first.zip, first.sha256);
        Pack broken = makePack("definitions/quest/1045.json", "{\"type\":\"after\"}", true, false, false);
        assertThrows(IOException.class, () -> DefinitionPackInstaller.install(store, broken.zip, broken.sha256));
        System.setProperty(ExternalQuestDefinitions.ROOT_PROPERTY, store.toString());
        assertEquals("{\"type\":\"before\"}", read(ExternalQuestDefinitions.resolve(1045, true)));
        assertEquals(previous,
                DefinitionPackInstaller.activeDefinitionsRoot(store).getParent().getFileName().toString());
    }

    @Test
    void rejectsMissingOrTamperedArchiveBeforeActivation() throws Exception {
        Path store = temp.resolve("store");
        Pack pack = makePack("definitions/quest/1045.json", "{}", false, false, false);
        assertThrows(IOException.class, () -> DefinitionPackInstaller.install(store, pack.zip, "0".repeat(64)));
        assertFalse(Files.exists(store.resolve("active.json")));
        Pack missing = makePack("definitions/quest/1045.json", "{}", false, false, true);
        assertThrows(IOException.class, () -> DefinitionPackInstaller.install(store, missing.zip, missing.sha256));
        assertFalse(Files.exists(store.resolve("active.json")));
    }

    @Test
    void rejectsUnlistedTraversalAndUnexpectedCategory() throws Exception {
        Path store = temp.resolve("store");
        Pack attack = makePack("definitions/quest/1045.json", "{}", false, true, false);
        assertThrows(IOException.class, () -> DefinitionPackInstaller.install(store, attack.zip, attack.sha256));
        Pack unknown = makePack("definitions/executable/1045.json", "{}", false, false, false);
        assertThrows(IOException.class, () -> DefinitionPackInstaller.install(store, unknown.zip, unknown.sha256));
        assertFalse(Files.exists(store.resolve("active.json")));
    }

    @Test
    void rejectsMalformedActivePointerWithoutReadingFallback() throws Exception {
        Path store = temp.resolve("store");
        Files.createDirectories(store.resolve("quest"));
        Files.writeString(store.resolve("quest/1045.json"), "{\"type\":\"old\"}");
        Files.writeString(store.resolve("active.json"), "{\"generation\":\"../escape\"}");
        System.setProperty(ExternalQuestDefinitions.ROOT_PROPERTY, store.toString());
        assertNull(ExternalQuestDefinitions.resolve(1045, true));
    }

    @Test
    void nextGenerationIsAtomicAndLeavesEarlierGenerationForRollback() throws Exception {
        Path store = temp.resolve("store");
        Pack one = makePack("definitions/eventquest/441.json", "{\"value\":1}", false, false, false);
        Pack two = makePack("definitions/eventquest/441.json", "{\"value\":2}", false, false, false);
        String id1 = DefinitionPackInstaller.install(store, one.zip, one.sha256);
        String id2 = DefinitionPackInstaller.install(store, two.zip, two.sha256);
        assertNotEquals(id1, id2);
        assertTrue(Files.isDirectory(store.resolve("generations").resolve(id1)));
        assertTrue(Files.isDirectory(store.resolve("generations").resolve(id2)));
        System.setProperty(ExternalQuestDefinitions.ROOT_PROPERTY, store.toString());
        assertEquals("{\"value\":2}", read(ExternalQuestDefinitions.resolve(441, false)));
    }

    private String read(java.net.URL url) throws Exception {
        assertNotNull(url);
        try (var in = url.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
