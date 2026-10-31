package logbook.internal.updatecheck;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.Test;

class ManagedBundleModeTest {
    @Test
    void managedStartupAndDirectUpdateAreGuardedWithoutStartingJavaFx() throws Exception {
        String old = System.getProperty(ManagedBundleMode.PROPERTY);
        try {
            System.setProperty(ManagedBundleMode.PROPERTY, "true");
            assertTrue(ManagedBundleMode.enabled());
            CheckUpdate.run(true);
            var method = CheckUpdate.class.getDeclaredMethod("launchUpdate", String.class);
            method.setAccessible(true);
            var error = assertThrows(InvocationTargetException.class, () -> method.invoke(null, "25.0.10"));
            assertInstanceOf(IllegalStateException.class, error.getCause());
            System.clearProperty(ManagedBundleMode.PROPERTY);
            assertFalse(ManagedBundleMode.enabled());
            assertDoesNotThrow(ManagedBundleMode::requireLegacyUpdater);
        } finally {
            if (old == null)
                System.clearProperty(ManagedBundleMode.PROPERTY);
            else
                System.setProperty(ManagedBundleMode.PROPERTY, old);
        }
    }
}
