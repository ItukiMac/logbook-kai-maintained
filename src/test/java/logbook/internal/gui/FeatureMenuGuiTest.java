package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.*;
import javafx.scene.input.KeyCombination;
import logbook.core.GameDefinitionRuntime;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "logbook.gui.tests", matches = "true")
@Timeout(20)
class FeatureMenuGuiTest {
    @BeforeAll
    static void start() throws Exception {
        FxTestSupport.start();
    }

    @Test
    void actualMainMenuContainsAllModularFeaturesOnceAndPreservesShortcuts() throws Exception {
        FxTestSupport.fx(() -> {
            var loader = new FXMLLoader(getClass().getResource("/logbook/gui/main_menu.fxml"));
            MenuBar bar = loader.load();
            var items = bar.getMenus().stream().flatMap(m -> m.getItems().stream())
                    .filter(i -> i.getId() != null && i.getId().startsWith("logbook.feature.")).toList();
            assertEquals(7, items.size());
            assertEquals(7, items.stream().map(MenuItem::getId).distinct().count());
            Menu calc = (Menu) loader.getNamespace().get("calc");
            assertEquals(4, calc.getItems().stream()
                    .filter(i -> i.getId() != null && i.getId().startsWith("logbook.feature.")).count());
            assertEquals(KeyCombination.keyCombination("Ctrl+C"),
                    items.stream().filter(i -> "logbook.feature.experience".equals(i.getId())).findFirst().orElseThrow()
                            .getAccelerator());
            assertTrue(bar.getMenus().stream().flatMap(m -> m.getItems().stream())
                    .anyMatch(i -> "logbook.reload-definitions".equals(i.getId())));
            return null;
        });
    }
}
