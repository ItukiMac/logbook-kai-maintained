package logbook.internal.gui;

import logbook.plugin.api.FeatureUiV1;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.TableView;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import logbook.internal.calc.ProbabilityInput;

public class ProbCalcController extends FeatureUiV1.Window {

    @FXML
    private TextField probInput;

    @FXML
    private TextField limitInput;

    @FXML
    private TextField stepInput;

    @FXML
    private TableView<ProbRow> resultTable;

    @FXML
    private Label inputMessage;

    private ObservableList<ProbRow> rows = FXCollections.observableArrayList();

    @FXML
    void initialize() {
        this.resultTable.setItems(this.rows);
        this.probInput.textProperty().addListener((ob, o, n) -> this.update());
        this.limitInput.textProperty().addListener((ob, o, n) -> this.update());
        this.stepInput.textProperty().addListener((ob, o, n) -> this.update());
        this.update();
    }

    private void update() {
        var result = ProbabilityInput.evaluate(this.probInput.getText(),
                this.limitInput.getText(), this.stepInput.getText());
        this.rows.setAll(result.points().stream()
                .map(point -> new ProbRow(point.count(), point.probability())).toList());
        this.inputMessage.setText(result.message());
        this.inputMessage.setVisible(!result.valid());
        this.inputMessage.setManaged(!result.valid());
    }

    // Preserve the public row type/getters used by the existing FXML and callers.
    public static class ProbRow {
        private final int count;
        private final double prob;

        public ProbRow(int count, double prob) {
            this.count = count;
            this.prob = prob;
        }

        public int getCount() {
            return count;
        }

        public double getProb() {
            return prob;
        }

        public String getCountString() {
            return String.valueOf(count);
        }

        public String getProbString() {
            return String.format("%.1f%%", prob * 100.0);
        }
    }
}
