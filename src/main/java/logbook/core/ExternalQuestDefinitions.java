package logbook.core;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Opt-in read-only resolver for staged quest definitions.
 *
 * This class does not download, activate or validate a remote release.
 * Only explicitly configured local directories are considered; production
 * auto-updates must first implement complete generation verification.
 */
final class ExternalQuestDefinitions {
    static final String ROOT_PROPERTY = "logbook.data.root";

    private ExternalQuestDefinitions() {
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
            Path directory = root.resolve(folder);
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
