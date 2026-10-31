package logbook.internal.updatecheck;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateArchiveStageTest {
    @TempDir
    Path temporaryDirectory;

    private Path applicationJar() throws Exception {
        Path jar = temporaryDirectory.resolve("source.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "logbook.internal.Launcher");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            output.putNextEntry(new JarEntry("logbook/internal/Launcher.class"));
            output.write(new byte[] { 1, 2, 3 });
            output.closeEntry();
        }
        return jar;
    }

    private Path archive(String entryName, byte[] bytes) throws Exception {
        Path zip = temporaryDirectory.resolve("release.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            output.putNextEntry(new ZipEntry(entryName));
            output.write(bytes);
            output.closeEntry();
        }
        return zip;
    }

    @Test
    void stagesValidApplicationJarWithoutTouchingActiveJar() throws Exception {
        Path jar = applicationJar();
        byte[] original = Files.readAllBytes(jar);
        Path active = temporaryDirectory.resolve("logbook-kai2.jar");
        Files.writeString(active, "CURRENT_VERSION");

        Path staged = UpdateArchiveStage.stage(
                archive("logbook-kai2.jar", original), temporaryDirectory);
        assertEquals(temporaryDirectory.resolve("update/logbook-kai2.jar"), staged);
        assertArrayEquals(original, Files.readAllBytes(staged));
        assertEquals("CURRENT_VERSION", Files.readString(active));
    }

    @Test
    void rejectsTraversalAndPreservesExistingStagedJar() throws Exception {
        Path current = temporaryDirectory.resolve("update/logbook-kai2.jar");
        Files.createDirectories(current.getParent());
        Files.writeString(current, "PREVIOUS_STAGING");

        assertThrows(IOException.class, () -> UpdateArchiveStage.stage(
                archive("../logbook-kai2.jar", Files.readAllBytes(applicationJar())),
                temporaryDirectory));
        assertEquals("PREVIOUS_STAGING", Files.readString(current));
    }

    @Test
    void rejectsNonApplicationJar() throws Exception {
        assertThrows(IOException.class, () -> UpdateArchiveStage.stage(
                archive("logbook-kai2.jar", new byte[] { 1, 2, 3, 4 }),
                temporaryDirectory));
        assertTrue(Files.notExists(temporaryDirectory.resolve("update/logbook-kai2.jar")));
    }

    @Test
    void rejectsMissingJarEntry() throws Exception {
        assertThrows(IOException.class, () -> UpdateArchiveStage.stage(
                archive("README.txt", new byte[] { 1, 2, 3 }),
                temporaryDirectory));
    }
}
