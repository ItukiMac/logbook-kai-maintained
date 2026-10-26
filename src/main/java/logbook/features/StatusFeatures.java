package logbook.features;
import java.util.List;
import logbook.plugin.api.FeatureModule;
public final class StatusFeatures implements FeatureModule {
    @Override public List<Feature> features() {return List.of(
            new Feature("repair-list", "お風呂に入りたい艦娘", "logbook/gui/require_ndock.fxml", Group.COMMAND, 10, "Ctrl+N"));}
}
