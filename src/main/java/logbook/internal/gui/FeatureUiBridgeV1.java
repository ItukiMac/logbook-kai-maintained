package logbook.internal.gui;

import java.io.IOException;
import java.text.NumberFormat;
import java.util.function.Consumer;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.util.Callback;
import logbook.plugin.api.FeatureUiV1;

/** Host implementation of the public FeatureUiV1 contract; stays in the core JAR. */
public final class FeatureUiBridgeV1 {
    private FeatureUiBridgeV1() { }

    public static void bindWindow(FeatureUiV1.Window controller, Stage stage) {
        ((WindowController) controller).initWindow(stage);
    }

    public static void showWindow(String fxml, Stage owner, String title,
            Consumer<FeatureUiV1.Window> controller, Consumer<Stage> window) throws IOException {
        InternalFXMLLoader.showWindow(fxml, owner, title,
                controller == null ? null : value -> controller.accept((FeatureUiV1.Window) value), window);
    }

    public static void restoreTable(TableView<?> table, String key) { TableTool.setVisible(table, key); }
    public static void selectionCopy(TableView<?> table) { TableTool.selectionCopy(table); }
    public static void selectAll(TableView<?> table) { TableTool.selectAll(table); }
    public static void tableKeyPressed(KeyEvent event) { TableTool.defaultOnKeyPressedHandler(event); }

    public static void showVisibleSetting(TableView<?> table, String key, Stage owner) throws IOException {
        TableTool.showVisibleSetting(table, key, owner);
    }

    public static <T> Callback<TableColumn<T, Integer>, TableCell<T, Integer>> rowCountCellFactory() {
        return TableTool.getRowCountCellFactory();
    }

    /** Generic on purpose: the host never needs a feature's UseitemItem row class. */
    public static <T> Callback<TableColumn<T, Integer>, TableCell<T, Integer>> integerCellFactory() {
        NumberFormat format = NumberFormat.getIntegerInstance();
        return column -> new TableCell<>() {
            @Override protected void updateItem(Integer item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : format.format(item));
            }
        };
    }

    public static void restoreSplit(SplitPane pane, String key) { Tools.Controls.setSplitWidth(pane, key); }
    public static ImageView zoomImage(ImageView view) { return Tools.Controls.zoomImage(view); }
}
