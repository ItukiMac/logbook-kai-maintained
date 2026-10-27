package logbook.core;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.stream.Stream;
import logbook.plugin.api.FeatureModule;
import org.junit.jupiter.api.Test;
class FeatureRegistryTest {
    @Test void sevenRealBuiltinsAreDiscoveredByPublicSpi(){
        var registry=FeatureRegistry.discover();
        assertEquals(4,registry.group(FeatureModule.Group.CALCULATOR).size());
        assertEquals(2,registry.group(FeatureModule.Group.EXTRA).size());
        assertEquals(1,registry.group(FeatureModule.Group.COMMAND).size());
        assertEquals("logbook/gui/prob_calc.fxml",registry.get("probability").fxml());
        assertThrows(UnsupportedOperationException.class,()->registry.group(FeatureModule.Group.CALCULATOR).clear());
    }
    @Test void duplicateAndMissingFeaturesAreRejectedBeforeMenuChanges(){
        FeatureModule module=()->List.of(new FeatureModule.Feature("test","test","logbook/gui/prob_calc.fxml",FeatureModule.Group.CALCULATOR,1,""));
        assertThrows(IllegalArgumentException.class,()->new FeatureRegistry(Stream.of(module,module),getClass().getClassLoader()));
        FeatureModule missing=()->List.of(new FeatureModule.Feature("missing","test","logbook/gui/no-such.fxml",FeatureModule.Group.CALCULATOR,1,""));
        assertThrows(IllegalArgumentException.class,()->new FeatureRegistry(Stream.of(missing),getClass().getClassLoader()));
    }
    @Test void managedBundleRejectsUnversionedModuleBeforeItCanPublishFeatures(){
        var accessed = new java.util.concurrent.atomic.AtomicBoolean();
        FeatureModule legacy = () -> {
            accessed.set(true);
            return List.of(new FeatureModule.Feature("legacy", "legacy",
                    "logbook/gui/prob_calc.fxml", FeatureModule.Group.CALCULATOR, 1, ""));
        };
        String old = System.getProperty("logbook.runtime.managedBundle");
        System.setProperty("logbook.runtime.managedBundle", "true");
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> new FeatureRegistry(Stream.of(legacy), getClass().getClassLoader()));
            assertFalse(accessed.get(), "incompatible modules must fail before features() is called");
        } finally {
            if (old == null) System.clearProperty("logbook.runtime.managedBundle");
            else System.setProperty("logbook.runtime.managedBundle", old);
        }
    }
    @Test void resourceTraversalIsRejected(){
        assertThrows(IllegalArgumentException.class,()->new FeatureModule.Feature("test","test","logbook/gui/../x.fxml",FeatureModule.Group.CALCULATOR,1,""));
    }
}
