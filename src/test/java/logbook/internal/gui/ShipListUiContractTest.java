package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.function.Supplier;
import javafx.stage.Stage;
import logbook.bean.DeckPort;
import logbook.plugin.api.FeatureUiV1;
import logbook.plugin.api.ShipListUiV1;
import org.junit.jupiter.api.Test;

class ShipListUiContractTest {
    @Test
    void additiveGuiApiRetainsTheExistingLifecycle() {
        assertEquals(1, ShipListUiV1.version());
        assertEquals(1, FeatureUiV1.version());
        assertTrue(FeatureUiV1.Window.class.isAssignableFrom(ShipController.class));
    }

    @Test
    void existingConstructorsAndPublicLabelExporterRemainAvailable() throws Exception {
        assertNotNull(ShipTablePane.class.getConstructor(DeckPort.class));
        assertNotNull(ShipTablePane.class.getConstructor(Supplier.class, String.class));
        assertNotNull(ShipTablePane.KanmusuList.class.getMethod("copyAll"));
        for (String method : List.of("enable", "disable", "update"))
            assertNotNull(ShipTablePane.class.getMethod(method));
    }

    @Test
    void publicWindowEntryDoesNotExposeMutableShipBeans() throws Exception {
        var method = ShipListUiV1.class.getMethod("open", Stage.class, String.class,
                java.util.function.Consumer.class, java.util.function.Consumer.class);
        assertEquals(void.class, method.getReturnType());
        assertFalse(method.toGenericString().contains("logbook.bean"));
    }
}
