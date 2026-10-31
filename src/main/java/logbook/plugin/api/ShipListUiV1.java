package logbook.plugin.api;

import java.io.IOException;
import java.util.function.Consumer;
import javafx.stage.Stage;

/**
 * Versioned entry to the bundled owned-ship window. UI lives in status, statistics
 * in charts; legacy Ship/ShipItem/filter/export types stay in core for ABI support.
 * Modules still require the exact host digest and the shared application loader.
 * This GUI contract does not claim a new immutable row model or global state lock.
 */
public final class ShipListUiV1 {
    private ShipListUiV1() {
    }

    public static int version() {
        return 1;
    }

    /** Invoke on the JavaFX thread. Opening does not start a game request or install anything. */
    public static void open(Stage owner, String title, Consumer<FeatureUiV1.Window> controller,
            Consumer<Stage> window) throws IOException {
        FeatureUiV1.showWindow("logbook/gui/ship.fxml", owner, title, controller, window);
    }
}
