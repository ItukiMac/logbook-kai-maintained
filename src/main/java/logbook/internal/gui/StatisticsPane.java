package logbook.internal.gui;

import java.io.IOException;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.chart.BubbleChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.StackedBarChart;
import javafx.scene.chart.XYChart;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import logbook.internal.logger.LoggerHolder;
import logbook.plugin.api.FeatureUiV1;
import logbook.plugin.api.ShipStatisticsV1;

/** Locked-ship statistics tab. Reads one detached result; owns no mutable game beans. */
public class StatisticsPane extends VBox {
    @FXML
    private VBox content;
    @FXML
    private Text total;
    @FXML
    private Text quality;
    @FXML
    private PieChart ratio;
    @FXML
    private StackedBarChart<Double, String> average;
    @FXML
    private CategoryAxis averageCategory;
    @FXML
    private StackedBarChart<Double, String> median;
    @FXML
    private CategoryAxis medianCategory;
    @FXML
    private StackedBarChart<Number, String> spectrum;
    @FXML
    private CategoryAxis spectrumCategory;
    @FXML
    private BubbleChart<Double, Double> bubble;

    public StatisticsPane() {
        try {
            FXMLLoader loader = FeatureUiV1.componentLoader(getClass(), "/logbook/gui/statistics.fxml");
            loader.setRoot(this);
            loader.setController(this);
            loader.load();
        } catch (IOException error) {
            LoggerHolder.get().error("FXMLのロードに失敗しました", error);
        }
    }

    @FXML
    void initialize() {
    }

    @FXML
    void storeImageAction(ActionEvent event) {
        FeatureUiV1.Controls.storeSnapshot(this.content, "統計情報", this.content.getScene().getWindow());
    }

    public void update() {
        render(ShipStatisticsV1.snapshot());
    }

    /** Rendering only; an already-captured snapshot remains valid after the source changes. */
    public void render(ShipStatisticsV1.Snapshot snapshot) {
        if (!Platform.isFxApplicationThread())
            throw new IllegalStateException("Statistics rendering requires FX thread");
        java.util.Objects.requireNonNull(snapshot);
        this.total.setText(snapshot.lockedShips() > 0 && snapshot.missingExperience() == snapshot.lockedShips()
                ? "未記録"
                : ShipStatisticsV1.formatExperience(snapshot.recordedExperience()));
        this.quality.setText(snapshot.warnings().isEmpty() ? ""
                : "確認済みの値のみ集計：経験値不明 " + snapshot.missingExperience() + "隻、レベル不明 "
                        + snapshot.missingLevel() + "隻、艦種未分類 " + snapshot.unclassifiedShips()
                        + "隻、状態不明 " + snapshot.skippedRecords() + "件");
        this.quality.setVisible(!snapshot.warnings().isEmpty());
        this.quality.setManaged(!snapshot.warnings().isEmpty());
        this.averageCategory.setCategories(FXCollections.observableArrayList(snapshot.categories()));
        this.medianCategory.setCategories(FXCollections.observableArrayList(snapshot.categories()));
        this.spectrumCategory.setCategories(FXCollections.observableArrayList(snapshot.levelBands()));
        this.average.getData().clear();
        this.median.getData().clear();
        this.spectrum.getData().clear();
        this.bubble.getData().clear();
        var ratios = FXCollections.<PieChart.Data> observableArrayList();
        for (var group : snapshot.groups()) {
            if (group.recordedExperienceShips() > 0)
                ratios.add(new PieChart.Data(group.name(), group.experience()));
            if (group.average() != null && group.median() != null) {
                var a = new XYChart.Series<Double, String>();
                a.setName(group.name());
                a.getData().add(new XYChart.Data<>(group.average(), group.name()));
                this.average.getData().add(a);
                var m = new XYChart.Series<Double, String>();
                m.setName(group.name());
                m.getData().add(new XYChart.Data<>(group.median(), group.name()));
                this.median.getData().add(m);
                var b = new XYChart.Series<Double, Double>();
                b.setName(group.name());
                b.getData().add(new XYChart.Data<>(group.average(), group.median(), group.recordedLevels() / 5.0));
                this.bubble.getData().add(b);
            }
            if (!group.histogram().isEmpty()) {
                var series = new XYChart.Series<Number, String>();
                series.setName(group.name());
                group.histogram().forEach((label, count) -> series.getData().add(new XYChart.Data<>(count, label)));
                this.spectrum.getData().add(series);
            }
        }
        this.ratio.setData(ratios);
    }
}
