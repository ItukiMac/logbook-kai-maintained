package logbook.internal.gui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.chart.BubbleChart;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.StackedBarChart;
import javafx.scene.control.TabPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import logbook.bean.*;
import logbook.core.GameDefinitionRuntime;
import logbook.core.LogBookCoreServices;
import logbook.plugin.api.ShipStatisticsV1;

/** Actual ship-window statistics tab in the product distribution, synthetic state only. */
public final class ShipStatisticsGuiProbe {
    private static void check(boolean value, String message) {
        if (!value)
            throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 1 && args[0].equals("synthetic-statistics-only"), "Explicit fixture mode required");
        check(!Files.exists(Path.of("config")) && !Files.exists(Path.of("retained-history.csv")),
                "Refusing existing runtime");
        byte[] history = "synthetic retained history\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(Path.of("retained-history.csv"), history, StandardOpenOption.CREATE_NEW);
        var errors = new CopyOnWriteArrayList<Throwable>();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            errors.add(e);
            e.printStackTrace();
        });
        LogBookCoreServices.init(List.of());
        GameDefinitionRuntime.initialize();
        fixtures();
        var ready = new CompletableFuture<Void>();
        Platform.startup(() -> ready.complete(null));
        ready.get(20, TimeUnit.SECONDS);
        Platform.setImplicitExit(false);
        try {
            var done = new CompletableFuture<Void>();
            Platform.runLater(() -> {
                try {
                    window();
                    done.complete(null);
                } catch (Throwable error) {
                    done.completeExceptionally(error);
                }
            });
            done.get(40, TimeUnit.SECONDS);
            check(errors.isEmpty(), "Unhandled FX error: " + errors);
            check(Arrays.equals(history, Files.readAllBytes(Path.of("retained-history.csv"))), "History changed");
            System.out.println(
                    "SHIP_STATISTICS_GUI_OK charts=5 product_charts_jar=true actual_ship_tab=true frozen_after_mutation=true unknown_values_visible=true image_snapshot=true original_history_unchanged=true production_accessed=false");
        } finally {
            Platform.exit();
        }
    }

    private static void fixtures() {
        AppConfig.get().setHideShipImageFromShipTablePane(true);
        AppConfig.get().setHideItemImageFromShipTablePane(true);
        AppConfig.get().setAllDecksTab(false);
        AppConfig.get().setDeckTabs(false);
        AppConfig.get().setLabelTabs(false);
        for (int id : List.of(1, 2, 3, 4)) {
            int type = id == 3 ? 3 : 2;
            Stype stype = new Stype();
            stype.setId(type);
            stype.setName(type == 3 ? "軽巡洋艦" : "駆逐艦");
            StypeCollection.get().getStypeMap().put(type, stype);
            ShipMst master = new ShipMst();
            master.setId(1100000 + id);
            master.setName("synthetic " + id);
            master.setSortId(id);
            master.setStype(type);
            master.setCtype(1);
            master.setFuelMax(10);
            master.setBullMax(10);
            ShipMstCollection.get().getShipMap().put(master.getId(), master);
            Ship ship = new Ship();
            ship.setId(id);
            ship.setShipId(master.getId());
            ship.setLv(new int[] { 10, 20, 99, 150 }[id - 1]);
            ship.setCond(49);
            ship.setExp(List.of(new int[] { 100, 300, 600, 999999 }[id - 1], 100));
            ship.setSlot(List.of(-1, -1, -1, -1, -1));
            ship.setSlotEx(0);
            ship.setSlotnum(0);
            ship.setOnslot(List.of(0, 0, 0, 0, 0));
            ship.setNowhp(15);
            ship.setMaxhp(15);
            ship.setSoku(10);
            ship.setLeng(1);
            ship.setFuel(10);
            ship.setBull(10);
            ship.setLocked(id != 4);
            ship.setSallyArea(0);
            ship.setKyouka(List.of(0, 0, 0, 0, 0));
            ship.setKaryoku(List.of(10, 20));
            ship.setRaisou(List.of(10, 20));
            ship.setTaiku(List.of(10, 20));
            ship.setSoukou(List.of(10, 20));
            ship.setKaihi(List.of(10, 20));
            ship.setTaisen(List.of(10, 20));
            ship.setSakuteki(List.of(10, 20));
            ship.setLucky(List.of(10, 20));
            ShipCollection.get().getShipMap().put(id, ship);
        }
    }

    private static Object value(StatisticsPane pane, String field) throws Exception {
        var f = StatisticsPane.class.getDeclaredField(field);
        f.setAccessible(true);
        return f.get(pane);
    }

    @SuppressWarnings("unchecked")
    private static void window() throws Exception {
        check(StatisticsPane.class.getProtectionDomain().getCodeSource().getLocation().getPath()
                .endsWith("/features/logbook-kai2-feature-charts.jar"), "Statistics pane loaded from host fallback");
        check(StatisticsPane.class.getResource("/logbook/gui/statistics.fxml").toString()
                .contains("logbook-kai2-feature-charts.jar!"), "Statistics FXML not in charts");
        FXMLLoader loader = new FXMLLoader(ShipController.class.getResource("/logbook/gui/ship.fxml"));
        Parent root = loader.load();
        ShipController controller = loader.getController();
        Stage stage = new Stage();
        controller.setWindow(stage);
        try {
            stage.setScene(new Scene(root));
            stage.show();
            root.applyCss();
            root.layout();
            TabPane tabs = (TabPane) loader.getNamespace().get("tab");
            var tab = tabs.getTabs().stream().filter(t -> "統計".equals(t.getText())).findFirst().orElseThrow();
            tabs.getSelectionModel().select(tab);
            check(tab.getContent() instanceof StatisticsPane, "Actual statistics tab missing");
            StatisticsPane pane = (StatisticsPane) tab.getContent();
            var snapshot = ShipStatisticsV1.snapshot();
            Text total = (Text) value(pane, "total");
            Text quality = (Text) value(pane, "quality");
            PieChart ratio = (PieChart) value(pane, "ratio");
            var average = (StackedBarChart<Double, String>) value(pane, "average");
            var median = (StackedBarChart<Double, String>) value(pane, "median");
            var spectrum = (StackedBarChart<Number, String>) value(pane, "spectrum");
            var bubble = (BubbleChart<Double, Double>) value(pane, "bubble");
            ratio.setAnimated(false);
            average.setAnimated(false);
            median.setAnimated(false);
            spectrum.setAnimated(false);
            bubble.setAnimated(false);
            pane.render(snapshot);
            check("1.0K".equals(total.getText()), "Total changed: " + total.getText());
            check(!quality.isManaged(), "Complete fixture flagged uncertain");
            check(ratio.getData().size() == 2
                    && ratio.getData().stream().mapToDouble(PieChart.Data::getPieValue).sum() == 1000,
                    "Ratio totals changed");
            check(average.getData().size() == 2 && average.getData().getFirst().getData().getFirst().getXValue() == 15,
                    "Mean changed");
            check(median.getData().size() == 2 && median.getData().getFirst().getData().getFirst().getXValue() == 15,
                    "Median changed");
            check(spectrum.getData().stream().flatMap(s -> s.getData().stream())
                    .mapToDouble(d -> d.getXValue().doubleValue()).sum() == 3, "Histogram counts changed");
            check(bubble.getData().size() == 2
                    && ((Number) bubble.getData().getFirst().getData().getFirst().getExtraValue()).doubleValue() == 0.4,
                    "Bubble size changed");
            pane.applyCss();
            pane.layout();
            var image = ((VBox) value(pane, "content")).snapshot(null, null);
            check(image.getWidth() > 0 && image.getHeight() > 0, "Chart snapshot failed");
            ShipCollection.get().getShipMap().get(1).setExp(List.of(9999, 100));
            ShipCollection.get().getShipMap().get(2).setLv(85);
            pane.render(snapshot);
            check("1.0K".equals(total.getText()), "Previously captured values changed");
            pane.update();
            check("10.9K".equals(total.getText()), "Explicit refresh failed");
            check(average.getData().getFirst().getData().getFirst().getXValue() == 47.5, "Refreshed mean failed");
            Ship retained = ShipCollection.get().getShipMap().get(1);
            ShipCollection.get().getShipMap().clear();
            ShipCollection.get().getShipMap().put(1, retained);
            retained.setLv(null);
            retained.setExp(null);
            ShipMstCollection.get().getShipMap().clear();
            pane.update();
            check("未記録".equals(total.getText()) && quality.isManaged() && quality.getText().contains("艦種未分類 1"),
                    "Unknown values silently became zero");
            check(ratio.getData().isEmpty() && average.getData().isEmpty() && median.getData().isEmpty()
                    && spectrum.getData().isEmpty() && bubble.getData().isEmpty(),
                    "Unknown data left fabricated or stale chart values");
        } finally {
            controller.onWindowHidden(new WindowEvent(stage, WindowEvent.WINDOW_HIDDEN));
            stage.close();
            controller.setWindow(null);
        }
    }
}
