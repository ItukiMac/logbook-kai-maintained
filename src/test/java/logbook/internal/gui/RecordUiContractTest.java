package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.stage.Stage;
import logbook.plugin.api.FeatureUiV1;
import logbook.plugin.api.RecordViewsV1;
import org.junit.jupiter.api.Test;

class RecordUiContractTest {
    @Test void existingGuiVersionIsRetainedAndRecordContractIsExplicit() {
        assertEquals(1, FeatureUiV1.version());
        assertEquals(1, RecordViewsV1.version());
        assertTrue(FeatureUiV1.Window.class.isAssignableFrom(BattleLogController.class));
        assertTrue(FeatureUiV1.Window.class.isAssignableFrom(MissionLogController.class));
        assertTrue(FeatureUiV1.Window.class.isAssignableFrom(CreateItemController.class));
    }
    @Test void missingComponentIsACheckedFailureRatherThanAnEmptyLoader() {
        assertThrows(IOException.class, () -> FeatureUiV1.componentLoader(getClass(), "/missing-record-component.fxml"));
        assertThrows(IOException.class, () -> FeatureUiV1.componentLoader(getClass(), "relative.fxml"));
    }
    @Test void historicalWindowCannotReadOrRenderFromABackgroundThread() {
        assertThrows(IllegalStateException.class,
                () -> RecordViewsV1.showBattleDetail("2026-10-27 12:00:00", null, "test"));
    }
    @Test void publicRecordMethodDoesNotExposeMutableInternalModels() throws Exception {
        var method = RecordViewsV1.class.getMethod("showBattleDetail", String.class, Stage.class, String.class);
        assertEquals(boolean.class, method.getReturnType());
        assertArrayEquals(new Class<?>[] {IOException.class}, method.getExceptionTypes());
        assertEquals(FXMLLoader.class, FeatureUiV1.class.getMethod("componentLoader", Class.class, String.class).getReturnType());
        assertEquals(Parent.class, FeatureUiV1.class.getMethod("applyGlobalStyles", Parent.class).getReturnType());
    }
}
