package logbook.core;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import logbook.plugin.api.FeatureModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FeatureEmbeddedDescriptorTest {
    private static final String PROVIDER = "fixture.VersionedModule";
    private static final String ENTRY = "META-INF/logbook/feature.json";
    private static final String METADATA = """
            {"id":"fixture","version":"1.0.0","host_api":1,"host_sha256":"%HOST_SHA%","dependencies":[],
             "provider":"fixture.VersionedModule","features":["fixture.window"]}
            """;
    @TempDir
    Path directory;
    private byte[] providerClass;
    private Path hostJar;
    private String hostSha;
    private int sequence;

    @BeforeEach
    void compileProvider() throws Exception {
        Path source = directory.resolve("src/fixture/VersionedModule.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source,
                """
                        package fixture;
                        import logbook.plugin.api.FeatureModule;
                        import java.util.List;
                        public final class VersionedModule implements FeatureModule {
                            public Descriptor descriptor() { return new Descriptor("fixture", "1.0.0", 1, List.of()); }
                            public List<Feature> features() {
                                return List.of(new Feature("fixture.window", "Fixture", "logbook/gui/prob_calc.fxml", Group.EXTRA, 0, ""));
                            }
                        }
                        """);
        Path classes = directory.resolve("classes");
        Files.createDirectories(classes);
        Path api = Path.of(FeatureModule.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertNotNull(ToolProvider.getSystemJavaCompiler(), "The regression requires a JDK, not a JRE");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-proc:none", "-classpath", api.toString(), "-d", classes.toString(), source.toString()));
        providerClass = Files.readAllBytes(classes.resolve("fixture/VersionedModule.class"));
        hostJar = directory.resolve("host-api.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(hostJar))) {
            for (String name : List.of("logbook/core/FeatureRegistry.class",
                    "logbook/core/FeatureBundlePreflight.class",
                    "logbook/gui/prob_calc.fxml", "logbook/plugin/api/FeatureModule.class",
                    "logbook/plugin/api/FeatureModule$Feature.class", "logbook/plugin/api/FeatureModule$Group.class",
                    "logbook/plugin/api/FeatureModule$Version.class",
                    "logbook/plugin/api/FeatureModule$Descriptor.class",
                    "logbook/plugin/api/FeatureModule$Dependency.class")) {
                output.putNextEntry(new JarEntry(name));
                try (var input = getClass().getClassLoader().getResourceAsStream(name)) {
                    assertNotNull(input, name);
                    output.write(input.readAllBytes());
                }
                output.closeEntry();
            }
        }
        hostSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(hostJar)));
    }

    private Path jar(String metadata, boolean includeProvider) throws Exception {
        Path path = directory.resolve("fixture-" + sequence++ + ".jar");
        try (var output = new JarOutputStream(Files.newOutputStream(path))) {
            if (includeProvider) {
                output.putNextEntry(new JarEntry("fixture/VersionedModule.class"));
                output.write(providerClass);
                output.closeEntry();
                output.putNextEntry(new JarEntry("META-INF/services/logbook.plugin.api.FeatureModule"));
                output.write((PROVIDER + "\n").getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
            if (metadata != null) {
                output.putNextEntry(new JarEntry(ENTRY));
                output.write(metadata.replace("%HOST_SHA%", hostSha).getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return path;
    }

    private record Catalog(String version, String featureId) {
    }

    private Catalog registry(Path... paths) throws Exception {
        URL[] urls = new URL[paths.length + 1];
        urls[0] = hostJar.toUri().toURL();
        for (int i = 0; i < paths.length; i++)
            urls[i + 1] = paths[i].toUri().toURL();
        // Only the API and registry use child-first lookup. They are the actual
        // compiled production classes, loaded from an actual synthetic host JAR.
        try (var loader = new URLClassLoader(urls, FeatureModule.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("logbook.core.FeatureRegistry") || name.equals("logbook.plugin.api.FeatureModule")
                        || name.startsWith("logbook.plugin.api.FeatureModule$")) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> result = findLoadedClass(name);
                        if (result == null)
                            result = findClass(name);
                        if (resolve)
                            resolveClass(result);
                        return result;
                    }
                }
                return super.loadClass(name, resolve);
            }
        }) {
            Object module = loader.loadClass(PROVIDER).getConstructor().newInstance();
            Class<?> registryType = loader.loadClass("logbook.core.FeatureRegistry");
            var constructor = registryType.getDeclaredConstructor(Stream.class, ClassLoader.class, boolean.class);
            constructor.setAccessible(true);
            Object registry;
            try {
                registry = constructor.newInstance(Stream.of(module), loader, true);
            } catch (InvocationTargetException error) {
                if (error.getCause() instanceof RuntimeException cause)
                    throw cause;
                throw error;
            }
            var descriptors = (List<?>) registryType.getMethod("descriptors").invoke(registry);
            Object descriptor = descriptors.getFirst();
            String version = (String) descriptor.getClass().getMethod("version").invoke(descriptor);
            Object feature = registryType.getMethod("get", String.class).invoke(registry, "fixture.window");
            String id = (String) feature.getClass().getMethod("id").invoke(feature);
            return new Catalog(version, id);
        }
    }

    @Test
    void acceptsMetadataFromTheProvidersOwnArchive() throws Exception {
        Path decoy = jar(METADATA.replace("1.0.0", "99.0.0"), false);
        var catalog = registry(decoy, jar(METADATA, true));
        assertEquals("1.0.0", catalog.version());
        assertEquals("fixture.window", catalog.featureId());
    }

    @Test
    void missingEmbeddedMetadataIsRejected() throws Exception {
        Path path = jar(null, true);
        assertThrows(IllegalArgumentException.class, () -> registry(path));
    }

    @Test
    void mismatchedVersionsProviderAndFeatureIdsAreRejected() throws Exception {
        for (String invalid : List.of(
                METADATA.replace("1.0.0", "1.1.0"),
                METADATA.replace("fixture.VersionedModule", "fixture.OtherModule"),
                METADATA.replace("fixture.window", "fixture.different"),
                METADATA.replace("\"host_api\":1", "\"host_api\":2"),
                METADATA.replace("\"id\":\"fixture\"", "\"id\":\"other\""))) {
            Path path = jar(invalid, true);
            assertThrows(IllegalArgumentException.class, () -> registry(path));
        }
    }

    @Test
    void duplicateKeysExtraFieldsTrailingJsonAndNonintegerApiAreRejected() throws Exception {
        for (String invalid : List.of(
                METADATA.replace("\"host_api\":1", "\"host_api\":1,\"host_api\":1"),
                METADATA.replace("\"host_api\":1", "\"host_api\":1.0"),
                METADATA.replace("\"host_api\":1", "\"host_api\":true"),
                METADATA.replace("\"host_api\":1", "\"host_api\":1,\"unexpected\":true"),
                METADATA + "{}")) {
            Path path = jar(invalid, true);
            assertThrows(IllegalArgumentException.class, () -> registry(path));
        }
    }

    @Test
    void aFeatureBuiltForAnotherHostIsRejected() throws Exception {
        Path path = jar(METADATA.replace("%HOST_SHA%", "0".repeat(64)), true);
        var error = assertThrows(IllegalArgumentException.class, () -> registry(path));
        assertTrue(error.getMessage().contains("different host JAR"));
    }

    @Test
    void unexpectedEmbeddedDependenciesAreRejected() throws Exception {
        String invalid = METADATA.replace("\"dependencies\":[]", """
                "dependencies":[{"id":"base","min_version":"1.0.0","max_version_exclusive":"2.0.0"}]
                """);
        Path path = jar(invalid, true);
        assertThrows(IllegalArgumentException.class, () -> registry(path));
    }

    @Test
    void oversizedEmbeddedMetadataIsRejected() throws Exception {
        Path path = jar(METADATA + " ".repeat(65536), true);
        assertThrows(IllegalArgumentException.class, () -> registry(path));
    }

    private record PreflightResult(int exitCode, String output) {
    }

    private PreflightResult preflight(Path moduleJar) throws Exception {
        Path runtime = directory.resolve("preflight-runtime-" + sequence++);
        Files.createDirectories(runtime.resolve("home"));
        Files.createDirectories(runtime.resolve("config"));
        Files.writeString(runtime.resolve("config/sentinel.json"), "{\"keep\":true}\n");
        Map<String, String> before = inventory(runtime);
        var classpath = new ArrayList<String>();
        classpath.add(hostJar.toString());
        classpath.add(moduleJar.toString());
        for (Class<?> dependency : List.of(tools.jackson.databind.ObjectMapper.class,
                tools.jackson.core.json.JsonFactory.class, com.fasterxml.jackson.annotation.JsonProperty.class)) {
            classpath.add(Path.of(dependency.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        }
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(executable, "-Dlogbook.runtime.managedBundle=true",
                "-Duser.home=" + runtime.resolve("home"), "-cp", String.join(java.io.File.pathSeparator, classpath),
                "logbook.core.FeatureBundlePreflight").directory(runtime.toFile()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "preflight must finish without launching the GUI");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(before, inventory(runtime), "preflight must not initialize or write application state");
            return new PreflightResult(process.exitValue(), output);
        } finally {
            if (process.isAlive())
                process.destroyForcibly();
        }
    }

    private Map<String, String> inventory(Path root) throws Exception {
        Map<String, String> values = new TreeMap<>();
        try (var files = Files.walk(root)) {
            for (Path path : files.toList()) {
                String key = root.relativize(path).toString();
                values.put(key, Files.isDirectory(path) ? "directory"
                        : HexFormat.of()
                                .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
            }
        }
        return values;
    }

    @Test
    void separateJvmPreflightAcceptsValidContractsWithoutApplicationClasses() throws Exception {
        var result = preflight(jar(METADATA, true));
        assertEquals(0, result.exitCode(), result.output());
        assertEquals(List.of("FEATURE_BUNDLE_PREFLIGHT_OK"), result.output().lines().toList());
    }

    @Test
    void separateJvmPreflightRejectsMetadataThatDisagreesWithRealCode() throws Exception {
        var result = preflight(jar(METADATA.replace("1.0.0", "1.1.0"), true));
        assertNotEquals(0, result.exitCode(), result.output());
        assertFalse(result.output().contains("FEATURE_BUNDLE_PREFLIGHT_OK"));
        assertTrue(result.output().contains("differs from provider contract"), result.output());
    }

    @Test
    void managedDiscoveryDoesNotAcceptAnExplodedProvider() {
        FeatureModule module = new FeatureModule() {
            @Override
            public Descriptor descriptor() {
                return new Descriptor("exploded", "1.0.0", 1, List.of());
            }

            @Override
            public List<Feature> features() {
                return List.of();
            }
        };
        assertThrows(IllegalArgumentException.class,
                () -> new FeatureRegistry(Stream.of(module), getClass().getClassLoader(), true));
    }
}
