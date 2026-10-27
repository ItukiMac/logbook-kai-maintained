package logbook.plugin.api;

import java.io.IOException;
import java.util.function.Consumer;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.util.Callback;
import logbook.internal.gui.FeatureUiBridgeV1;
import logbook.internal.gui.WindowController;

/**
 * Version 1 of the GUI contract used by the bundled feature modules.
 *
 * <p>Keep the signatures and window lifecycle of this class binary compatible.
 * An incompatible contract must get a new versioned type and host API version.
 * This adapter does not promise compatibility for unrelated legacy bean/model
 * APIs; bundles must also declare the exact compatible host artifact.</p>
 */
public final class FeatureUiV1 {
    private FeatureUiV1() { }

    public static int version() { return 1; }

    /** Public lifecycle base; existing WindowController plugins remain supported. */
    public abstract static class Window extends WindowController {
        /** Bind the usual close/hidden/definition-refresh lifecycle to a window. */
        public final void bindWindow(Stage stage) {
            FeatureUiBridgeV1.bindWindow(this, stage);
        }
    }

    /** Open a bundled feature dialog with the host's styles and geometry handling. */
    public static void showWindow(String fxml, Stage owner, String title,
            Consumer<Window> controller, Consumer<Stage> window) throws IOException {
        FeatureUiBridgeV1.showWindow(fxml, owner, title, controller, window);
    }

    /** Table helpers keep the existing persisted keys and shortcuts. */
    public static final class Tables {
        private Tables() { }

        public static void setVisible(TableView<?> table, String key) {
            FeatureUiBridgeV1.restoreTable(table, key);
        }

        public static void selectionCopy(TableView<?> table) {
            FeatureUiBridgeV1.selectionCopy(table);
        }

        public static void selectAll(TableView<?> table) {
            FeatureUiBridgeV1.selectAll(table);
        }

        public static void defaultOnKeyPressedHandler(KeyEvent event) {
            FeatureUiBridgeV1.tableKeyPressed(event);
        }

        public static void showVisibleSetting(TableView<?> table, String key, Stage owner) throws IOException {
            FeatureUiBridgeV1.showVisibleSetting(table, key, owner);
        }

        public static <T> Callback<TableColumn<T, Integer>, TableCell<T, Integer>> getRowCountCellFactory() {
            return FeatureUiBridgeV1.rowCountCellFactory();
        }

        public static <T> Callback<TableColumn<T, Integer>, TableCell<T, Integer>> createIntegerFormattedCellFactory() {
            return FeatureUiBridgeV1.integerCellFactory();
        }
    }

    public static final class Controls {
        private Controls() { }

        public static void setSplitWidth(SplitPane pane, String key) {
            FeatureUiBridgeV1.restoreSplit(pane, key);
        }

        public static ImageView zoomImage(ImageView view) {
            return FeatureUiBridgeV1.zoomImage(view);
        }
    }
}
