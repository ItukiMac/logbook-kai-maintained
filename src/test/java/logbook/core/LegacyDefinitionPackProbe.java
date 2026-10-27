package logbook.core;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Actual pinned legacy host JAR, loaded without the new host classes. Isolated files only. */
public final class LegacyDefinitionPackProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("legacy host JAR and NEW test directory required");
        Path legacy = Path.of(args[0]).toAbsolutePath(); Path test = Path.of(args[1]).toAbsolutePath();
        if (!Files.isRegularFile(legacy) || Files.isSymbolicLink(legacy)) throw new IOException("Invalid legacy JAR");
        Files.createDirectory(test);
        var old = DefinitionPackCompatibilityTest.pack(test, DefinitionPackCompatibility.LEGACY_FORMAT, null, "{}");
        var next = DefinitionPackCompatibilityTest.pack(test, DefinitionPackCompatibility.CAPABILITY_FORMAT,
                List.of(DefinitionPackCompatibility.DECK_IDS, DefinitionPackCompatibility.NO_RETREAT),
                "{\"filter\":{\"deckIds\":[1],\"noRetreat\":true}}");
        // Platform parent deliberately excludes current application/test classes.
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] {legacy.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Class<?> installer = Class.forName("logbook.core.DefinitionPackInstaller", true, loader);
            if (installer.getClassLoader() != loader) throw new AssertionError("Legacy class identity lost");
            var install = installer.getDeclaredMethod("install", Path.class, Path.class, String.class); install.setAccessible(true);
            Path legacyRoot = test.resolve("legacy");
            install.invoke(null, legacyRoot, old.path(), old.sha());
            byte[] pointer = Files.readAllBytes(legacyRoot.resolve("active.json"));
            try {
                install.invoke(null, legacyRoot, next.path(), next.sha());
                throw new AssertionError("Old host accepted incompatible V2 pack");
            } catch (InvocationTargetException expected) {
                if (!(expected.getCause() instanceof IOException)) throw expected;
            }
            if (!Arrays.equals(pointer, Files.readAllBytes(legacyRoot.resolve("active.json"))))
                throw new AssertionError("Legacy active generation changed after rejection");
            if (!Files.exists(legacyRoot.resolve("generations").resolve(old.sha()))) throw new AssertionError("Legacy generation lost");
            String index = "[{\"tag_name\":\"data-v2026.10.26.99\",\"assets\":[{\"name\":\"logbook-data-pack-v2.zip\",\"digest\":\"sha256:" + next.sha()
                    + "\",\"browser_download_url\":\"https://github.com/ItukiMac/logbook-kai-maintained/releases/download/data-v2026.10.26.99/logbook-data-pack-v2.zip\"}]}]";
            // This pinned historical JAR predates Jackson3. Do not substitute the
            // current mapper or add its classes to the deliberately isolated loader.
            Class<?> oldMapper = Class.forName("com.fasterxml.jackson.databind.ObjectMapper", true, loader);
            Object oldTree = oldMapper.getMethod("readTree", String.class).invoke(oldMapper.getConstructor().newInstance(), index);
            Class<?> oldClient = Class.forName("logbook.core.DataPackReleaseClient", true, loader);
            var select = oldClient.getDeclaredMethod("selectRelease", Class.forName("com.fasterxml.jackson.databind.JsonNode", true, loader));
            select.setAccessible(true);
            if (!((java.util.Optional<?>) select.invoke(null, oldTree)).isEmpty()) throw new AssertionError("Legacy host selected the V2-only asset");
            if (DataPackReleaseClient.selectRelease(logbook.internal.json.JsonMappers.builder().build().readTree(index)).isEmpty())
                throw new AssertionError("Current host did not select V2 asset");
        }
        Path modernRoot = test.resolve("modern");
        DefinitionPackInstaller.install(modernRoot, old.path(), old.sha());
        DefinitionPackInstaller.install(modernRoot, next.path(), next.sha());
        if (!next.sha().equals(GameDefinitionLoader.load(modernRoot).generation())) throw new AssertionError("V2 not readable");
        System.out.println("LEGACY_DATA_PACK_GATE_OK legacy-v1=accepted legacy-v2=rejected active=preserved current-v1-v2=accepted legacy-v2-discovery=skipped");
    }
}
