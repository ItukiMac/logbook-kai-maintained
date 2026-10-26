package logbook.core;
import java.util.*;
import java.util.stream.*;
import logbook.plugin.api.FeatureModule;
/** Immutable module catalog. Duplicate IDs and missing resources fail before changing the menu. */
public final class FeatureRegistry {
    private final Map<String,FeatureModule.Feature> features;
    public FeatureRegistry(Stream<FeatureModule> modules,ClassLoader resources) {
        Map<String,FeatureModule.Feature> found=new LinkedHashMap<>();
        modules.forEach(module->{for(var feature:module.features()){
            if(found.putIfAbsent(feature.id(),feature)!=null)throw new IllegalArgumentException("Duplicate feature "+feature.id());
            if(resources.getResource(feature.fxml())==null)throw new IllegalArgumentException("Missing feature resource "+feature.fxml());
        }});
        features=Map.copyOf(found);
    }
    public static FeatureRegistry discover(){return new FeatureRegistry(LogBookCoreServices.getServiceProviders(FeatureModule.class),LogBookCoreServices.getClassLoader());}
    public FeatureModule.Feature get(String id){var feature=features.get(id);if(feature==null)throw new IllegalArgumentException("Unknown feature "+id);return feature;}
    public List<FeatureModule.Feature> group(FeatureModule.Group group){return features.values().stream().filter(f->f.group()==group).sorted(Comparator.comparingInt(FeatureModule.Feature::order).thenComparing(FeatureModule.Feature::id)).toList();}
}
