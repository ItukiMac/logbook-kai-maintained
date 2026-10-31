package logbook.tools;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.DatePicker;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableView;
import javafx.stage.Stage;
import logbook.bean.AppConfig;
import logbook.bean.AppViewConfig;
import logbook.core.LogBookCoreServices;
import logbook.internal.Config;
import logbook.internal.log.BattleResultLogFormat;
import logbook.internal.log.CreateitemLogFormat;
import logbook.internal.log.LogWriter;
import logbook.internal.log.MissionResultLogFormat;
import logbook.plugin.api.FeatureUiV1;
import logbook.plugin.api.RecordViewsV1;
import logbook.plugin.api.RecordDataV1;

/** Independent process: only synthetic data in a new working directory, never a user profile. */
public final class RecordUiProbe {
    private static final Map<Path, byte[]> ORIGINAL = new LinkedHashMap<>();
    private static int windows;

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 2 && args[0].equals("isolated-record-ui-proof"), "Explicit isolated probe required");
        require(!Files.exists(Path.of("config")) && !Files.exists(Path.of("reports"))
                && !Files.exists(Path.of("battle")), "Refusing an existing runtime");
        for (String name : List.of("BattleLogController", "MissionLogController", "CreateItemController")) {
            try {
                Class.forName("logbook.internal.gui." + name, false, RecordUiProbe.class.getClassLoader());
                throw new AssertionError("Record controller leaked into the host archive: " + name);
            } catch (ClassNotFoundException expected) {
            }
        }
        Path reports = Files.createDirectory(Path.of("reports")).toAbsolutePath();
        Path battle = Files.createDirectory(Path.of("battle")).toAbsolutePath();
        Files.createDirectory(battle.resolve("2026-10"));
        fixture(reports.resolve(new CreateitemLogFormat().fileName()),
                "date,item,type,fuel,ammo,steel,bauxite,secretary\n"
                        + "2026-10-27 12:00:00,synthetic item,synthetic type,10,20,30,40,synthetic secretary\n"
                        + "2026-10-27 12:00:01,,,10,20,30,40,synthetic secretary\n"
                        + "malformed,retained\n");
        fixture(reports.resolve(new MissionResultLogFormat().fileName()),
                "date,result,area,name,fuel,ammo,metal,bauxite\n"
                        + "2026-10-27 12:00:00,成功,synthetic area,synthetic mission,30,20,0,0\n"
                        + "2026-10-27 12:00:01,大成功,synthetic area,synthetic mission,45,30,0,0\n");
        String[] currentBattle = new String[65];
        Arrays.fill(currentBattle, "");
        String[] baseFields = { "2026-10-27 12:00:00", "1-1 synthetic sea", "1", "出撃", "S", "同航戦",
                "単縦陣", "単縦陣", "航空優勢", "", "", "synthetic enemy", "駆逐艦", "synthetic drop" };
        System.arraycopy(baseFields, 0, currentBattle, 0, baseFields.length);
        currentBattle[63] = "120";
        currentBattle[64] = "60";
        String[] historicalBattle = Arrays.copyOf(currentBattle, 14);
        historicalBattle[0] = "2026-10-27 12:00:01";
        String[] malformedExperience = currentBattle.clone();
        malformedExperience[0] = "2026-10-27 12:00:02";
        malformedExperience[64] = "bad-exp";
        fixture(reports.resolve(new BattleResultLogFormat().fileName()),
                "synthetic battle header\n" + String.join(",", currentBattle) + "\n"
                        + String.join(",", historicalBattle) + "\n" + String.join(",", malformedExperience) + "\n");
        fixture(battle.resolve("2026-10/2026-10-27 12-34-56.json"), "{broken historical record\n");
        LogBookCoreServices.init(List.of());
        AppConfig.get().setReportPath(reports.toString());
        AppConfig.get().setBattleLogDir(battle.toString());
        var snapshot = RecordDataV1.battles();
        require(snapshot.size() == 3, "Immutable snapshot lost historical rows");
        try {
            snapshot.clear();
            throw new AssertionError("Mutable battle snapshot");
        } catch (UnsupportedOperationException expected) {
        }
        var periods = RecordDataV1.recentBattles();
        try {
            periods.clear();
            throw new AssertionError("Mutable period snapshot");
        } catch (UnsupportedOperationException expected) {
        }
        for (var rows : periods.values()) {
            try {
                rows.clear();
                throw new AssertionError("Mutable period rows");
            } catch (UnsupportedOperationException expected) {
            }
        }
        require(RecordDataV1.missions().size() == 2 && RecordDataV1.developments().size() == 2,
                "Record service results changed");
        var cutoff = java.time.ZonedDateTime.of(2026, 10, 27, 12, 0, 0, 0, java.time.ZoneId.of("Asia/Tokyo"));
        require(RecordDataV1.missionsSince(cutoff.minusSeconds(1)).size() == 2, "Before boundary lost rows");
        require(RecordDataV1.missionsSince(cutoff).size() == 1, "Exclusive query included equal instant");
        require(RecordDataV1.missionsSince(cutoff.plusSeconds(1)).isEmpty(), "After boundary retained old rows");
        for (var rows : java.util.List.of(RecordDataV1.missionsSince(cutoff), RecordDataV1.developments())) {
            try {
                rows.clear();
                throw new AssertionError("Mutable mission/development snapshot");
            } catch (UnsupportedOperationException expected) {
            }
        }
        try (URLClassLoader featureLoader = new URLClassLoader(
                new java.net.URL[] { Path.of(args[1]).toUri().toURL() }, RecordUiProbe.class.getClassLoader())) {
            FutureTask<Void> task = new FutureTask<>(() -> {
                Platform.setImplicitExit(false);
                open(featureLoader, "missionlog", "MissionLogController", 2);
                open(featureLoader, "createitemlog", "CreateItemController", 2);
                Object battleController = open(featureLoader, "battlelog", "BattleLogController", 3);
                Class<?> dialogType = featureLoader.loadClass("logbook.internal.gui.BattleLogController$UnitDialog");
                var constructor = dialogType.getDeclaredConstructor(battleController.getClass());
                constructor.setAccessible(true);
                Object dialog = constructor.newInstance(battleController);
                require(dialog instanceof DialogPane, "Custom-root dialog missing");
                int dates = 0;
                for (var field : dialogType.getDeclaredFields()) {
                    if (field.getType() == DatePicker.class) {
                        field.setAccessible(true);
                        require(field.get(dialog) != null, "Component FXML failed to inject " + field.getName());
                        dates++;
                    }
                }
                require(dates == 2, "Date-range component was not fully loaded");
                require(FeatureUiV1.componentLoader(dialogType, "/logbook/gui/battlelog_dialog.fxml")
                        .getClassLoader() == featureLoader, "Nested component fell back to the host loader");
                require(FeatureUiV1.applyGlobalStyles((Parent) dialog) == dialog,
                        "Global styles changed root identity");
                require(!RecordViewsV1.showBattleDetail("2026-10-27 00:00:00", null, "missing"),
                        "Missing record opened");
                require(!RecordViewsV1.showBattleDetail("2026-10-27 12:34:56", null, "corrupt"),
                        "Corrupt record opened");
                for (String invalid : new String[] { null, "../../record", "2026-02-30 12:00:00",
                        "2026-10-27 24:00:00" }) {
                    try {
                        RecordViewsV1.showBattleDetail(invalid, null, "invalid");
                        throw new AssertionError("Invalid timestamp accepted: " + invalid);
                    } catch (IllegalArgumentException expected) {
                    }
                }
                require(AppViewConfig.get().getCreateItemLogConfig().getIndex() == 1,
                        "Recipe mode not saved under existing setting");
                Config.getDefault().store();
                var restored = new Config(Path.of("config")).get(AppViewConfig.class, AppViewConfig::new);
                require(restored.getCreateItemLogConfig().getIndex() == 1, "Existing view setting did not round-trip");
                return null;
            });
            Platform.startup(() -> Platform.runLater(task));
            try {
                task.get(45, TimeUnit.SECONDS);
            } finally {
                Platform.exit();
            }
        } finally {
            LogBookCoreServices.closeContainer();
        }
        for (var entry : ORIGINAL.entrySet())
            require(Arrays.equals(entry.getValue(), Files.readAllBytes(entry.getKey())),
                    "Original history changed: " + entry.getKey());
        require(windows == 3, "Not all windows were exercised");
        System.out.println(
                "RECORD_UI_ISOLATION_OK windows=3 battle_rows=3 battle_exp=60 mission_fuel=75 development_rows=2 nested_dialog=true settings_reread=true original_history_unchanged=true production_accessed=false");
    }

    private static void fixture(Path path, String text) throws IOException {
        byte[] bytes = text.getBytes(LogWriter.DEFAULT_CHARSET);
        Files.write(path, bytes, java.nio.file.StandardOpenOption.CREATE_NEW);
        ORIGINAL.put(path, bytes);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Object open(ClassLoader loader, String resource, String name, int expectedRows) throws Exception {
        FXMLLoader fxml = new FXMLLoader(loader.getResource("logbook/gui/" + resource + ".fxml"));
        fxml.setClassLoader(loader);
        Parent root = fxml.load();
        Object value = fxml.getController();
        require(value.getClass().getName().equals("logbook.internal.gui." + name),
                "Persisted controller identity changed");
        require(value.getClass().getClassLoader() == loader, "Controller did not cross the runtime-package boundary");
        require(value instanceof FeatureUiV1.Window, "Public window lifecycle not used");
        var controller = (FeatureUiV1.Window) value;
        Stage stage = new Stage();
        controller.bindWindow(stage);
        try {
            stage.setScene(new Scene(root));
            stage.show();
            root.applyCss();
            root.layout();
            TableView<?> detail = (TableView<?>) fxml.getNamespace().get("detail");
            TreeTableView<?> collect = (TreeTableView<?>) fxml.getNamespace().get("collect");
            require(detail != null && !detail.getColumns().isEmpty(), "Detail table initialization was swallowed");
            require(collect != null && collect.getRoot() != null, "Collection initialization was swallowed");
            require(detail.getItems() instanceof javafx.collections.transformation.SortedList,
                    "Detail sorting was not initialized");
            if (resource.equals("createitemlog")) {
                ((ToggleButton) fxml.getNamespace().get("buttonRecipeItem")).fire();
                ((TreeTableView) collect).getSelectionModel().select(collect.getRoot());
            } else if (expectedRows > 0) {
                ArrayDeque<TreeItem<?>> nodes = new ArrayDeque<>();
                nodes.add(collect.getRoot());
                while (!nodes.isEmpty() && detail.getItems().size() != expectedRows) {
                    TreeItem<?> node = nodes.removeFirst();
                    nodes.addAll(node.getChildren());
                    if (node != collect.getRoot())
                        ((TreeTableView) collect).getSelectionModel().select(node);
                }
            }
            require(detail.getItems().size() == expectedRows,
                    name + " has wrong detail row count: " + detail.getItems().size());
            invoke(value, "selectAllDetail");
            require(detail.getSelectionModel().getSelectedItems().size() == expectedRows, "Selection contract changed");
            if (resource.equals("missionlog")) {
                TableColumn<Object, Number> fuel = (TableColumn<Object, Number>) fxml.getNamespace().get("fuel");
                int total = detail.getItems().stream()
                        .mapToInt(row -> fuel.getCellObservableValue(row).getValue().intValue()).sum();
                require(total == 75, "Mission values changed across the GUI boundary");
            }
            if (resource.equals("createitemlog")) {
                TableColumn<Object, String> item = (TableColumn<Object, String>) fxml.getNamespace().get("item");
                require(detail.getItems().stream()
                        .anyMatch(row -> "synthetic item".equals(item.getCellObservableValue(row).getValue())),
                        "Development success/failed rows were lost");
            }
            if (resource.equals("battlelog")) {
                TableColumn<Object, String> rank = (TableColumn<Object, String>) fxml.getNamespace().get("rank");
                require("S".equals(rank.getCellObservableValue(detail.getItems().getFirst()).getValue()),
                        "Battle rank changed");
                TableColumn<Object, Number> exp = (TableColumn<Object, Number>) fxml.getNamespace().get("exp");
                require(detail.getItems().stream().filter(row -> exp.getCellObservableValue(row).getValue() != null)
                        .mapToInt(row -> exp.getCellObservableValue(row).getValue().intValue()).sum() == 60,
                        "Missing experience became a number");
                require(detail.getItems().stream().filter(row -> exp.getCellObservableValue(row).getValue() == null)
                        .count() == 2,
                        "Old/malformed experience must stay unknown");
                require(detail.getItems().stream().anyMatch(row -> row.toString().endsWith("未記録\t未記録")),
                        "Copy hides missing values");
                require(detail.getItems().stream().anyMatch(row -> row.toString().endsWith("120\t不正")),
                        "Copy hides invalid values");
                javafx.scene.control.ChoiceBox<String> grouping = (javafx.scene.control.ChoiceBox<String>) fxml
                        .getNamespace().get("aggregateType");
                grouping.getSelectionModel().select(FeatureUiV1.Tables.columnName(exp));
                TableView<?> aggregate = (TableView<?>) fxml.getNamespace().get("aggregate");
                require(aggregate.getItems().size() == 3, "Grouping merged unknown/invalid/recorded values");
                ((TableView) detail).getSortOrder().add(exp);
                detail.sort();
                require(detail.getItems().size() == 3, "Sorting dropped legacy records");
                ((TableView) detail).scrollToColumn(exp);
                root.applyCss();
                root.layout();
                var labels = root.lookupAll(".table-cell").stream()
                        .filter(n -> n instanceof javafx.scene.control.Labeled)
                        .map(n -> ((javafx.scene.control.Labeled) n).getText()).toList();
                require(labels.contains("未記録") && labels.contains("不正"), "Actual table cells lost unknown labels");
            }
            windows++;
            return controller;
        } finally {
            stage.close();
            controller.setWindow(null);
        }
    }

    private static void invoke(Object target, String name) throws Exception {
        Method method = Arrays.stream(target.getClass().getDeclaredMethods()).filter(m -> m.getName().equals(name))
                .findFirst().orElseThrow();
        method.setAccessible(true);
        if (method.getParameterCount() == 0)
            method.invoke(target);
        else
            method.invoke(target, new javafx.event.ActionEvent());
    }
}
