package logbook.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import logbook.features.CalculatorFeatures;
import logbook.features.ChartFeatures;
import logbook.features.StatusFeatures;
import logbook.plugin.api.FeatureModule;
import logbook.plugin.api.FeatureModule.Dependency;
import logbook.plugin.api.FeatureModule.Descriptor;
import logbook.plugin.api.FeatureModule.Version;
import org.junit.jupiter.api.Test;

class FeatureVersionContractTest {
    private static final String FXML = "logbook/gui/prob_calc.fxml";

    private FeatureRegistry registry(FeatureModule... modules) {
        return new FeatureRegistry(Stream.of(modules), getClass().getClassLoader());
    }

    private FeatureModule module(String id, String version, int api, List<Dependency> dependencies,
                                 AtomicBoolean accessed) {
        return new FeatureModule() {
            @Override public Descriptor descriptor() {
                return new Descriptor(id, version, api, dependencies);
            }
            @Override public List<Feature> features() {
                accessed.set(true);
                return List.of(new Feature(id + ".window", id, FXML, Group.EXTRA, 0, ""));
            }
        };
    }

    private FeatureModule module(String id, String version, Dependency... dependencies) {
        return module(id, version, FeatureModule.HOST_API_VERSION, List.of(dependencies), new AtomicBoolean());
    }

    @Test void builtinsDeclareTheirOwnStableVersions() {
        var catalog = registry(new CalculatorFeatures(), new ChartFeatures(), new StatusFeatures());
        assertEquals(List.of("calculators", "charts", "status"),
                catalog.descriptors().stream().map(Descriptor::id).toList());
        assertTrue(catalog.descriptors().stream().allMatch(d ->
                d.version().equals("1.0.0") && d.hostApi() == 1 && d.dependencies().isEmpty()));
        assertThrows(UnsupportedOperationException.class, () -> catalog.descriptors().clear());
    }

    @Test void unversionedFunctionalProvidersRemainSupportedOutsideManagedMode() {
        FeatureModule legacy = () -> List.of(new FeatureModule.Feature("legacy", "legacy", FXML,
                FeatureModule.Group.EXTRA, 0, ""));
        assertNull(legacy.descriptor());
        assertEquals("legacy", registry(legacy).get("legacy").id());
        assertTrue(registry(legacy).descriptors().isEmpty());
    }

    @Test void unknownHostApiFailsBeforePublishingAnyFeatures() {
        var first = new AtomicBoolean();
        var second = new AtomicBoolean();
        assertThrows(IllegalArgumentException.class, () -> registry(
                module("valid", "1.0.0", 1, List.of(), first),
                module("future", "1.0.0", 2, List.of(), second)));
        assertFalse(first.get());
        assertFalse(second.get());
    }

    @Test void duplicateModuleIdsFailEvenWhenTheirFeatureIdsWouldDiffer() {
        var first = new AtomicBoolean();
        var second = new AtomicBoolean();
        var error = assertThrows(IllegalArgumentException.class, () -> registry(
                module("calculators", "1.0.0", 1, List.of(), first),
                module("calculators", "1.1.0", 1, List.of(), second)));
        assertTrue(error.getMessage().contains("Duplicate module"));
        assertFalse(first.get());
        assertFalse(second.get());
    }

    @Test void missingDependencyFailsBeforePublishingFeatures() {
        var accessed = new AtomicBoolean();
        var dependency = new Dependency("absent", "1.0.0", "2.0.0");
        var error = assertThrows(IllegalArgumentException.class,
                () -> registry(module("consumer", "1.0.0", 1, List.of(dependency), accessed)));
        assertTrue(error.getMessage().contains("Missing dependency"));
        assertFalse(accessed.get());
    }

    @Test void dependencyVersionBelowMinimumIsRejected() {
        var error = assertThrows(IllegalArgumentException.class, () -> registry(
                module("consumer", "1.0.0", new Dependency("base", "1.2.0", "2.0.0")),
                module("base", "1.1.9")));
        assertTrue(error.getMessage().contains("Incompatible dependency"));
    }

    @Test void dependencyUpperBoundIsExclusive() {
        assertThrows(IllegalArgumentException.class, () -> registry(
                module("consumer", "1.0.0", new Dependency("base", "1.2.0", "2.0.0")),
                module("base", "2.0.0")));
    }

    @Test void compatibleUpdateIsAcceptedAndReportsActualVersion() {
        for (String version : List.of("1.2.0", "1.2.1", "1.10.0", "1.999.999")) {
            var catalog = registry(
                    module("consumer", "1.0.0", new Dependency("base", "1.2.0", "2.0.0")),
                    module("base", version));
            assertEquals(version, catalog.descriptors().stream()
                    .filter(d -> d.id().equals("base")).findFirst().orElseThrow().version());
            assertEquals("consumer.window", catalog.get("consumer.window").id());
        }
    }

    @Test void circularDependenciesAreRejectedWithoutExecutingFeatures() {
        var accessed = new AtomicBoolean();
        var error = assertThrows(IllegalArgumentException.class, () -> registry(
                module("one", "1.0.0", 1,
                        List.of(new Dependency("two", "1.0.0", "2.0.0")), accessed),
                module("two", "1.0.0", 1,
                        List.of(new Dependency("three", "1.0.0", "2.0.0")), accessed),
                module("three", "1.0.0", 1,
                        List.of(new Dependency("one", "1.0.0", "2.0.0")), accessed)));
        assertTrue(error.getMessage().contains("Circular dependency"));
        assertFalse(accessed.get());
    }

    @Test void directSelfDependencyIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> registry(
                module("self", "1.0.0", new Dependency("self", "1.0.0", "2.0.0"))));
    }

    @Test void dependencyGraphMayHaveSharedAncestors() {
        var catalog = registry(
                module("one", "1.0.0", new Dependency("base", "1.0.0", "2.0.0")),
                module("two", "1.0.0", new Dependency("base", "1.0.0", "2.0.0")),
                module("base", "1.0.0"));
        assertEquals(3, catalog.descriptors().size());
    }

    @Test void descriptorAndDependencyMetadataAreImmutableSnapshots() {
        var dependencies = new ArrayList<Dependency>();
        dependencies.add(new Dependency("base", "1.0.0", "2.0.0"));
        var descriptor = new Descriptor("consumer", "1.0.0", 1, dependencies);
        dependencies.clear();
        assertEquals(1, descriptor.dependencies().size());
        assertThrows(UnsupportedOperationException.class, () -> descriptor.dependencies().clear());
    }

    @Test void duplicateDependenciesAndMalformedRangesAreRejected() {
        var dependency = new Dependency("base", "1.0.0", "2.0.0");
        assertThrows(IllegalArgumentException.class,
                () -> new Descriptor("consumer", "1.0.0", 1, List.of(dependency, dependency)));
        assertThrows(IllegalArgumentException.class,
                () -> new Dependency("base", "1.0.0", "1.0.0"));
        assertThrows(IllegalArgumentException.class,
                () -> new Dependency("base", "2.0.0", "1.0.0"));
    }

    @Test void versionsUseNumericOrderingAndRejectAmbiguousForms() {
        assertTrue(Version.parse("1.10.0").compareTo(Version.parse("1.9.99")) > 0);
        assertTrue(Version.parse("2.0.0").compareTo(Version.parse("1.999.999")) > 0);
        assertEquals(0, Version.parse("2147483647.0.0").compareTo(Version.parse("2147483647.0.0")));
        for (String invalid : List.of("", "1", "1.0", "01.0.0", "1.00.0", "1.0.01", "-1.0.0",
                "+1.0.0", "1.0.0-rc1", "1.0.0+build", " 1.0.0", "1.0.0\n", "2147483648.0.0")) {
            assertThrows(IllegalArgumentException.class, () -> Version.parse(invalid), invalid);
        }
        assertThrows(IllegalArgumentException.class, () -> Version.parse(null));
        assertThrows(IllegalArgumentException.class, () -> new Version(-1, 0, 0));
    }

    @Test void missingAndInvalidMetadataAreRejected() {
        for (String invalid : List.of("", "A", "a/b", "../a", "a b")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new Descriptor(invalid, "1.0.0", 1, List.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> new Dependency(invalid, "1.0.0", "2.0.0"));
        }
        assertThrows(IllegalArgumentException.class, () -> new Descriptor(null, "1.0.0", 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Descriptor("valid", null, 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Descriptor("valid", "1.0.0", 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Descriptor("valid", "1.0.0", 1, null));
    }

    @Test void nullModuleAndFeatureListsFailWithActionableValidationErrors() {
        assertThrows(IllegalArgumentException.class, () -> registry((FeatureModule) null));
        assertThrows(IllegalArgumentException.class, () -> registry((FeatureModule) () -> null));
        assertThrows(IllegalArgumentException.class, () -> registry(
                (FeatureModule) () -> java.util.Arrays.asList((FeatureModule.Feature) null)));
    }
}
