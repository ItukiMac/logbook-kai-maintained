package logbook.internal.gui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TreeTableView;
import javafx.stage.Stage;
import logbook.bean.*;
import logbook.core.GameDefinitionRuntime;
import logbook.core.LogBookCoreServices;
import logbook.internal.Config;
import logbook.plugin.api.FeatureUiV1;

/** Actual base/support windows, using split JARs and a synthetic disposable runtime. */
public final class SupportFeatureProbe {
    private static void check(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 1 && "synthetic-disposable-runtime".equals(args[0]), "Explicit fixture mode required");
        check(!Files.exists(Path.of("config")), "Only a new runtime is accepted");
        byte[] history = "retained synthetic history\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(Path.of("history-sentinel.csv"), history, StandardOpenOption.CREATE_NEW);
        var failures = new CopyOnWriteArrayList<Throwable>();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            failures.add(error);
            error.printStackTrace();
        });
        LogBookCoreServices.init(List.of());
        GameDefinitionRuntime.initialize();
        fixture();
        var ready = new CompletableFuture<Void>();
        Platform.startup(() -> ready.complete(null));
        ready.get(20, TimeUnit.SECONDS);
        Platform.setImplicitExit(false);
        try {
            runFx(() -> airbase());
            runFx(() -> useitems());
            runFx(() -> {
            }); // Observe queued lifecycle callbacks before persisting.
            check(failures.isEmpty(), "Uncaught FX failure: " + failures);
            Config.getDefault().store();
            check(Files.isRegularFile(Path.of("config/logbook.bean.AppConfig.json")), "Settings were not saved");
            AppConfig reread = new Config(Path.of("config")).get(AppConfig.class, AppConfig::new);
            for (String key : List.of("class logbook.internal.gui.AirBaseController#planeTable",
                    "class logbook.internal.gui.UseitemController#table")) {
                Map<String, Double> expected = AppConfig.get().getColumnWidthMap().get(key);
                check(expected != null && !expected.isEmpty(), "No GUI setting changes for " + key);
                check(expected.equals(reread.getColumnWidthMap().get(key)), "Setting round trip changed " + key);
            }
            check(java.util.Arrays.equals(history, Files.readAllBytes(Path.of("history-sentinel.csv"))),
                    "History changed");
            System.out.println(
                    "SPLIT_SUPPORT_GUI_SAVE_OK windows=2 airbase=42 item-count=12 settings-reread=2 history=retained");
        } finally {
            Platform.exit();
        }
    }

    private static void fixture() {
        Maparea area = new Maparea();
        area.setId(1);
        area.setName("synthetic area");
        area.setType(0);
        MapareaCollection.get().getMaparea().put(1, area);
        SlotitemMst master = new SlotitemMst();
        master.setId(7);
        master.setName("synthetic fighter");
        master.setType(List.of(0, 0, 6, 0, 0));
        master.setTyku(10);
        master.setHouk(0);
        master.setHoum(0);
        SlotitemMstCollection.get().getSlotitemMap().put(7, master);
        SlotItem item = new SlotItem();
        item.setId(11);
        item.setSlotitemId(7);
        item.setLevel(0);
        item.setAlv(0);
        SlotItemCollection.get().getSlotitemMap().put(11, item);
        Mapinfo.PlaneInfo plane = new Mapinfo.PlaneInfo();
        plane.setSquadronId(1);
        plane.setSlotid(11);
        plane.setState(1);
        plane.setCount(18);
        plane.setMaxCount(18);
        plane.setCond(1);
        Mapinfo.Distance distance = new Mapinfo.Distance();
        distance.setBase(3);
        distance.setBonus(2);
        Mapinfo.AirBase base = new Mapinfo.AirBase();
        base.setAreaId(1);
        base.setRid(1);
        base.setName("synthetic base");
        base.setActionKind(1);
        base.setDistance(distance);
        base.setPlaneInfo(List.of(plane));
        Mapinfo.get().setAirBase(List.of(base));
        for (int id : List.of(10000, 10001)) {
            UseitemMst use = new UseitemMst();
            use.setId(id);
            use.setName("synthetic item " + id);
            use.setDescription(List.of("fixture description"));
            UseitemMstCollection.get().getUseitemMap().put(id, use);
        }
        Useitem owned = new Useitem();
        owned.setId(10000);
        owned.setCount(12);
        UseitemCollection.get().getUseitemMap().put(10000, owned);
    }

    private static FXMLLoader load(String fxml, Stage stage) throws Exception {
        String module = fxml.equals("require_ndock") ? "ships" : "equipment";
        FXMLLoader loader = InternalFXMLLoader.load("logbook/gui/" + fxml + ".fxml");
        check(loader.getLocation().toString().contains("logbook-kai2-feature-" + module + ".jar!/"),
                "FXML remains in core: " + fxml);
        Parent root = loader.load();
        FeatureUiV1.Window controller = loader.getController();
        check(controller.getClass().getProtectionDomain().getCodeSource().getLocation().toString()
                .endsWith("logbook-kai2-feature-" + module + ".jar"), "Controller remains in core: " + fxml);
        controller.bindWindow(stage);
        stage.setScene(new Scene(root));
        stage.show();
        root.applyCss();
        root.layout();
        return loader;
    }

    @SuppressWarnings("unchecked")
    private static void airbase() throws Exception {
        Stage stage = new Stage();
        try {
            FXMLLoader loader = load("airbase", stage);
            TreeTableView<AirBaseController.AreaTable> areas = (TreeTableView<AirBaseController.AreaTable>) loader
                    .getNamespace().get("areaTable");
            var row = areas.getRoot().getChildren().getFirst().getChildren().getFirst().getChildren().getFirst();
            check("42".equals(row.getValue().seikuProperty().get()), "Expected real air superiority calculation 42");
            check("5".equals(row.getValue().distanceProperty().get()), "Expected base/bonus radius sum 5");
            areas.getSelectionModel().select(row);
            check("42".equals(((Label) loader.getNamespace().get("seikuTotal")).getText()),
                    "Selection did not recalculate summary");
            check("126".equals(((Label) loader.getNamespace().get("lesser")).getText()),
                    "Air superiority threshold changed");
            TableView<?> planes = (TableView<?>) loader.getNamespace().get("planeTable");
            check(planes.getItems().size() == 1, "Plane rows were not populated");
            ((TableColumn<?, ?>) loader.getNamespace().get("count")).setPrefWidth(91);
            stage.getScene().getRoot().layout();
        } finally {
            stage.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static void useitems() throws Exception {
        Stage stage = new Stage();
        try {
            FXMLLoader loader = load("useitem", stage);
            TableView<UseitemItem> table = (TableView<UseitemItem>) loader.getNamespace().get("table");
            check(table.getItems().size() == 1 && table.getItems().getFirst().getCount() == 12,
                    "Inventory count/filter failed");
            check(UseitemItem.class.getProtectionDomain().getCodeSource().getLocation().toString()
                    .endsWith("logbook-kai2-feature-equipment.jar"), "Owned item row remains in core");
            ((CheckBox) loader.getNamespace().get("includeEmpty")).fire();
            check(table.getItems().size() == 2, "Include-empty action did not update rows");
            FeatureUiV1.Tables.selectAll(table);
            check(table.getSelectionModel().getSelectedItems().size() == 2, "Public table selection contract failed");
            ((TableColumn<?, ?>) loader.getNamespace().get("count")).setPrefWidth(123);
            stage.getScene().getRoot().layout();
        } finally {
            stage.close();
        }
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }

    private static void runFx(CheckedAction action) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                action.run();
                done.complete(null);
            } catch (Throwable ex) {
                done.completeExceptionally(ex);
            }
        });
        done.get(20, TimeUnit.SECONDS);
    }
}
