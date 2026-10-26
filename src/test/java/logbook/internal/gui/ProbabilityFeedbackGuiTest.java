package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "logbook.gui.tests", matches = "true")
@Timeout(20)
class ProbabilityFeedbackGuiTest {
    @BeforeAll static void start() throws Exception { FxTestSupport.start(); }
    @Test void actualFxmlShowsRejectionAndRecoversWithoutChangingDefaultInputs() throws Exception {
        FxTestSupport.fx(() -> {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/logbook/gui/prob_calc.fxml"));
            Parent root = loader.load();
            Stage stage = new Stage();
            stage.setScene(new Scene(root));
            stage.show();
            try {
                TextField p = (TextField) loader.getNamespace().get("probInput");
                TextField limit = (TextField) loader.getNamespace().get("limitInput");
                TextField step = (TextField) loader.getNamespace().get("stepInput");
                Label message = (Label) loader.getNamespace().get("inputMessage");
                TableView<?> table = (TableView<?>) loader.getNamespace().get("resultTable");
                assertEquals("100", limit.getText()); assertEquals("10", step.getText());
                assertTrue(message.isVisible());
                p.setText("1"); assertFalse(message.isVisible()); assertFalse(table.getItems().isEmpty());
                limit.setText("2147483647"); step.setText("1");
                assertTrue(message.isVisible()); assertTrue(message.isManaged());
                assertTrue(message.getText().contains("10000行")); assertTrue(table.getItems().isEmpty());
                root.applyCss(); root.layout();
                assertTrue(message.getHeight() > 0);
                assertTrue(message.getBoundsInParent().getMaxX() <= root.getLayoutBounds().getWidth() + 1);
                step.setText("2147483647");
                assertFalse(message.isVisible()); assertFalse(table.getItems().isEmpty());
                p.setText("NaN"); assertTrue(message.isVisible()); assertTrue(table.getItems().isEmpty());
                p.setText("2"); assertFalse(message.isVisible());
            } finally { stage.close(); }
            return null;
        });
    }
}
