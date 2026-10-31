package logbook.internal.gui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import logbook.bean.*;
import logbook.core.FeatureRegistry;
import logbook.core.LogBookCoreServices;
import logbook.core.GameDefinitionRuntime;
import logbook.plugin.api.FeatureModule;

/** Standalone integration probe: never starts Launcher, a proxy or game traffic.
 * Run with ONLY split JARs plus the probe directory on the application classpath.
 */
public final class FeatureDistributionProbe {
    private static void check(boolean value, String reason) {
        if (!value)
            throw new AssertionError(reason);
    }

    public static void main(String[] args) throws Exception {
        var failures = new CopyOnWriteArrayList<Throwable>();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            failures.add(error);
            error.printStackTrace();
        });
        Files.writeString(Path.of("資材ログ.csv"), "fixture header\n", StandardOpenOption.CREATE_NEW);
        LogBookCoreServices.init(List.of());
        GameDefinitionRuntime.initialize();
        // Synthetic in-memory state only: the experience window expects a flagship.
        Ship ship = new Ship();
        ship.setId(1);
        ship.setShipId(1);
        ship.setLv(1);
        ship.setSortno(1);
        ship.setExp(List.of(0, 100, 0));
        ship.setNowhp(10);
        ship.setMaxhp(10);
        ship.setNdockTime(0);
        ship.setNdockItem(List.of(0, 0));
        ship.setSlot(List.of(-1));
        ship.setSlotEx(0);
        ship.setLocked(true);
        ship.setCond(49);
        ShipCollection.get().getShipMap().put(1, ship);
        ShipMst mst = new ShipMst();
        mst.setId(1);
        mst.setName("synthetic-fixture");
        mst.setAfterlv(0);
        mst.setAftershipid(0);
        mst.setStype(2);
        mst.setSortId(1);
        mst.setSortno(1);
        ShipMstCollection.get().getShipMap().put(1, mst);
        for (int id = 1; id <= 4; id++) {
            DeckPort deck = new DeckPort();
            deck.setId(id);
            deck.setName("fixture-" + id);
            deck.setMission(List.of(0L, 0L, 0L));
            deck.setShip(id == 1 ? List.of(1) : List.of(-1));
            DeckPortCollection.get().getDeckPortMap().put(id, deck);
        }
        var registry = FeatureRegistry.discover();
        var features = new ArrayList<FeatureModule.Feature>();
        for (var group : FeatureModule.Group.values())
            features.addAll(registry.group(group));
        check(features.size() == 7, "Seven actual features must be discovered");
        var ready = new CompletableFuture<Void>();
        Platform.startup(() -> ready.complete(null));
        ready.get(20, TimeUnit.SECONDS);
        Platform.setImplicitExit(false);
        try {
            for (var feature : features)
                load(feature.fxml(), failures);
            load("logbook/gui/calc_exp_area.fxml", failures);
            check(failures.isEmpty(), "Uncaught FX failure: " + failures);
            System.out.println("SPLIT_GUI_OK features=7 windows=8 source=feature-jars only");
        } finally {
            Platform.exit();
        }
    }

    private static void load(String fxml, List<Throwable> failures) throws Exception {
        var done = new CompletableFuture<Void>();
        Platform.runLater(() -> {
            Stage window = new Stage();
            try {
                var loader = InternalFXMLLoader.load(fxml);
                check(loader.getLocation().toString().contains("logbook-kai2-feature-"), "FXML still in core: " + fxml);
                Parent root = loader.load();
                WindowController controller = loader.getController();
                String source = controller.getClass().getProtectionDomain().getCodeSource().getLocation().toString();
                check(source.contains("logbook-kai2-feature-"), "Controller still in core: " + source);
                if (controller instanceof CalcExpSeaAreaEditorController editor)
                    editor.setApply(() -> {
                    });
                controller.initWindow(window);
                window.setScene(new Scene(root));
                window.show();
                root.applyCss();
                root.layout();
                if (fxml.endsWith("prob_calc.fxml")) {
                    ((TextField) loader.getNamespace().get("probInput")).setText("2");
                    check(!((Label) loader.getNamespace().get("inputMessage")).isVisible(), "Probability input failed");
                    check(!((TableView<?>) loader.getNamespace().get("resultTable")).getItems().isEmpty(),
                            "No computed rows");
                }
                System.out.println("SPLIT_WINDOW_OK " + fxml + " " + source);
                window.close();
                // Execute after pending shown/definition refresh callbacks too.
                Platform.runLater(() -> done.complete(null));
            } catch (Throwable failure) {
                window.close();
                done.completeExceptionally(failure);
            }
        });
        done.get(20, TimeUnit.SECONDS);
        check(failures.isEmpty(), "FX callback failure: " + failures);
    }
}
