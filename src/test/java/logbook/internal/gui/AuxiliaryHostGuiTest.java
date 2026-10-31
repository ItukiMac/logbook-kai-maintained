package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import logbook.bean.AppDeckCollection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Host-owned auxiliary controls; no Robot, chooser, capture, ffmpeg or private files. */
@EnabledIfSystemProperty(named = "logbook.gui.tests", matches = "true")
@Timeout(25)
class AuxiliaryHostGuiTest {
    @BeforeAll
    static void start() throws Exception {
        FxTestSupport.start();
    }

    private void window(String resource) throws Exception {
        FxTestSupport.fx(() -> {
            var loader = new FXMLLoader(getClass().getResource("/logbook/gui/" + resource + ".fxml"));
            Parent root = loader.load();
            var stage = new Stage();
            try {
                // CaptureController.setWindow intentionally constructs Robot and probes
                // the desktop. Its native binding belongs to separate real-device testing.
                if (loader.getController() instanceof CaptureController capture) {
                    var field = CaptureController.class.getDeclaredField("sc");
                    field.setAccessible(true);
                    assertNull(field.get(capture));
                } else if (loader.getController() instanceof WindowController controller)
                    controller.initWindow(stage);
                stage.setScene(new Scene(root));
                stage.show();
                root.applyCss();
                root.layout();
                assertTrue(stage.isShowing());
                assertFalse(loader.getNamespace().isEmpty());
            } finally {
                stage.close();
            }
            return null;
        });
    }

    @Test
    void captureFxmlLoadsAndClosesWithoutBindingNativeDesktop() throws Exception {
        window("capture");
    }

    @Test
    void captureSaveWindowLoadsWithoutOpeningDestinationChooser() throws Exception {
        window("capturesave");
    }

    @Test
    void savedFormationEditorOpensAndClosesWithoutChangingCollection() throws Exception {
        var before = java.util.List.copyOf(AppDeckCollection.get().getDecks());
        window("deck");
        assertEquals(before, AppDeckCollection.get().getDecks());
    }

}
