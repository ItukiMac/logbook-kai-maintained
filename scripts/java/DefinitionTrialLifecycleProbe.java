package logbook.core;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipFile;
import logbook.internal.json.JsonMappers;

/** Actual pinned published baseline -> single-definition trial -> retained generation restore. */
public final class DefinitionTrialLifecycleProbe {
    private static String hash(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }

    private static void require(boolean value, String message) {
        if (!value)
            throw new AssertionError(message);
    }

    public static void main(String[] a) throws Exception {
        if (a.length != 6)
            throw new IllegalArgumentException("baseline sha trial sha NEW-store actual-jar");
        Path old = Path.of(a[0]), next = Path.of(a[2]), store = Path.of(a[4]), jar = Path.of(a[5]);
        require(!Files.exists(store), "New data-only store required");
        String jarHash = hash(jar), oldHash = hash(old), nextHash = hash(next);
        require(oldHash.equals(a[1]) && nextHash.equals(a[3]), "Pinned archive identity");
        String original = DefinitionPackInstaller.install(store, old, a[1]);
        byte[] oldRule = Files
                .readAllBytes(DefinitionPackInstaller.activeDefinitionsRoot(store).resolve("mission/1/A1.json"));
        String candidate = DefinitionPackInstaller.install(store, next, a[3]);
        require(!original.equals(candidate), "Distinct actual generations");
        var active = DefinitionPackInstaller.activeDefinitionsRoot(store);
        var json = JsonMappers.builder().build();
        var rule = json.readTree(Files.readAllBytes(active.resolve("mission/1/A1.json")));
        require(rule.path("conditions").get(0).path("value").asInt() == 10, "Fleet level10 applied");
        require(rule.path("conditions").get(1).path("conditions").get(0).path("level").asInt() == 5,
                "Flagship level5 applied");
        int checked = 0, changed = 0;
        try (var z = new ZipFile(old.toFile())) {
            var entries = z.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().startsWith("definitions/"))
                    continue;
                byte[] before = z.getInputStream(entry).readAllBytes();
                byte[] after = Files.readAllBytes(active.resolve(entry.getName().substring("definitions/".length())));
                if (!java.util.Arrays.equals(before, after)) {
                    changed++;
                    require(entry.getName().equals("definitions/mission/1/A1.json"), "Only reviewed A1 changes");
                }
                checked++;
            }
        }
        require(checked == 152 && changed == 1, "Complete152 definitions and exactly1 changed");
        require(DefinitionPackInstaller.restoreGeneration(store, original).equals(original),
                "Restore original pointer");
        require(java.util.Arrays.equals(oldRule,
                Files.readAllBytes(DefinitionPackInstaller.activeDefinitionsRoot(store).resolve("mission/1/A1.json"))),
                "Exact original rule restored");
        require(Files.isDirectory(store.resolve("generations").resolve(candidate)), "Candidate generation retained");
        require(jarHash.equals(hash(jar)) && oldHash.equals(hash(old)) && nextHash.equals(hash(next)),
                "JAR and both archives unchanged");
        require(!Files.exists(store.resolve("logbook-kai2.jar")) && !Files.exists(store.resolve("plugins")),
                "Data-only store");
        System.out.println(
                "SINGLE_DEFINITION_LIFECYCLE_OK files=152 changed=1 apply=true restore=true original_retained=true jar_unchanged=true production=false");
    }
}
