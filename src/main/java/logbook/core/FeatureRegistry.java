package logbook.core;

import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import logbook.plugin.api.FeatureModule;
import logbook.plugin.api.FeatureModule.Descriptor;

/**
 * Immutable module catalog. Module identities and dependencies are validated as
 * a complete set before invoking features(). Managed discovery also binds each
 * embedded descriptor to its runtime provider before publishing any menu entries.
 */
public final class FeatureRegistry {
    private final Map<String, FeatureModule.Feature> features;
    private final List<Descriptor> descriptors;

    public FeatureRegistry(Stream<FeatureModule> modules, ClassLoader resources) {
        this(modules, resources, false);
    }

    FeatureRegistry(Stream<FeatureModule> modules, ClassLoader resources, boolean verifyEmbeddedDescriptors) {
        Objects.requireNonNull(modules, "modules");
        Objects.requireNonNull(resources, "resources");
        List<FeatureModule> candidates = modules.toList();
        boolean versionedRequired = verifyEmbeddedDescriptors || Boolean.getBoolean("logbook.runtime.managedBundle");
        if (versionedRequired && candidates.isEmpty()) {
            throw new IllegalArgumentException("Managed bundle has no feature modules");
        }
        Map<String, Descriptor> releases = new LinkedHashMap<>();
        var captured = new IdentityHashMap<FeatureModule, Descriptor>();
        for (FeatureModule module : candidates) {
            if (module == null) {
                throw new IllegalArgumentException("Missing feature module");
            }
            Descriptor descriptor = module.descriptor();
            captured.put(module, descriptor);
            if (descriptor == null) {
                if (versionedRequired) {
                    throw new IllegalArgumentException("Managed bundle requires versioned module: "
                            + module.getClass().getName());
                }
                continue; // The original functional FeatureModule SPI remains supported.
            }
            if (descriptor.hostApi() != FeatureModule.HOST_API_VERSION) {
                throw new IllegalArgumentException("Unknown host API " + descriptor.hostApi()
                        + " required by " + descriptor.id());
            }
            if (releases.putIfAbsent(descriptor.id(), descriptor) != null) {
                throw new IllegalArgumentException("Duplicate module " + descriptor.id());
            }
        }
        validateDependencies(releases);
        String hostFingerprint = verifyEmbeddedDescriptors ? hostFingerprint() : null;

        Map<String, FeatureModule.Feature> found = new LinkedHashMap<>();
        for (FeatureModule module : candidates) {
            List<FeatureModule.Feature> supplied = module.features();
            if (supplied == null) {
                throw new IllegalArgumentException("Missing feature list: " + module.getClass().getName());
            }
            if (supplied.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Missing feature entry: " + module.getClass().getName());
            }
            if (verifyEmbeddedDescriptors) {
                verifyEmbeddedDescriptor(module, captured.get(module), supplied, hostFingerprint);
            }
            for (FeatureModule.Feature feature : supplied) {
                if (found.putIfAbsent(feature.id(), feature) != null) {
                    throw new IllegalArgumentException("Duplicate feature " + feature.id());
                }
                if (resources.getResource(feature.fxml()) == null) {
                    throw new IllegalArgumentException("Missing feature resource " + feature.fxml());
                }
            }
        }
        features = Map.copyOf(found);
        descriptors = List.copyOf(releases.values());
    }

    private static void validateDependencies(Map<String, Descriptor> releases) {
        Map<String, Integer> remaining = new LinkedHashMap<>();
        Map<String, List<String>> consumers = new HashMap<>();
        for (Descriptor release : releases.values()) {
            remaining.put(release.id(), release.dependencies().size());
            for (FeatureModule.Dependency dependency : release.dependencies()) {
                Descriptor target = releases.get(dependency.id());
                if (target == null) {
                    throw new IllegalArgumentException("Missing dependency " + dependency.id()
                            + " required by " + release.id());
                }
                if (!dependency.accepts(target.version())) {
                    throw new IllegalArgumentException("Incompatible dependency " + dependency.id()
                            + " " + target.version() + " required by " + release.id());
                }
                consumers.computeIfAbsent(dependency.id(), key -> new ArrayList<>()).add(release.id());
            }
        }
        // Iterative cycle detection also handles large graphs without recursion.
        var ready = new ArrayDeque<String>();
        remaining.forEach((id, count) -> {
            if (count == 0)
                ready.add(id);
        });
        int visited = 0;
        while (!ready.isEmpty()) {
            String id = ready.remove();
            visited++;
            for (String consumer : consumers.getOrDefault(id, List.of())) {
                int count = remaining.compute(consumer, (key, value) -> value - 1);
                if (count == 0)
                    ready.add(consumer);
            }
        }
        if (visited != releases.size()) {
            throw new IllegalArgumentException("Circular dependency between feature modules");
        }
    }

    /** Reads each provider's own archive, never the first matching classpath resource. */
    private static void verifyEmbeddedDescriptor(FeatureModule module, Descriptor descriptor,
            List<FeatureModule.Feature> supplied, String hostFingerprint) {
        final String entryName = "META-INF/logbook/feature.json";
        final int maxMetadataBytes = 64 * 1024;
        String provider = module.getClass().getName();
        try {
            var codeSource = module.getClass().getProtectionDomain().getCodeSource();
            if (codeSource == null || !"file".equals(codeSource.getLocation().getProtocol())) {
                throw new IllegalArgumentException("Managed feature has no local JAR source: " + provider);
            }
            Path source = Path.of(codeSource.getLocation().toURI());
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Managed feature must come from its own JAR: " + provider);
            }
            byte[] raw;
            try (JarFile archive = new JarFile(source.toFile())) {
                if (archive.stream().filter(entry -> entryName.equals(entry.getName())).count() != 1) {
                    throw new IllegalArgumentException("Missing or duplicate embedded feature descriptor: " + provider);
                }
                var entry = archive.getJarEntry(entryName);
                if (entry.isDirectory() || entry.getSize() > maxMetadataBytes) {
                    throw new IllegalArgumentException("Oversized embedded feature descriptor: " + provider);
                }
                try (var stream = archive.getInputStream(entry)) {
                    raw = stream.readNBytes(maxMetadataBytes + 1);
                }
            }
            if (raw.length > maxMetadataBytes) {
                throw new IllegalArgumentException("Oversized embedded feature descriptor: " + provider);
            }
            // Preflight is intentionally usable without application implementation classes.
            ObjectMapper mapper = tools.jackson.databind.json.JsonMapper.builderWithJackson2Defaults()
                    .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
            JsonNode metadata = mapper.readTree(raw);
            requireFields(metadata,
                    Set.of("id", "version", "host_api", "host_sha256", "dependencies", "provider", "features"));
            if (!metadata.get("host_api").isInt() || !metadata.get("dependencies").isArray()
                    || !metadata.get("features").isArray()) {
                throw new IllegalArgumentException("Invalid embedded feature descriptor types: " + provider);
            }
            List<FeatureModule.Dependency> dependencies = new ArrayList<>();
            for (JsonNode dependency : metadata.get("dependencies")) {
                requireFields(dependency, Set.of("id", "min_version", "max_version_exclusive"));
                dependencies.add(new FeatureModule.Dependency(text(dependency, "id"),
                        text(dependency, "min_version"), text(dependency, "max_version_exclusive")));
            }
            Descriptor embedded = new Descriptor(text(metadata, "id"), text(metadata, "version"),
                    metadata.get("host_api").intValue(), dependencies);
            List<String> featureIds = new ArrayList<>();
            for (JsonNode id : metadata.get("features")) {
                if (!id.isTextual())
                    throw new IllegalArgumentException("Invalid embedded feature ID: " + provider);
                featureIds.add(id.textValue());
            }
            if (!hostFingerprint.equals(text(metadata, "host_sha256"))) {
                throw new IllegalArgumentException("Feature was built for a different host JAR: " + provider);
            }
            if (!descriptor.equals(embedded) || !provider.equals(text(metadata, "provider"))
                    || !supplied.stream().map(FeatureModule.Feature::id).toList().equals(featureIds)) {
                throw new IllegalArgumentException(
                        "Embedded feature descriptor differs from provider contract: " + provider);
            }
        } catch (IOException | URISyntaxException | tools.jackson.core.JacksonException error) {
            throw new IllegalArgumentException("Cannot verify embedded feature descriptor: " + provider, error);
        }
    }

    /** Version 1 still uses shared public DTOs, so only an exact tested host is accepted. */
    private static String hostFingerprint() {
        try {
            var codeSource = FeatureModule.class.getProtectionDomain().getCodeSource();
            if (codeSource == null || !"file".equals(codeSource.getLocation().getProtocol())) {
                throw new IllegalArgumentException("Managed host API has no local JAR source");
            }
            Path source = Path.of(codeSource.getLocation().toURI());
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Managed host API must come from the core JAR");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(source)) {
                byte[] block = new byte[64 * 1024];
                for (int length; (length = input.read(block)) != -1;)
                    digest.update(block, 0, length);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | URISyntaxException | NoSuchAlgorithmException error) {
            throw new IllegalArgumentException("Cannot verify managed core JAR identity", error);
        }
    }

    private static void requireFields(JsonNode value, Set<String> expected) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException("Expected feature metadata object");
        }
        Set<String> fields = new HashSet<>();
        value.propertyNames().iterator().forEachRemaining(fields::add);
        if (!fields.equals(expected)) {
            throw new IllegalArgumentException("Invalid feature metadata fields");
        }
    }

    private static String text(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("Expected feature metadata string: " + field);
        }
        return value.textValue();
    }

    public static FeatureRegistry discover() {
        return new FeatureRegistry(LogBookCoreServices.getServiceProviders(FeatureModule.class),
                LogBookCoreServices.getClassLoader(), Boolean.getBoolean("logbook.runtime.managedBundle"));
    }

    /** Validated, immutable release descriptors in provider discovery order. */
    public List<Descriptor> descriptors() {
        return descriptors;
    }

    public FeatureModule.Feature get(String id) {
        var feature = features.get(id);
        if (feature == null)
            throw new IllegalArgumentException("Unknown feature " + id);
        return feature;
    }

    public List<FeatureModule.Feature> group(FeatureModule.Group group) {
        return features.values().stream().filter(feature -> feature.group() == group)
                .sorted(Comparator.comparingInt(FeatureModule.Feature::order)
                        .thenComparing(FeatureModule.Feature::id))
                .toList();
    }
}
