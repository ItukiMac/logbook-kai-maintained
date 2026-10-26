package logbook.plugin.api;
import java.util.List;
import java.util.Objects;
/** Additive declarative window-feature SPI; legacy Main*Menu interfaces remain supported. */
public interface FeatureModule {
    List<Feature> features();
    enum Group { COMMAND, CALCULATOR, EXTRA }
    record Feature(String id,String title,String fxml,Group group,int order,String accelerator) {
        public Feature {
            Objects.requireNonNull(title);Objects.requireNonNull(group);
            if(id==null || !id.matches("[a-z0-9][a-z0-9._-]{0,127}"))throw new IllegalArgumentException("Feature ID");
            if(fxml==null || !fxml.startsWith("logbook/gui/") || !fxml.endsWith(".fxml") || fxml.contains("..") || fxml.contains("\\"))throw new IllegalArgumentException("Feature resource");
        }
    }
}
