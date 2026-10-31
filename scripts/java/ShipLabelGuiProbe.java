package logbook.internal.gui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;
import logbook.bean.*;
import logbook.core.GameDefinitionRuntime;
import logbook.core.LogBookCoreServices;
import logbook.internal.Config;
import logbook.plugin.api.ShipLabelsV1;

/** Tests actual manual-label dialogs and post-confirmation commands on new synthetic state only. */
public final class ShipLabelGuiProbe {
    private static void check(boolean value, String message) {
        if (!value)
            throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 1 && args[0].equals("synthetic-ship-labels-only"), "Explicit fixture mode required");
        check(!Files.exists(Path.of("config")) && !Files.exists(Path.of("history-sentinel.csv")),
                "Refusing existing runtime");
        byte[] original = "synthetic history never edited\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(Path.of("history-sentinel.csv"), original, StandardOpenOption.CREATE_NEW);
        var failures = new CopyOnWriteArrayList<Throwable>();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            failures.add(e);
            e.printStackTrace();
        });
        LogBookCoreServices.init(List.of());
        GameDefinitionRuntime.initialize();
        fixture();
        var ready = new CompletableFuture<Void>();
        Platform.startup(() -> ready.complete(null));
        ready.get(20, TimeUnit.SECONDS);
        Platform.setImplicitExit(false);
        try {
            runFx(ShipLabelGuiProbe::window);
            runFx(() -> {
            });
            check(failures.isEmpty(), "Uncaught GUI failure: " + failures);
            Config.getDefault().store();
            var restored = new Config(Path.of("config")).get(ShipLabelCollection.class, ShipLabelCollection::new);
            check(restored.getLabels().equals(ShipLabelCollection.get().getLabels()),
                    "Original label storage format did not round-trip");
            check(restored.getLabels().get(1).equals(Set.of("first")), "Unexpected first-ship labels persisted");
            check(restored.getLabels().get(2).equals(Set.of("second", "competing")), "Concurrent label was lost");
            check(Arrays.equals(original, Files.readAllBytes(Path.of("history-sentinel.csv"))),
                    "Original history changed");
            System.out.println(
                    "SHIP_LABEL_GUI_OK dialog_cancel_no_change=true dialog_confirm_add_remove=true stale_batch_rejected=true settings_reread=true original_history_unchanged=true production_accessed=false");
        } finally {
            Platform.exit();
        }
    }

    private static void fixture() {
        AppConfig.get().setHideShipImageFromShipTablePane(true);
        AppConfig.get().setHideItemImageFromShipTablePane(true);
        for (int id : List.of(1, 2)) {
            ShipMst master = new ShipMst();
            master.setId(1000000 + id);
            master.setName("synthetic ship " + id);
            master.setSortId(id);
            master.setStype(2);
            master.setCtype(1);
            master.setFuelMax(10);
            master.setBullMax(10);
            ShipMstCollection.get().getShipMap().put(master.getId(), master);
            Ship ship = new Ship();
            ship.setId(id);
            ship.setShipId(master.getId());
            ship.setLv(10);
            ship.setCond(49);
            ship.setExp(List.of(1000, 100));
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
            ship.setLocked(true);
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
            ShipLabelCollection.get().getLabels().put(id, new LinkedHashSet<>(Set.of(id == 1 ? "first" : "second")));
        }
    }

    @SuppressWarnings("unchecked")
    private static void window() throws Exception {
        var pane = new ShipTablePane(() -> new ArrayList<>(ShipCollection.get().getShipMap().values()),
                "synthetic-label-probe");
        Stage stage = new Stage();
        try {
            stage.setScene(new Scene(pane));
            stage.show();
            pane.applyCss();
            pane.layout();
            pane.enable();
            pane.update();
            var field = ShipTablePane.class.getDeclaredField("table");
            field.setAccessible(true);
            TableView<ShipItem> table = (TableView<ShipItem>) field.get(pane);
            check(table.getItems().size() == 2, "Ship table did not initialize its real rows");
            table.getSelectionModel().selectAll();
            queueDialog(stage, false, "cancelled", false);
            pane.addLabel();
            check(ShipLabelCollection.get().getLabels().values().stream().noneMatch(s -> s.contains("cancelled")),
                    "Cancel wrote labels");
            queueDialog(stage, true, "confirmed", false);
            pane.addLabel();
            check(ShipLabelCollection.get().getLabels().values().stream().allMatch(s -> s.contains("confirmed")),
                    "Confirmed dialog did not update both targets");
            check(table.getItems().stream().allMatch(s -> s.getLabel().contains("confirmed")),
                    "Confirmed labels did not refresh the actual table");
            var stale = ShipLabelsV1.capture(List.of(1, 2));
            ShipLabelCollection.get().getLabels().get(2).add("competing");
            var rejected = pane.applyLabelEdit(stale, "rejected", true);
            check(rejected.outcome() == ShipLabelsV1.Outcome.CONFLICT, "Stale confirmation was not rejected");
            check(ShipLabelCollection.get().getLabels().values().stream().noneMatch(s -> s.contains("rejected")),
                    "Partial stale edit changed a prefix");
            table.getSelectionModel().selectAll();
            queueDialog(stage, true, "confirmed", true);
            pane.removeLabel();
            check(ShipLabelCollection.get().getLabels().values().stream().noneMatch(s -> s.contains("confirmed")),
                    "Confirmed removal failed");
            check(table.getItems().stream().noneMatch(s -> s.getLabel().contains("confirmed")),
                    "Removal did not refresh view labels");
            check(ShipCollection.get().getShipMap().values().stream().allMatch(s -> s.getSallyArea() == 0),
                    "Manual command changed game event tags");
        } finally {
            pane.disable();
            stage.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static void queueDialog(Stage owner, boolean confirm, String value, boolean choice) {
        Platform.runLater(() -> {
            for (Window window : List.copyOf(Window.getWindows())) {
                if (window == owner || !window.isShowing() || window.getScene() == null)
                    continue;
                if (window.getScene().getRoot() instanceof DialogPane dialog) {
                    if (choice) {
                        ComboBox<String> input = (ComboBox<String>) dialog.lookup(".combo-box");
                        check(input != null, "Missing removal choices");
                        check(input.getItems().contains(value), "Manual label missing from dialog");
                        input.setValue(value);
                    } else {
                        TextField input = (TextField) dialog.lookup(".text-field");
                        check(input != null, "Missing label editor");
                        input.setText(value);
                    }
                    ((Button) dialog.lookupButton(confirm ? ButtonType.OK : ButtonType.CANCEL)).fire();
                    return;
                }
            }
            throw new AssertionError("Expected real label dialog was not shown");
        });
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    private static void runFx(Action action) throws Exception {
        var done = new CompletableFuture<Void>();
        Platform.runLater(() -> {
            try {
                action.run();
                done.complete(null);
            } catch (Throwable e) {
                done.completeExceptionally(e);
            }
        });
        done.get(35, TimeUnit.SECONDS);
    }
}
