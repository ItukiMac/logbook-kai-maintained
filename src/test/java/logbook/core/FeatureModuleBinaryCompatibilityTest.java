package logbook.core;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import logbook.plugin.api.FeatureModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Compile against the former SPI, then run the unchanged provider with the new API. */
class FeatureModuleBinaryCompatibilityTest {
    @TempDir
    Path directory;

    @Test
    void previouslyCompiledProviderUsesDefaultMetadataWithoutAbstractMethodError() throws Exception {
        Path api = directory.resolve("src/logbook/plugin/api/FeatureModule.java");
        Files.createDirectories(api.getParent());
        Files.writeString(api, """
                package logbook.plugin.api;
                public interface FeatureModule {
                    java.util.List<Feature> features();
                    enum Group { COMMAND, CALCULATOR, EXTRA }
                    record Feature(String id, String title, String fxml, Group group, int order, String accelerator) { }
                }
                """);
        Path provider = directory.resolve("src/fixture/LegacyFeatureProvider.java");
        Files.createDirectories(provider.getParent());
        Files.writeString(provider, """
                package fixture;
                import logbook.plugin.api.FeatureModule;
                public final class LegacyFeatureProvider implements FeatureModule {
                    public java.util.List<Feature> features() {
                        return java.util.List.of(new Feature("legacy-binary", "Legacy", "logbook/gui/prob_calc.fxml",
                                Group.CALCULATOR, 10, ""));
                    }
                }
                """);
        Path classes = directory.resolve("old-classes");
        Files.createDirectories(classes);
        assertNotNull(ToolProvider.getSystemJavaCompiler(), "The regression requires a JDK, not a JRE");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-proc:none", "-d", classes.toString(), api.toString(), provider.toString()));
        Path jar = directory.resolve("legacy-provider.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            String entry = "fixture/LegacyFeatureProvider.class";
            output.putNextEntry(new JarEntry(entry));
            output.write(Files.readAllBytes(classes.resolve(entry)));
            output.closeEntry();
        }
        try (var loader = new URLClassLoader(new java.net.URL[] { jar.toUri().toURL() },
                FeatureModule.class.getClassLoader())) {
            Class<?> type = loader.loadClass("fixture.LegacyFeatureProvider");
            assertSame(FeatureModule.class, type.getInterfaces()[0]);
            FeatureModule instance = (FeatureModule) type.getConstructor().newInstance();
            assertNull(instance.descriptor());
            var registry = new FeatureRegistry(Stream.of(instance), loader);
            assertEquals("legacy-binary", registry.get("legacy-binary").id());
            assertTrue(registry.descriptors().isEmpty());
        }
    }
}
