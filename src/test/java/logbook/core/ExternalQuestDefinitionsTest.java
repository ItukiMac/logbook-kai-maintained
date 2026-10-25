package logbook.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExternalQuestDefinitionsTest {
    @TempDir
    Path temp;

    @AfterEach
    void clearProperty() {
        System.clearProperty(ExternalQuestDefinitions.ROOT_PROPERTY);
    }

    @Test
    void disabledByDefault() throws Exception {
        Path file = temp.resolve("quest/1045.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"type\":\"出撃\"}");
        assertNull(ExternalQuestDefinitions.resolve(1045, true));
    }

    @Test
    void resolvesOnlyExistingCorrectCategoryAndFile() throws Exception {
        Files.createDirectories(temp.resolve("quest"));
        Files.createDirectories(temp.resolve("eventquest"));
        Files.writeString(temp.resolve("quest/1045.json"), "{\"type\":\"出撃\"}");
        Files.writeString(temp.resolve("eventquest/1045.json"), "{\"type\":\"遠征\"}");
        System.setProperty(ExternalQuestDefinitions.ROOT_PROPERTY, temp.toString());

        URL regular = ExternalQuestDefinitions.resolve(1045, true);
        URL event = ExternalQuestDefinitions.resolve(1045, false);
        assertNotNull(regular);
        assertNotNull(event);
        assertEquals("{\"type\":\"出撃\"}", new String(regular.openStream().readAllBytes()));
        assertEquals("{\"type\":\"遠征\"}", new String(event.openStream().readAllBytes()));
        assertNull(ExternalQuestDefinitions.resolve(404, true));
        assertNull(ExternalQuestDefinitions.resolve(-1, true));
        assertTrue(regular.getPath().endsWith("/quest/1045.json"));
    }

    @Test
    void rejectsSymlinkedDefinitions() throws Exception {
        Path target = temp.resolve("source.json");
        Files.writeString(target, "{\"type\":\"出撃\"}");
        Path directory = temp.resolve("quest");
        Files.createDirectories(directory);
        Files.createSymbolicLink(directory.resolve("1045.json"), target);
        System.setProperty(ExternalQuestDefinitions.ROOT_PROPERTY, temp.toString());
        assertNull(ExternalQuestDefinitions.resolve(1045, true));
    }
}
