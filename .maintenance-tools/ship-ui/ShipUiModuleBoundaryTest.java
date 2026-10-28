package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import logbook.plugin.api.FeatureUiV1;
import logbook.plugin.api.ShipTableUiV1;
import org.junit.jupiter.api.Test;

/** Nested classes are checked too; package-private calls fail across loader boundaries. */
class ShipUiModuleBoundaryTest {
    @Test void windowsUsePublicLifecycleAndRemainAssignableToOldTypes() {
        assertEquals(1, ShipTableUiV1.version());
        assertTrue(FeatureUiV1.Window.class.isAssignableFrom(ShipController.class));
        assertTrue(WindowController.class.isAssignableFrom(ShipController.class));
        assertTrue(ShipTableUiV1.Pane.class.isAssignableFrom(ShipTablePane.class));
        assertTrue(javafx.scene.layout.VBox.class.isAssignableFrom(ShipTablePane.class));
    }
    @Test void controllerAndEveryNestedTableClassAvoidPrivateHostGuiHelpers() throws Exception {
        Path directory = Path.of(ShipController.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .resolve("logbook/internal/gui");
        int inspected = 0;
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                String file = path.getFileName().toString();
                if (!(file.equals("ShipController.class") || file.startsWith("ShipController$")
                        || file.equals("ShipTablePane.class") || file.startsWith("ShipTablePane$"))) continue;
                inspected++;
                String constants = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
                for (String forbidden : List.of("TableTool", "Tools$", "InternalFXMLLoader", "WindowController", "ParameterFilterPane"))
                    assertFalse(constants.contains("logbook/internal/gui/" + forbidden), file + " directly links " + forbidden);
            }
        }
        assertTrue(inspected >= 10, "Nested cells/export helpers were not inspected");
    }
    @Test void existingConstructorsAndLifecycleSignaturesRemain() throws Exception {
        assertNotNull(ShipTablePane.class.getConstructor(logbook.bean.DeckPort.class));
        assertNotNull(ShipTablePane.class.getConstructor(java.util.function.Supplier.class, String.class));
        for (String name : List.of("enable", "disable", "update"))
            assertEquals(void.class, ShipTablePane.class.getMethod(name).getReturnType());
        assertNotNull(ShipTablePane.KanmusuList.class.getMethod("copyAll"));
        assertNotNull(ShipTablePane.KanmusuList.class.getMethod("selectionCopy", TableView.class));
    }
    @Test void newWindowOwnerOverloadsPreserveTheOldStageSignatures() throws Exception {
        for (Class<?> owner : List.of(Stage.class, javafx.stage.Window.class)) {
            assertNotNull(FeatureUiV1.Tables.class.getMethod("store", TableView.class, String.class, owner));
            assertNotNull(FeatureUiV1.Controls.class.getMethod("alert", javafx.scene.control.Alert.AlertType.class,
                    String.class, String.class, owner));
        }
    }
}
