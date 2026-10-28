package logbook.plugin.api;

import java.util.function.Predicate;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.scene.layout.VBox;
import logbook.bean.ParameterFilterConfig;
import logbook.internal.gui.ShipItem;
import logbook.internal.gui.ShipUiBridgeV1;

/**
 * Versioned GUI lifecycle for the bundled ship table. Legacy ShipItem and
 * ParameterFilterConfig remain explicit compatibility types, not immutable data
 * contracts. Bundles require the exact host digest; arbitrary-core ABI or an
 * atomic game-state snapshot is not promised by this adapter.
 */
public final class ShipTableUiV1 {
    private ShipTableUiV1() { }
    public static int version() { return 1; }

    /** Existing public pane lifecycle; call from the JavaFX application thread. */
    public abstract static class Pane extends VBox {
        public abstract void enable();
        public abstract void disable();
        public abstract void update();
    }

    /** Host-created legacy filter widget, with no package-private class in its signature. */
    public abstract static class ParameterFilter extends VBox {
        public abstract ReadOnlyObjectProperty<Predicate<ShipItem>> filterProperty();
        public abstract void loadConfig(ParameterFilterConfig config);
        public abstract ParameterFilterConfig saveConfig();
    }

    public static ParameterFilter parameterFilter() {
        return ShipUiBridgeV1.parameterFilter();
    }
}
