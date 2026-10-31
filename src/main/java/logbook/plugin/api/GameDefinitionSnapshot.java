package logbook.plugin.api;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Immutable, generation-pinned game definitions. No user settings or mutable game beans. */
public final class GameDefinitionSnapshot {
    private static final Set<String> CATEGORIES = Set.of("quest", "eventquest", "mission", "map", "kousyou",
            "supplemental");
    public static final int MAX_FILES = 2000;
    public static final long MAX_BYTES = 128L * 1024 * 1024;
    private final String generation;
    private final Map<String, byte[]> files;
    private final String fingerprint;

    public GameDefinitionSnapshot(String generation, Map<String, byte[]> input) {
        if (generation == null || !generation.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("Invalid generation identifier");
        }
        Objects.requireNonNull(input);
        if (input.size() > MAX_FILES)
            throw new IllegalArgumentException("Too many definitions");
        long total = 0;
        for (var entry : input.entrySet()) {
            validateName(entry.getKey());
            total += Objects.requireNonNull(entry.getValue()).length;
            if (total > MAX_BYTES)
                throw new IllegalArgumentException("Definitions exceed memory limit");
        }
        Map<String, byte[]> copied = new LinkedHashMap<>();
        new TreeMap<>(input).forEach((name, bytes) -> copied.put(name, bytes.clone()));
        this.files = Collections.unmodifiableMap(copied);
        this.generation = generation;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            copied.forEach((name, bytes) -> {
                byte[] key = name.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(key.length).array());
                digest.update(key);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            });
            this.fingerprint = HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void validateName(String name) {
        if (name == null || name.contains("\\") || name.contains(":") || name.contains("..")
                || !name.endsWith(".json")) {
            throw new IllegalArgumentException("Invalid managed resource name");
        }
        String[] parts = name.split("/", -1);
        if (parts.length < 3 || !parts[0].equals("logbook") || !CATEGORIES.contains(parts[1])) {
            throw new IllegalArgumentException("Resource is not a managed game definition");
        }
        for (String part : parts) {
            if (part.isEmpty() || part.equals("."))
                throw new IllegalArgumentException("Invalid resource path");
        }
    }

    public String generation() {
        return generation;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public Set<String> resourceNames() {
        return files.keySet();
    }

    public Optional<InputStream> openResource(String name) {
        validateName(name);
        byte[] bytes = files.get(name);
        return bytes == null ? Optional.empty() : Optional.of(new ByteArrayInputStream(bytes));
    }
}
