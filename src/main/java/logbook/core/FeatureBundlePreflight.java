package logbook.core;

import java.util.ServiceLoader;
import logbook.plugin.api.FeatureModule;

/**
 * Read-only, separate-JVM validation for the manager's fixed bundle classpath.
 * This entry point deliberately does not initialize LogBookCoreServices, Config,
 * the application, or the runtime plugin directory. A rejected contract escapes
 * main and exits nonzero before the manager can start the normal Launcher.
 */
public final class FeatureBundlePreflight {
    public static final String SUCCESS_MARKER = "FEATURE_BUNDLE_PREFLIGHT_OK";

    private FeatureBundlePreflight() {
    }

    public static void main(String[] args) {
        if (args.length != 0) {
            throw new IllegalArgumentException("Feature preflight takes no arguments");
        }
        ClassLoader loader = FeatureBundlePreflight.class.getClassLoader();
        ServiceLoader<FeatureModule> providers = ServiceLoader.load(FeatureModule.class, loader);
        new FeatureRegistry(providers.stream().map(ServiceLoader.Provider::get), loader, true);
        System.out.println(SUCCESS_MARKER);
    }
}
