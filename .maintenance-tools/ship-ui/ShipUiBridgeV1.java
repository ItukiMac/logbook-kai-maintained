package logbook.internal.gui;

import java.util.function.Predicate;
import javafx.beans.property.ReadOnlyObjectProperty;
import logbook.bean.ParameterFilterConfig;
import logbook.plugin.api.ShipTableUiV1;

/** Host adapter: keep shared legacy filter implementations inside the core. */
public final class ShipUiBridgeV1 {
    private ShipUiBridgeV1() { }

    public static ShipTableUiV1.ParameterFilter parameterFilter() {
        var delegate = new ParameterFilterPane.ShipItemParameterFilterPane();
        return new ShipTableUiV1.ParameterFilter() {
            { getChildren().add(delegate); }
            @Override public ReadOnlyObjectProperty<Predicate<ShipItem>> filterProperty() {
                return delegate.filterProperty();
            }
            @Override public void loadConfig(ParameterFilterConfig config) { delegate.loadConfig(config); }
            @Override public ParameterFilterConfig saveConfig() { return delegate.saveConfig(); }
        };
    }
}
