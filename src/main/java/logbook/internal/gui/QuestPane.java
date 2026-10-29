package logbook.internal.gui;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Arc;
import javafx.stage.Stage;
import logbook.bean.AppQuest;
import logbook.plugin.api.QuestListV1.Quest;
import logbook.core.QuestListReadService;
import logbook.core.LogBookCoreServices;
import logbook.internal.ThreadManager;
import logbook.internal.logger.LoggerHolder;

/**
 * 任務
 *
 */
public class QuestPane extends HBox {

    private final Quest display;
    private final logbook.plugin.api.QuestLocalFilesV1.Note localNote;
    private DefinitionUiBinding definitionBinding;
    private final javafx.beans.value.ChangeListener<javafx.stage.Window> definitionWindowListener = (o,a,b) -> attachDefinitionWindow(b);
    private void attachDefinitionWindow(javafx.stage.Window window) {
        if (definitionBinding != null) definitionBinding.close();
        definitionBinding = window == null ? null : new DefinitionUiBinding(window, this::refreshDefinitionAvailability);
    }
    private void refreshDefinitionAvailability() {
        try (var scope = LogBookCoreServices.pinDefinitions()) {
            int id = this.display.no();
            this.condition.setVisible(LogBookCoreServices.getQuestResource(id,true) != null
                || LogBookCoreServices.getQuestResource(id,false) != null || LogBookCoreServices.getCustomQuestResource(id) != null);
            getProperties().put("definitionGeneration", scope.snapshot().generation());
        }
    }

    @FXML
    private Arc progress;

    @FXML
    private Hyperlink name;

    @FXML
    private Hyperlink condition;

    @FXML
    private Hyperlink searchLink;

    @FXML
    private VBox detailView;

    @FXML
    private VBox infomation;

    @FXML
    private Label detail;

    /**
     * 任務のコンストラクタ
     *
     * @param quest 任務
     */
    public QuestPane(AppQuest quest) {
        this(QuestListReadService.copy(quest));
    }

    /** Display an already captured immutable row; retain the legacy constructor above. */
    public QuestPane(Quest quest) {
        this(quest, Integer.valueOf(6).equals(quest.category()) || Integer.valueOf(11).equals(quest.category())
                ? logbook.plugin.api.QuestLocalFilesV1.note(quest.no()) : null);
    }

    /** Render the captured note; do not reopen its file during control construction. */
    public QuestPane(Quest quest, logbook.plugin.api.QuestLocalFilesV1.Note note) {
        this.display = java.util.Objects.requireNonNull(quest);
        if (note != null && note.questId() != quest.no()) throw new IllegalArgumentException("Note identity mismatch");
        this.localNote = note;
        try {
            FXMLLoader loader = InternalFXMLLoader.load("logbook/gui/quest.fxml");
            loader.setRoot(this);
            loader.setController(this);
            loader.load();
        } catch (IOException e) {
            LoggerHolder.get().error("FXMLのロードに失敗しました", e);
        }
    }

    @FXML
    void initialize() {
        try {
            // 詳細は初期表示しない
            this.detailView.managedProperty().bind(this.detailView.visibleProperty());
            this.detailView.setVisible(false);

            Quest quest = this.display;

            switch (quest.category() == null ? 0 : quest.category()) {
                case 1:
                    this.getStyleClass().add("deck");
                    break;
                case 2:
                case 8:
                case 9:
                    this.getStyleClass().add("sortie");
                    break;
                case 3:
                    this.getStyleClass().add("practice");
                    break;
                case 4:
                    this.getStyleClass().add("mission");
                    break;
                case 5:
                    this.getStyleClass().add("supply");
                    break;
                case 6:
                    this.getStyleClass().add("kousyou");
                    break;
                case 7:
                    this.getStyleClass().add("kaisou");
                    break;
                default:
                    break;
            }
            if (!this.display.active()) {
                this.getStyleClass().add("inactive");
            }
            switch (quest.progressFlag() == null ? 0 : quest.progressFlag()) {
                case 1:
                    this.progress.setStartAngle(90);
                    this.progress.setRadiusX(5);
                    this.progress.setRadiusY(5);
                    this.progress.setLength(360 * 0.5);
                    break;
                case 2:
                    this.progress.setStartAngle(45);
                    this.progress.setRadiusX(5);
                    this.progress.setRadiusY(5);
                    this.progress.setLength(360 * 0.8);
                    break;
                default:
                    if (Integer.valueOf(3).equals(quest.state())) {
                        this.progress.setRadiusX(5);
                        this.progress.setRadiusY(5);
                    } else {
                        this.progress.setRadiusX(2);
                        this.progress.setRadiusY(2);
                    }
                    this.progress.setLength(360);
                    break;
            }
            this.name.setText(java.util.Objects.toString(quest.title(), "任務 " + quest.no()));
            this.detail.setText(java.util.Objects.toString(quest.detail(), "").replace("<br>", ""));
            this.setOnContextMenuRequested(this::showContextMenu);

            refreshDefinitionAvailability();
            this.sceneProperty().addListener((o,previous,current) -> {
                if (previous != null) previous.windowProperty().removeListener(definitionWindowListener);
                attachDefinitionWindow(current == null ? null : current.getWindow());
                if (current != null) current.windowProperty().addListener(definitionWindowListener);
            });

            // Values and diagnostics come from the same bounded read as the refresh comparison.
            if (this.localNote != null) {
                if (!this.localNote.comment().isEmpty()) {
                    Label commentLabel = new Label(this.localNote.comment());
                    commentLabel.setWrapText(true);
                    commentLabel.setStyle("-fx-text-fill: red;");
                    this.infomation.getChildren().add(commentLabel);
                }
                if (this.localNote.discard()) {
                    this.setStyle("-fx-background-color: #ffe0e0;");
                    this.name.setOpacity(0.5);
                    this.name.setStyle("-fx-text-fill: gray;");
                }
                if (this.localNote.file().problem()) showLocalDiagnostic(this.localNote.file().diagnostic());
            }
            // カスタム任務の場合、検索リンクを表示しない
            int no = quest.no();
            if (no >= 10001 && no <= 10005) {
                this.searchLink.setVisible(false);
                this.searchLink.setManaged(false);
            }
        } catch (Exception e) {
            LoggerHolder.get().error("FXMLの初期化に失敗しました", e);
        }
    }

    void showLocalDiagnostic(String message) {
        Label warning = new Label(message);
        warning.setWrapText(true);
        warning.getStyleClass().add("quest-local-read-warning");
        this.infomation.getChildren().add(warning);
    }

    @FXML
    void remove(ActionEvent event) {
        QuestVisibilityUi.hide(this, java.util.List.of(this.display));
    }

    @FXML
    void removeAll(ActionEvent event) {
        // Capture only the displayed rows. Do not clear the underlying quest collection.
        var parent = getParent();
        var rows = parent == null ? java.util.List.of(this.display)
                : parent.getChildrenUnmodifiable().stream().filter(QuestPane.class::isInstance)
                    .map(QuestPane.class::cast).map(row -> row.display).toList();
        QuestVisibilityUi.hide(this, rows);
    }

    @FXML
    void search(ActionEvent event) {
        try {
            ThreadManager.getExecutorService()
                    .submit(() -> {
                        Desktop.getDesktop()
                                .browse(URI.create("https://www.google.co.jp/search?q="
                                        + URLEncoder.encode(java.util.Objects.toString(this.display.title(), "任務 " + this.display.no()), "UTF-8")));
                        return null;
                    });
        } catch (Exception e) {
            LoggerHolder.get().warn("ブラウザを開けませんでした", e);
        }
    }

    @FXML
    void expand(ActionEvent event) {
        boolean expanded = !this.detailView.isVisible();
        this.detailView.setVisible(expanded);
        if (expanded) {
            this.getStyleClass().add("expanded");
        } else {
            this.getStyleClass().remove("expanded");
        }
    }

    @FXML
    void condition(ActionEvent event) {
        try {
            InternalFXMLLoader.showWindow("logbook/gui/quest_progress.fxml", (Stage) this.getScene().getWindow(),
                    java.util.Objects.toString(this.display.title(), "任務 " + this.display.no()), c -> {
                        ((QuestProgress) c).setQuest(QuestListReadService.toLegacy(this.display));
                    }, null);
        } catch (IOException e) {
            LoggerHolder.get().warn("進捗を開けませんでした", e);
        }
    }

    private void showContextMenu(ContextMenuEvent event) {
        MenuItem item = new MenuItem("表示中の任務をすべて非表示（この起動中）");
        item.setOnAction(this::removeAll);
        ContextMenu contextMenu = new ContextMenu(item);
        contextMenu.show(this.getScene().getWindow(), event.getScreenX(), event.getScreenY());
    }
}
