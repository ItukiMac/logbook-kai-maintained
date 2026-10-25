package logbook.internal.gui;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import logbook.internal.calc.ProbabilityCalculator;

public class ProbCalcController extends WindowController {

    @FXML
    private TextField probInput;

    @FXML
    private TextField limitInput;

    @FXML
    private TextField stepInput;

    @FXML
    private TableView<ProbRow> resultTable;

    private ObservableList<ProbRow> rows = FXCollections.observableArrayList();

    @FXML
    void initialize() {
        this.resultTable.setItems(this.rows);
        this.probInput.textProperty().addListener((ob, o, n) -> this.update());
        this.limitInput.textProperty().addListener((ob, o, n) -> this.update());
        this.stepInput.textProperty().addListener((ob, o, n) -> this.update());
    }

    private void update() {
        this.rows.clear();
        String text = this.probInput.getText();
        if (this.limitInput == null || this.stepInput == null) return;
        String limitText = this.limitInput.getText();
        String stepText = this.stepInput.getText();
        if (text == null || text.isEmpty() || limitText == null || limitText.isEmpty()
                || stepText == null || stepText.isEmpty()) {
            return;
        }
        try {
            this.rows.setAll(ProbabilityCalculator.calculate(
                    Double.parseDouble(text), Integer.parseInt(limitText), Integer.parseInt(stepText))
                    .stream().map(point -> new ProbRow(point.count(), point.probability())).toList());
        } catch (NumberFormatException e) {
            // Ignore invalid input, preserving the existing empty-table behavior.
        }
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
