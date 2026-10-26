package logbook.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import logbook.plugin.api.GameDefinitionSnapshot;

/** Read-only full-manifest validation, pinned to a single active-generation lookup. */
final class GameDefinitionLoader {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private GameDefinitionLoader() {}

    static GameDefinitionSnapshot load(Path input) throws IOException {
        Path root = input.toAbsolutePath().normalize();
        Path definitions = DefinitionPackInstaller.activeDefinitionsRoot(root);
        if (definitions.equals(root)) throw new IOException("A staged manifest generation is required");
        Path selected = definitions.getParent();
        String generation = selected.getFileName().toString();
        JsonNode manifest = JSON.readTree(readRegular(selected.resolve("manifest.json"), 2 * 1024 * 1024));
        Set<String> requiredCapabilities = DefinitionPackCompatibility.requireManifest(manifest);
        JsonNode entries = manifest.path("files");
        if (!entries.isArray() || entries.isEmpty() || entries.size() > GameDefinitionSnapshot.MAX_FILES) {
            throw new IOException("Invalid definition file list");
        }
        Set<String> seen = new HashSet<>();
        Map<String, byte[]> loaded = new LinkedHashMap<>();
        long total = 0;
        for (JsonNode entry : entries) {
            String name = entry.path("path").asText();
            String checksum = entry.path("sha256").asText();
            JsonNode size = entry.path("bytes");
            if (!name.startsWith("definitions/") || !seen.add(name)
                    || !checksum.matches("[0-9a-f]{64}") || !size.isIntegralNumber() || !size.canConvertToLong()) {
                throw new IOException("Invalid or duplicate manifest entry");
            }
            String resource = "logbook/" + name.substring("definitions/".length());
            try { GameDefinitionSnapshot.validateName(resource); }
            catch (IllegalArgumentException ex) { throw new IOException("Unsafe managed resource", ex); }
            long length = size.longValue();
            if (length < 0 || length > GameDefinitionSnapshot.MAX_BYTES
                    || total + length > GameDefinitionSnapshot.MAX_BYTES) throw new IOException("Definition size limit");
            total += length;
            Path file = selected.resolve(name).normalize();
            if (!file.startsWith(definitions)) throw new IOException("Definition path escaped generation");
            byte[] bytes = readRegular(file, (int) length);
            if (bytes.length != length || !checksum.equals(sha256(bytes))) {
                throw new IOException("Definition checksum or size mismatch: " + resource);
            }
            DefinitionPackCompatibility.requireDefinition(resource, JSON.readTree(bytes), requiredCapabilities);
            loaded.put(resource, bytes);
        }
        return new GameDefinitionSnapshot(generation, loaded);
    }
    private static byte[] readRegular(Path file, int limit) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || !file.toRealPath().equals(file.toAbsolutePath().normalize()) || Files.size(file) > limit) {
            throw new IOException("Missing, symlinked or oversized definition file: " + file);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("File grew beyond limit");
            return bytes;
        }
    }
    private static String sha256(byte[] bytes) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IOException(e); }
    }
}
