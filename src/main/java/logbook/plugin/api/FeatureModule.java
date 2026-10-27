package logbook.plugin.api;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Declarative window-feature SPI. The original {@link #features()} method and
 * {@link Feature} constructor remain binary compatible with legacy providers.
 * Legacy Main*Menu interfaces are independent of this SPI and remain supported.
 */
@FunctionalInterface
public interface FeatureModule {
    /** Current public feature host contract. Unknown contract numbers fail closed. */
    int HOST_API_VERSION = 1;

    List<Feature> features();

    /**
     * Module release metadata. Existing providers inherit {@code null}; they can
     * still run in a legacy single-JAR application, but cannot participate in a
     * managed independently versioned distribution until they declare a contract.
     * Adding a default method deliberately preserves the original functional SPI.
     */
    default Descriptor descriptor() {
        return null;
    }

    /**
     * Immutable release identity for one independently replaceable feature JAR.
     * Version numbers are strict numeric major.minor.patch values. A host API
     * number denotes an exact contract, not a minimum runtime version.
     */
    record Descriptor(String id, String version, int hostApi, List<Dependency> dependencies) {
        public Descriptor {
            requireId(id, "Module ID");
            Version.parse(version);
            if (hostApi < 1) {
                throw new IllegalArgumentException("Host API must be positive");
            }
            if (dependencies == null || dependencies.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Missing module dependencies");
            }
            dependencies = List.copyOf(dependencies);
            Set<String> ids = new HashSet<>();
            for (Dependency dependency : dependencies) {
                if (!ids.add(dependency.id())) {
                    throw new IllegalArgumentException("Duplicate dependency " + dependency.id());
                }
            }
        }
    }

    /** Dependency versions include the minimum and exclude the maximum. */
    record Dependency(String id, String minVersion, String maxVersionExclusive) {
        public Dependency {
            requireId(id, "Dependency ID");
            if (Version.parse(minVersion).compareTo(Version.parse(maxVersionExclusive)) >= 0) {
                throw new IllegalArgumentException("Dependency version range must be nonempty");
            }
        }

        public boolean accepts(String version) {
            Version actual = Version.parse(version);
            return Version.parse(minVersion).compareTo(actual) <= 0
                    && actual.compareTo(Version.parse(maxVersionExclusive)) < 0;
        }
    }

    /**
     * Shared numeric version parser. Prerelease/build labels and leading zeroes
     * are intentionally unsupported so Java and distribution validation agree.
     */
    record Version(int major, int minor, int patch) implements Comparable<Version> {
        public Version {
            if (major < 0 || minor < 0 || patch < 0) {
                throw new IllegalArgumentException("Negative version component");
            }
        }

        public static Version parse(String value) {
            if (value == null || !value.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")) {
                throw new IllegalArgumentException("Expected numeric major.minor.patch version: " + value);
            }
            String[] parts = value.split("\\.");
            try {
                return new Version(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]));
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Version component exceeds 2147483647: " + value, error);
            }
        }

        @Override
        public int compareTo(Version other) {
            int result = Integer.compare(major, other.major);
            if (result == 0) result = Integer.compare(minor, other.minor);
            if (result == 0) result = Integer.compare(patch, other.patch);
            return result;
        }

        @Override
        public String toString() {
            return major + "." + minor + "." + patch;
        }
    }

    enum Group { COMMAND, CALCULATOR, EXTRA }

    record Feature(String id, String title, String fxml, Group group, int order, String accelerator) {
        public Feature {
            Objects.requireNonNull(title);
            Objects.requireNonNull(group);
            requireId(id, "Feature ID");
            if (fxml == null || !fxml.startsWith("logbook/gui/") || !fxml.endsWith(".fxml")
                    || fxml.contains("..") || fxml.contains("\\")) {
                throw new IllegalArgumentException("Feature resource");
            }
        }
    }

    private static void requireId(String id, String label) {
        if (id == null || !id.matches("[a-z0-9][a-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(label);
        }
    }
}
