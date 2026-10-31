package logbook.features;
import java.util.List;
import logbook.plugin.api.FeatureModule;
/** Lifecycle/listener module; deliberately adds no main-window menu. */
public final class BridgeFeatures implements FeatureModule {
    public Descriptor descriptor() { return new Descriptor("bridge", "1.0.0", HOST_API_VERSION, List.of()); }
    public List<Feature> features() { return List.of(); }
}
