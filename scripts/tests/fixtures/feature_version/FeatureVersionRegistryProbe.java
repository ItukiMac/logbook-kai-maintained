package logbook.fixture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import logbook.core.FeatureRegistry;
import logbook.core.LogBookCoreServices;

/** Separate JVM: an incompatible provider must be refused before its feature code runs. */
public final class FeatureVersionRegistryProbe {
    public static void main(String[] args) throws Exception {
        Path entered = Path.of("incompatible-features-entered.txt");
        if (Files.exists(entered)) throw new AssertionError("Probe requires a fresh directory");
        LogBookCoreServices.init(List.of());
        try {
            FeatureRegistry.discover();
            throw new AssertionError("API 2 provider was accepted by API 1 host");
        } catch (IllegalArgumentException expected) {
            if (Files.exists(entered)) throw new AssertionError("Feature code ran before refusal", expected);
            System.out.println("INCOMPATIBLE_REGISTRY_REJECTED_BEFORE_FEATURES " + expected.getMessage());
        }
    }
}
