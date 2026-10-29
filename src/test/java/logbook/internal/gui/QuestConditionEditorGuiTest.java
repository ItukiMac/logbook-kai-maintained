package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import logbook.plugin.api.QuestEditorDataV1.Choice;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfSystemProperty(named="logbook.gui.tests", matches="true")
@Timeout(30)
class QuestConditionEditorGuiTest {
    @TempDir Path root;
    @BeforeAll static void fxStart() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); }); }
        catch (IllegalStateException running) { Platform.runLater(ready::countDown); }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
    }
    static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); Platform.runLater(task); return task.get(15, TimeUnit.SECONDS);
    }
    final class IsolatedController extends QuestConditionEditController {
        @Override Path questDirectory() { return root; }
    }
    record Window(Stage stage, Parent pane, QuestConditionEditController controller) implements AutoCloseable {
        @SuppressWarnings("unchecked") ComboBox<Choice> quests() { return (ComboBox<Choice>) pane.lookup("#questSelector"); }
        TextField text(String name) { return (TextField) pane.lookup("#" + name); }
        CheckBox noRetreat() { return (CheckBox) pane.lookup("#filterNoRetreat"); }
        public void close() { stage.close(); }
    }
    Window open() throws Exception {
        var loader = new FXMLLoader(getClass().getResource("/logbook/gui/quest_condition_edit.fxml"));
        loader.setControllerFactory(type -> new IsolatedController());
        Parent pane = loader.load(); Stage stage = new Stage(); stage.setScene(new Scene(pane)); stage.show();
        ((RadioButton) pane.lookup("#customQuestRadio")).fire();
        Window result = new Window(stage, pane, loader.getController()); result.quests().getSelectionModel().select(0);
        return result;
    }
    private static String source() {
        return "/* retained source */\n{\"type\":\"出撃\",\"resetType\":\"単発\",\"customName\":\"before\","
                + "\"filter\":{\"area\":[\"1-1\"],\"deckIds\":[1,3],\"noRetreat\":true,\"futureFilter\":{\"x\":2}},"
                + "\"conditions\":[{\"area\":[\"1-1\"],\"boss\":true,\"rank\":[\"S\",\"C\"],\"count\":1,\"futureCondition\":true}]}";
    }
    @Test void actualFxmlNoEditAndUnrelatedEditPreserveGuardsAndHiddenValues() throws Exception {
        Path file = root.resolve("10001.json"); Files.writeString(file, source());
        fx(() -> {
            try (Window window = open()) {
                assertEquals("1,3", window.text("filterDeckIds").getText()); assertTrue(window.noRetreat().isSelected());
                assertNull(window.controller().saveDocument());
                assertEquals(source(), Files.readString(file));
                window.text("customNameField").setText("after");
                Path backup = window.controller().saveDocument();
                assertEquals(source(), Files.readString(backup));
                var saved = QuestEditorDocument.mapper().readTree(file.toFile());
                assertEquals("after", saved.path("customName").asText());
                assertEquals(2, saved.path("filter").path("futureFilter").path("x").asInt());
                assertEquals("C", saved.path("conditions").get(0).path("rank").get(1).asText());
                assertTrue(saved.path("filter").path("noRetreat").asBoolean());
            }
            return null;
        });
    }
    @Test void switchingQuestClearsGuardsAndInvalidInputDoesNotWrite() throws Exception {
        Files.writeString(root.resolve("10001.json"), source());
        fx(() -> {
            try (Window window = open()) {
                window.quests().getSelectionModel().select(1);
                assertEquals("", window.text("filterDeckIds").getText()); assertFalse(window.noRetreat().isSelected());
                window.text("filterDeckIds").setText("5");
                assertThrows(IllegalArgumentException.class, window.controller()::saveDocument);
                assertFalse(Files.exists(root.resolve("10002.json")));
                window.text("filterDeckIds").setText("2,4"); window.noRetreat().setSelected(true);
                window.controller().saveDocument();
                window.quests().getSelectionModel().select(0); window.quests().getSelectionModel().select(1);
                assertEquals("2,4", window.text("filterDeckIds").getText()); assertTrue(window.noRetreat().isSelected());
            }
            return null;
        });
    }
    @Test void removalThenSaveInTheSameSlotResetsSnapshotAndGuardsWithoutLosingOriginal() throws Exception {
        Path file = root.resolve("10001.json"); Files.writeString(file, source());
        fx(() -> {
            try (Window window = open()) {
                Path retained = window.controller().removeDocument();
                assertFalse(Files.exists(file)); assertEquals(source(), Files.readString(retained));
                assertEquals(10001, window.quests().getValue().id());
                assertEquals("", window.text("filterDeckIds").getText()); assertFalse(window.noRetreat().isSelected());
                window.text("customNameField").setText("recreated");
                assertNull(window.controller().saveDocument());
                var saved = QuestEditorDocument.mapper().readTree(file.toFile());
                assertEquals("recreated", saved.path("customName").asText());
                assertFalse(saved.path("filter").has("deckIds")); assertFalse(saved.path("filter").has("noRetreat"));
                assertEquals(source(), Files.readString(retained));
            }
            return null;
        });
    }
    @Test void malformedLoadedFileAndExternalChangeCannotBeOverwritten() throws Exception {
        Path file = root.resolve("10001.json"); Files.writeString(file, "{broken");
        fx(() -> {
            try (Window window = open()) {
                assertThrows(java.io.IOException.class, window.controller()::saveDocument);
                assertEquals("{broken", Files.readString(file));
                Files.writeString(file, source()); window.quests().getSelectionModel().select(1); window.quests().getSelectionModel().select(0);
                Files.writeString(file, "external edit"); window.text("customNameField").setText("ignored");
                assertThrows(java.io.IOException.class, window.controller()::saveDocument);
                assertEquals("external edit", Files.readString(file));
            }
            return null;
        });
    }
}
