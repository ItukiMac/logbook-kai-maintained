package logbook.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Stages a hash-verified game-definition ZIP as one immutable generation,
 * then atomically switches an active.json pointer. Never changes the JAR,
 * user settings or customquest/ content.
 *
 * Network/download/release authentication are out of scope; the expected
 * archive SHA-256 MUST be independently obtained from the trusted publisher.
 */
final class DefinitionPackInstaller {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_ARCHIVE_BYTES = 128L * 1024 * 1024;
    private static final long MAX_UNCOMPRESSED_BYTES = 128L * 1024 * 1024;
    private static final int MAX_FILES = 2000;
    private static final Set<String> CATEGORIES = Set.of(
            "quest", "eventquest", "kousyou", "mission", "map", "supplemental");
    static final String FORMAT = "logbook-data-pack-v1";
    static final String POINTER_FORMAT = "logbook-active-data-v1";

    private DefinitionPackInstaller() {
    }

    static String install(Path rootInput, Path zip, String expectedSha256) throws IOException {
        if (expectedSha256 == null || !expectedSha256.matches("[0-9a-fA-F]{64}")) {
            throw new IOException("Missing authenticated SHA-256 expectation");
        }
        if (!Files.isRegularFile(zip, LinkOption.NOFOLLOW_LINKS)
                || Files.size(zip) == 0 || Files.size(zip) > MAX_ARCHIVE_BYTES) {
            throw new IOException("Invalid definition ZIP size");
        }
        String generation = sha256(zip);
        if (!generation.equalsIgnoreCase(expectedSha256)) {
            throw new IOException("Definition ZIP SHA-256 mismatch");
        }

        Path root = rootInput.toAbsolutePath().normalize();
        Files.createDirectories(root);
        requireRealDirectory(root);
        Path releases = root.resolve("generations");
        Files.createDirectories(releases);
        requireRealDirectory(releases);
        Path destination = releases.resolve(generation);
        Path temporary = Files.createTempDirectory(releases, ".install-");
        byte[] manifestBytes;
        Map<String, ExpectedFile> expected;
        try {
            try (ZipFile archive = new ZipFile(zip.toFile())) {
                ZipEntry manifestEntry = archive.getEntry("manifest.json");
                if (manifestEntry == null || manifestEntry.isDirectory()
                        || manifestEntry.getSize() > 2 * 1024 * 1024) {
                    throw new IOException("Missing or oversized data manifest");
                }
                manifestBytes = readBounded(archive.getInputStream(manifestEntry), 2 * 1024 * 1024);
                expected = parseManifest(manifestBytes);

                Set<String> seen = new HashSet<>();
                long[] total = {0};
                var entries = archive.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!safeZipName(name) || entry.isDirectory()) {
                        throw new IOException("Unsafe definition ZIP entry: " + name);
                    }
                    if (!seen.add(name)) {
                        throw new IOException("Duplicate definition ZIP entry: " + name);
                    }
                    if ("manifest.json".equals(name)) {
                        continue;
                    }
                    ExpectedFile file = expected.get(name);
                    if (file == null || entry.getSize() > MAX_UNCOMPRESSED_BYTES) {
                        throw new IOException("Unlisted or oversized definition file: " + name);
                    }
                    Path target = temporary.resolve(name).normalize();
                    if (!target.startsWith(temporary)) {
                        throw new IOException("Data ZIP path escapes staging root: " + name);
                    }
                    Files.createDirectories(target.getParent());
                    try (InputStream in = archive.getInputStream(entry);
                         var out = Files.newOutputStream(target)) {
                        MessageDigest digest = newDigest();
                        long count = 0;
                        byte[] buffer = new byte[16384];
                        int size;
                        while ((size = in.read(buffer)) != -1) {
                            count += size;
                            total[0] += size;
                            if (count > MAX_UNCOMPRESSED_BYTES || total[0] > MAX_UNCOMPRESSED_BYTES) {
                                throw new IOException("Definition ZIP exceeds expanded data limit");
                            }
                            digest.update(buffer, 0, size);
                            out.write(buffer, 0, size);
                        }
                        if (count != file.bytes || !file.sha256.equals(HexFormat.of().formatHex(digest.digest()))) {
                            throw new IOException("Invalid definition file hash or size: " + name);
                        }
                    }
                }
                if (seen.size() != expected.size() + 1) {
                    throw new IOException("Definition ZIP contains missing entries");
                }
            }
            Files.write(temporary.resolve("manifest.json"), manifestBytes);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                // Safe repeat-install: only activate an already staged generation
                // if its contents match the verified archive.
                requireRealDirectory(destination);
                for (Map.Entry<String, ExpectedFile> item : expected.entrySet()) {
                    Path file = destination.resolve(item.getKey());
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                            || Files.size(file) != item.getValue().bytes
                            || !sha256(file).equals(item.getValue().sha256)) {
                        throw new IOException("Existing generation is not intact: " + item.getKey());
                    }
                }
                if (!java.util.Arrays.equals(Files.readAllBytes(destination.resolve("manifest.json")), manifestBytes)) {
                    throw new IOException("Existing generation manifest differs");
                }
            } else {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            }

            Path pointer = root.resolve("active.json");
            if (Files.isSymbolicLink(pointer)) {
                throw new IOException("Refusing symlinked active-generation pointer");
            }
            Path pointerTemp = Files.createTempFile(root, ".active-", ".tmp");
            try {
                Files.writeString(pointerTemp,
                        "{\"schema\":\"" + POINTER_FORMAT + "\",\"generation\":\"" + generation + "\"}\n");
                Files.move(pointerTemp, pointer, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(pointerTemp);
            }
            return generation;
        } finally {
            removeTemporaryTree(temporary);
        }
    }

    static Path activeDefinitionsRoot(Path rootInput) throws IOException {
        Path root = rootInput.toAbsolutePath().normalize();
        requireRealDirectory(root);
        Path pointer = root.resolve("active.json");
        if (!Files.exists(pointer, LinkOption.NOFOLLOW_LINKS)) {
            return root; // legacy manual -Dlogbook.data.root=/path/with/quest
        }
        if (!Files.isRegularFile(pointer, LinkOption.NOFOLLOW_LINKS)
                || Files.size(pointer) > 4096) {
            throw new IOException("Invalid generation pointer");
        }
        JsonNode data;
        try (InputStream in = Files.newInputStream(pointer)) {
            data = JSON.readTree(in);
        }
        if (data == null || !POINTER_FORMAT.equals(data.path("schema").asText())
                || !data.path("generation").asText().matches("[0-9a-f]{64}")) {
            throw new IOException("Malformed generation pointer");
        }
        Path releases = root.resolve("generations");
        Path generation = releases.resolve(data.path("generation").asText());
        Path definitions = generation.resolve("definitions");
        requireRealDirectory(releases);
        requireRealDirectory(generation);
        requireRealDirectory(definitions);
        return definitions;
    }

    private static Map<String, ExpectedFile> parseManifest(byte[] manifest) throws IOException {
        JsonNode document = JSON.readTree(manifest);
        if (document == null || !FORMAT.equals(document.path("format").asText())) {
            throw new IOException("Unsupported data manifest format");
        }
        JsonNode files = document.path("files");
        if (!files.isArray() || files.isEmpty() || files.size() > MAX_FILES) {
            throw new IOException("Invalid definition file list");
        }
        Map<String, ExpectedFile> result = new HashMap<>();
        for (JsonNode file : files) {
            String path = file.path("path").asText();
            String checksum = file.path("sha256").asText();
            long bytes = file.path("bytes").asLong(-1);
            if (!validDefinitionPath(path) || !checksum.matches("[0-9a-f]{64}")
                    || bytes < 0 || bytes > MAX_UNCOMPRESSED_BYTES) {
                throw new IOException("Invalid manifest definition entry: " + path);
            }
            if (result.putIfAbsent(path, new ExpectedFile(checksum, bytes)) != null) {
                throw new IOException("Duplicate manifest definition entry: " + path);
            }
        }
        return result;
    }

    private static boolean validDefinitionPath(String name) {
        if (!safeZipName(name) || !name.endsWith(".json")) return false;
        String[] elements = name.split("/");
        if (elements.length < 3 || !"definitions".equals(elements[0])
                || !CATEGORIES.contains(elements[1])) return false;
        for (String element : elements) {
            if (element.isEmpty() || element.equals(".") || element.equals("..")) return false;
        }
        return true;
    }

    private static boolean safeZipName(String name) {
        return name != null && !name.startsWith("/") && !name.contains("\\")
                && !name.contains(":") && !name.contains("//")
                && !name.contains("../") && !name.equals("..")
                && !name.contains("/./");
    }

    private static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        try (InputStream in = input; var out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > maxBytes) throw new IOException("Manifest exceeds limit");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    private static void requireRealDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !directory.toRealPath().equals(directory.toAbsolutePath().normalize())) {
            throw new IOException("Data directory is missing or symlinked: " + directory);
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newDigest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    private static void removeTemporaryTree(Path temporary) throws IOException {
        if (!Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(temporary)) {
            for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private record ExpectedFile(String sha256, long bytes) {}
}
