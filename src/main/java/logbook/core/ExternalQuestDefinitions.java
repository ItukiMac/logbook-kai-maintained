package logbook.core;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Opt-in read-only resolver for staged quest definitions.
 *
 * Only explicitly configured local directories are considered. If an
 * active.json pointer exists, only its verified, staged generation is read.
 * Network retrieval and publisher authentication are separate operations.
 */
final class ExternalQuestDefinitions {
    static final String ROOT_PROPERTY = "logbook.data.root";

    private ExternalQuestDefinitions() {
    }

    /**
     * An activated generation is authoritative for its managed quest categories.
     * Returning null for an absent JSON must not resurrect obsolete bundled
     * event quests or definitions from the application JAR.
     */
    static boolean hasActiveGeneration() {
        String configuredRoot = System.getProperty(ROOT_PROPERTY);
        if (configuredRoot == null || configuredRoot.isBlank()) {
            return false;
        }
        try {
            Path root = Path.of(configuredRoot).toAbsolutePath().normalize();
            return Files.exists(root.resolve("active.json"), LinkOption.NOFOLLOW_LINKS);
        } catch (java.nio.file.InvalidPathException e) {
            return false;
        }
    }

    /**
     * Managed external JSON files other than quest conditions. Asset/capture
     * preferences and user-generated files are deliberately excluded.
     */
    static boolean isManagedGameDataResource(String name) {
        if (name == null || !name.startsWith("logbook/") || !name.endsWith(".json")
                || name.contains("..") || name.contains("\\") || name.contains("//")) {
            return false;
        }
        return name.startsWith("logbook/mission/")
                || name.startsWith("logbook/map/")
                || name.startsWith("logbook/kousyou/")
                || name.startsWith("logbook/supplemental/");
    }

    static URL resolveManagedResource(String name) {
        if (!isManagedGameDataResource(name)) return null;
        String rootValue = System.getProperty(ROOT_PROPERTY);
        if (rootValue == null || rootValue.isBlank()) return null;
        try {
            Path root = Path.of(rootValue).toAbsolutePath().normalize();
            Path active = DefinitionPackInstaller.activeDefinitionsRoot(root);
            Path relative = Path.of(name.substring("logbook/".length()));
            Path target = active.resolve(relative).normalize();
            if (!target.startsWith(active) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isReadable(target)) {
                return null;
            }
            // Reject symlinked nested folders before opening any data file.
            Path parent = target.getParent();
            while (parent != null && parent.startsWith(active)) {
                if (Files.isSymbolicLink(parent)) return null;
                if (parent.equals(active)) break;
                parent = parent.getParent();
            }
            return target.toUri().toURL();
        } catch (Exception e) {
            return null;
        }
    }

    static URL resolve(int questNo, boolean regular) {
        String configuredRoot = System.getProperty(ROOT_PROPERTY);
        if (configuredRoot == null || configuredRoot.isBlank() || questNo <= 0) {
            return null;
        }

        try {
            Path root = Path.of(configuredRoot).toAbsolutePath().normalize();
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }

            String folder = regular ? "quest" : "eventquest";
            Path activeRoot = DefinitionPackInstaller.activeDefinitionsRoot(root);
            Path directory = activeRoot.resolve(folder);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }

            Path file = directory.resolve(questNo + ".json").normalize();
            if (!file.startsWith(directory)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isReadable(file)) {
                return null;
            }
            // Refuse symlinked roots and intermediate directories.
            if (!root.toRealPath().equals(root)
                    || !directory.toRealPath().equals(directory)) {
                return null;
            }

            return file.toUri().toURL();
        } catch (Exception ignored) {
            return null;
        }
    }
}
