package logbook.internal.gui;

import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TreeTableView;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

/** Shared presentation for host and feature windows; never changes table data. */
final class WindowPresentation {
    private static final String INSTALLED = "logbook-kai2.presentation";

    private WindowPresentation() {
    }

    static void install(Stage stage) {
        if (stage.getProperties().putIfAbsent(INSTALLED, Boolean.TRUE) != null) {
            return;
        }
        stage.addEventHandler(WindowEvent.WINDOW_SHOWN, event -> {
            if (stage.getScene() != null) {
                apply(stage.getScene().getRoot());
            }
        });
    }

    private static void apply(Parent root) {
        root.applyCss();
        for (var node : root.lookupAll(".table-view")) {
            if (node instanceof TableView<?> table && (table.getPlaceholder() == null
                    || table.getPlaceholder() instanceof Label label
                            && "No content in table".equals(label.getText()))) {
                table.setPlaceholder(new Label("表示するデータはありません。接続・取得状況と絞り込み条件を確認してください。"));
            }
        }
        for (var node : root.lookupAll(".tree-table-view")) {
            if (node instanceof TreeTableView<?> table && table.getPlaceholder() == null) {
                table.setPlaceholder(new Label("表示するデータはありません。"));
            }
        }
        for (var node : root.lookupAll(".tab-pane")) {
            if (node instanceof TabPane tabs && tabs.getProperties().putIfAbsent(INSTALLED, Boolean.TRUE) == null) {
                tabs.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
                    if (selected != null && selected.getContent() instanceof Parent content) {
                        javafx.application.Platform.runLater(() -> apply(content));
                    }
                });
            }
        }
    }
}
