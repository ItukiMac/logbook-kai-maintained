package logbook.internal.updatecheck;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Stages one validated application JAR without changing the active installation.
 * Activation at next launch and rollback are separate U2 responsibilities.
 */
final class UpdateArchiveStage {
    static final long MAX_BYTES = 256L * 1024 * 1024;
    static final String JAR_NAME = "logbook-kai.jar";

    private UpdateArchiveStage() {
    }

    static Path stage(Path archive, Path appDirectory) throws IOException {
        if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)
                || Files.size(archive) == 0 || Files.size(archive) > MAX_BYTES) {
            throw new IOException("Invalid update archive size");
        }

        Path stageDir = appDirectory.resolve("update");
        if (Files.isSymbolicLink(stageDir)) {
            throw new IOException("Refusing symlinked update directory");
        }
        Files.createDirectories(stageDir);
        if (!Files.isDirectory(stageDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Update destination is not a directory");
        }

        Path temporaryJar = Files.createTempFile(stageDir, ".logbook-kai-", ".part");
        try {
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                ZipEntry application = null;
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    // Validate every entry, even ones that we do not extract.
                    if (name.startsWith("/") || name.contains("\\")
                            || name.contains("../") || name.equals("..")
                            || name.contains("//")) {
                        throw new IOException("Unsafe ZIP path: " + name);
                    }
                    if (name.endsWith(".jar")) {
                        if (!JAR_NAME.equals(name) || application != null) {
                            throw new IOException("Unexpected or repeated application JAR: " + name);
                        }
                        application = entry;
                    }
                }
                if (application == null || application.isDirectory()
                        || application.getSize() > MAX_BYTES) {
                    throw new IOException("Missing or oversized application JAR");
                }

                try (InputStream in = zip.getInputStream(application);
                        var output = Files.newOutputStream(temporaryJar)) {
                    byte[] buffer = new byte[16384];
                    long total = 0;
                    int size;
                    while ((size = in.read(buffer)) != -1) {
                        total += size;
                        if (total > MAX_BYTES) {
                            throw new IOException("Application JAR exceeds extraction limit");
                        }
                        output.write(buffer, 0, size);
                    }
                }
            }

            // ZIP is not enough: verify the staged file is an application JAR.
            try (JarFile jar = new JarFile(temporaryJar.toFile())) {
                if (jar.getManifest() == null
                        || !"logbook.internal.Launcher".equals(
                                jar.getManifest().getMainAttributes().getValue("Main-Class"))
                        || jar.getJarEntry("logbook/internal/Launcher.class") == null) {
                    throw new IOException("Update JAR is not a logbook-kai application");
                }
            }

            Path destination = stageDir.resolve(JAR_NAME);
            if (Files.isSymbolicLink(destination)) {
                throw new IOException("Refusing symlinked staged JAR");
            }
            // Fail closed on filesystems without atomic replacement.
            Files.move(temporaryJar, destination,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return destination;
        } finally {
            Files.deleteIfExists(temporaryJar);
        }
    }
}
