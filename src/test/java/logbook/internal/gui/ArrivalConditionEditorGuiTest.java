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
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import logbook.plugin.api.QuestEditorDataV1.Choice;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Real editor/FXML and real document store, restricted to a fresh temporary directory. */
@EnabledIfSystemProperty(named="logbook.gui.tests", matches="true")
@Timeout(30)
class ArrivalConditionEditorGuiTest {
    @TempDir Path root;
    @BeforeAll static void startFx() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); }); }
        catch (IllegalStateException alreadyRunning) { Platform.runLater(ready::countDown); }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
    }
    static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }
    final class IsolatedController extends QuestConditionEditController {
        @Override Path questDirectory() { return root; }
    }
    record Window(Stage stage, Parent pane, QuestConditionEditController controller) implements AutoCloseable {
        @SuppressWarnings("unchecked") ComboBox<Choice> quests() { return (ComboBox<Choice>) pane.lookup("#questSelector"); }
        TextField text(String id) { return (TextField) pane.lookup("#" + id); }
        HBox row(int index) { return (HBox) ((VBox) pane.lookup("#conditionsBox")).getChildren().get(index); }
        CheckBox arrival(int index) { return (CheckBox) row(index).lookup("#arrivalMode"); }
        TextField count(int index) { return (TextField) row(index).getChildren().get(6); }
        TextField cell(int index) { return (TextField) row(index).getChildren().get(2); }
        CheckBox rank(int index, int rank) { return (CheckBox) row(index).getChildren().get(3 + rank); }
        public void close() { stage.close(); }
    }
    Window open() throws Exception {
        var loader = new FXMLLoader(getClass().getResource("/logbook/gui/quest_condition_edit.fxml"));
        loader.setControllerFactory(type -> new IsolatedController());
        Parent pane = loader.load();
        Stage stage = new Stage(); stage.setScene(new Scene(pane)); stage.show();
        ((RadioButton) pane.lookup("#customQuestRadio")).fire();
        var window = new Window(stage, pane, loader.getController());
        window.quests().getSelectionModel().select(0);
        return window;
    }
    static String source(boolean arrival) {
        return "/* original bytes */\n{\"type\":\"出撃\",\"resetType\":\"単発\",\"customName\":\"before\","
            + "\"filter\":{\"area\":[\"1-6\"],\"deckIds\":[1,3],\"noRetreat\":true,\"futureFilter\":7},"
            + "\"conditions\":[{\"area\":[\"1-6\"],"
            + (arrival ? "\"arrival\":true," : "\"boss\":true,\"rank\":[\"S\"],\"cell\":\"B\",")
            + "\"count\":2,\"futureCondition\":\"keep\"}]}";
    }
    @Test void existingArrivalIsVisibleAndNoEditRetainsExactBytes() throws Exception {
        Path file = root.resolve("10001.json"); Files.writeString(file, source(true));
        fx(() -> {
            try (var w = open()) {
                assertNotNull(w.arrival(0), "1-6 needs an explicit arrival control");
                assertTrue(w.arrival(0).isSelected());
                assertTrue(w.cell(0).isDisabled());
                for (int i = 0; i < 3; i++) assertTrue(w.rank(0, i).isDisabled());
                assertFalse(w.count(0).isDisabled());
                assertEquals("2", w.count(0).getText());
                assertNull(w.controller().saveDocument());
                assertEquals(source(true), Files.readString(file));
            } return null;
        });
    }
    @Test void editedCountSurvivesReloadAndPreservesHiddenFieldsAndOriginalBackup() throws Exception {
        Path file = root.resolve("10001.json"); Files.writeString(file, source(true));
        fx(() -> {
            try (var w = open()) {
                w.count(0).setText("3");
                Path backup = w.controller().saveDocument();
                assertEquals(source(true), Files.readString(backup));
                var doc = QuestEditorDocument.mapper().readTree(file.toFile());
                var condition = doc.path("conditions").get(0);
                assertTrue(condition.path("arrival").asBoolean());
                assertEquals(3, condition.path("count").asInt());
                assertEquals("keep", condition.path("futureCondition").asText());
                assertEquals(7, doc.path("filter").path("futureFilter").asInt());
                w.quests().getSelectionModel().select(1); w.quests().getSelectionModel().select(0);
                assertTrue(w.arrival(0).isSelected()); assertEquals("3", w.count(0).getText());
            } return null;
        });
    }
    @Test void newArrivalOmitsBattleOperandsAndModeToggleRestoresUnsavedBattleInputs() throws Exception {
        fx(() -> {
            try (var w = open()) {
                w.text("filterArea").setText("1-6");
                w.cell(0).setText("B"); w.rank(0, 0).setSelected(true); w.count(0).setText("2");
                assertNotNull(w.arrival(0)); w.arrival(0).fire();
                var condition = w.controller().readForm().getConditions().get(0);
                assertTrue(condition.isArrival()); assertFalse(condition.isBoss());
                assertNull(condition.getRank()); assertNull(condition.getCell()); assertNull(condition.getCells());
                w.arrival(0).fire();
                assertFalse(w.cell(0).isDisabled()); assertEquals("B", w.cell(0).getText());
                assertTrue(w.rank(0, 0).isSelected());
                w.arrival(0).fire(); w.controller().saveDocument();
                var saved = QuestEditorDocument.mapper().readTree(root.resolve("10001.json").toFile());
                QuestEditorDocument.validate(saved);
                assertTrue(saved.path("conditions").get(0).path("arrival").asBoolean());
            } return null;
        });
    }
    @Test void loadedBattleCanConvertToArrivalAndBackWithoutLosingUnknownFields() throws Exception {
        Path file = root.resolve("10001.json"); Files.writeString(file, source(false));
        fx(() -> {
            try (var w = open()) {
                assertNotNull(w.arrival(0)); w.arrival(0).fire();
                Path old = w.controller().saveDocument(); assertEquals(source(false), Files.readString(old));
                var condition = QuestEditorDocument.mapper().readTree(file.toFile()).path("conditions").get(0);
                assertTrue(condition.path("arrival").asBoolean()); assertFalse(condition.hasNonNull("rank"));
                assertFalse(condition.hasNonNull("cell")); assertEquals("keep", condition.path("futureCondition").asText());
                w.arrival(0).fire(); w.controller().saveDocument();
                condition = QuestEditorDocument.mapper().readTree(file.toFile()).path("conditions").get(0);
                assertFalse(condition.has("arrival")); assertEquals("B", condition.path("cell").asText());
                assertEquals("S", condition.path("rank").get(0).asText());
                assertEquals("keep", condition.path("futureCondition").asText());
            } return null;
        });
    }
    @Test void hiddenBattleRankIsNotSilentlyDroppedWhenConverting() throws Exception {
        String original = source(false).replace("[\"S\"]", "[\"S\",\"C\"]");
        Path file = root.resolve("10001.json"); Files.writeString(file, original);
        fx(() -> {
            try (var w = open()) {
                assertNotNull(w.arrival(0)); w.arrival(0).fire();
                assertThrows(java.io.IOException.class, w.controller()::saveDocument);
                assertEquals(original, Files.readString(file));
            } return null;
        });
    }
    @Test void arrivalCountsMustBePositiveIntegersAndNeverCreateAnInvalidFile() throws Exception {
        fx(() -> {
            try (var w = open()) {
                w.text("filterArea").setText("1-6"); assertNotNull(w.arrival(0)); w.arrival(0).fire();
                for (String invalid : new String[] {"", "0", "-1", "1.5", "abc", "2147483648"}) {
                    w.count(0).setText(invalid);
                    assertThrows(IllegalArgumentException.class, w.controller()::saveDocument, invalid);
                    assertFalse(Files.exists(root.resolve("10001.json")));
                }
            } return null;
        });
    }
    @Test void harmlessAreaTextEditsRetainModeAndSwitchingQuestsResetsIt() throws Exception {
        fx(() -> {
            try (var w = open()) {
                w.text("filterArea").setText("1-6"); assertNotNull(w.arrival(0)); w.arrival(0).fire();
                w.count(0).setText("3"); w.text("filterArea").setText(" 1-6 , 1-5 ");
                assertTrue(w.arrival(0).isSelected()); assertEquals("3", w.count(0).getText());
                assertNull(w.arrival(1));
                w.quests().getSelectionModel().select(1); w.text("filterArea").setText("1-6");
                assertFalse(w.arrival(0).isSelected());
            } return null;
        });
    }
    @Test void otherAreasKeepLegacyBattleFormWithoutArrivalField() throws Exception {
        fx(() -> {
            try (var w = open()) {
                w.text("filterArea").setText("1-5,7-5-3");
                assertNull(w.arrival(0)); assertNull(w.arrival(1));
                var form = w.controller().readForm();
                assertFalse(form.getConditions().get(0).isArrival());
                assertEquals("T", form.getConditions().get(1).getCell());
            } return null;
        });
    }
    @Test void externalEditStillPreventsSavingArrivalChanges() throws Exception {
        Path file = root.resolve("10001.json"); Files.writeString(file, source(true));
        fx(() -> {
            try (var w = open()) {
                assertNotNull(w.arrival(0)); w.count(0).setText("4");
                Files.writeString(file, "external bytes");
                assertThrows(java.io.IOException.class, w.controller()::saveDocument);
                assertEquals("external bytes", Files.readString(file));
            } return null;
        });
    }
    @Test void normalQuestRowWithoutCellFieldDoesNotMisreadTheAddedCheckbox() throws Exception {
        Path file = root.resolve("905.json"); Files.writeString(file, source(true));
        fx(() -> {
            try (var w = open()) {
                var appQuest = new Choice(905,2,4,"fixture");
                w.quests().getItems().setAll(appQuest); w.quests().getSelectionModel().select(0);
                assertTrue(w.arrival(0).isSelected());
                assertTrue(w.row(0).getChildren().get(1) instanceof CheckBox);
                assertEquals(2, w.controller().readForm().getConditions().get(0).getCount());
                assertNull(w.controller().saveDocument());
                assertEquals(source(true), Files.readString(file));
            } return null;
        });
    }
    @Test void changingArrivalMapRequiresExplicitModeRemovalAndPreservesSourceUntilThen() throws Exception {
        Path file = root.resolve("10001.json"); Files.writeString(file, source(true));
        fx(() -> {
            try (var w = open()) {
                w.text("filterArea").setText("1-5");
                assertThrows(IllegalArgumentException.class, w.controller()::saveDocument);
                assertEquals(source(true), Files.readString(file));
                w.text("filterArea").setText("1-6");
                assertTrue(w.arrival(0).isSelected()); assertEquals("2", w.count(0).getText());
                assertNull(w.controller().saveDocument());
                w.text("filterArea").setText("1-5"); w.arrival(0).fire();
                assertNull(w.arrival(0));
                w.controller().saveDocument();
                var condition = QuestEditorDocument.mapper().readTree(file.toFile()).path("conditions").get(0);
                assertFalse(condition.has("arrival")); assertEquals("1-5", condition.path("area").get(0).asText());
            } return null;
        });
    }
    @Test void freshOrdinaryBattleDocumentDoesNotDeclareArrivalCapability() throws Exception {
        fx(() -> {
            try (var w = open()) {
                w.text("filterArea").setText("1-5"); w.controller().saveDocument();
                var condition = QuestEditorDocument.mapper().readTree(root.resolve("10001.json").toFile()).path("conditions").get(0);
                assertFalse(condition.has("arrival"));
            } return null;
        });
    }

}
