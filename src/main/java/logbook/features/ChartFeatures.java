package logbook.features;

import java.util.List;
import logbook.plugin.api.FeatureModule;

public final class ChartFeatures implements FeatureModule {
    @Override
    public Descriptor descriptor() {
        return new Descriptor("charts", "1.0.0", HOST_API_VERSION, List.of());
    }

    @Override
    public List<Feature> features() {
        return List.of(
                new Feature("resource-chart", "資材チャート", "logbook/gui/resource_chart.fxml", Group.EXTRA, 10, ""),
                new Feature("experience-chart", "経験値チャート", "logbook/gui/exp_chart.fxml", Group.EXTRA, 20, ""));
    }
}
