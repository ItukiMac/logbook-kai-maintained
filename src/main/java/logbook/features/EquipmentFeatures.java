package logbook.features;

import java.util.List;
import logbook.plugin.api.FeatureModule;

public final class EquipmentFeatures implements FeatureModule {
    public Descriptor descriptor() {
        return new Descriptor("equipment", "1.0.0", HOST_API_VERSION, List.of());
    }

    public List<Feature> features() {
        return List.of();
    }
}
