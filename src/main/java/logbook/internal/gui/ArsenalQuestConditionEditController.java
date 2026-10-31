package logbook.internal.gui;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.util.StringConverter;
import logbook.internal.logger.LoggerHolder;
import logbook.plugin.api.ArsenalQuestDocumentsV1;
import logbook.plugin.api.QuestListV1;
import logbook.plugin.api.QuestLocalFilesV1;

/** Factory-note editor: immutable choices and guarded, target-bound local commands. */
public class ArsenalQuestConditionEditController extends logbook.plugin.api.FeatureUiV1.Window {
    record Choice(int id, String title, String detail, boolean discard) {
    }

    @FXML
    private ComboBox<Choice> questSelector;
    @FXML
    private CheckBox discard;
    @FXML
    private TextArea comment;
    @FXML
    private TextArea questDetail;
    private ArsenalQuestDocumentsV1.Session session;
    private long selectionVersion;

    Path questDirectory() {
        return Path.of("arsenalquest");
    }

    List<Choice> choices() {
        var quests = QuestListV1.snapshot().quests().stream()
                .filter(q -> Integer.valueOf(6).equals(q.category()) || Integer.valueOf(11).equals(q.category()))
                .toList();
        var notes = QuestLocalFilesV1.snapshot(quests.stream().map(QuestListV1.Quest::no).toList()).notes();
        return quests.stream().map(q -> new Choice(q.no(), q.title(), q.detail(), notes.get(q.no()).discard()))
                .sorted(Comparator.comparing(Choice::discard).thenComparingInt(Choice::id)).toList();
    }

    @FXML
    void initialize() {
        this.questSelector.setConverter(new StringConverter<Choice>() {
            @Override
            public String toString(Choice choice) {
                return choice == null ? null : choice.id() + ": " + choice.title();
            }

            @Override
            public Choice fromString(String ignored) {
                return null;
            }
        });
        this.questSelector.setCellFactory(list -> new ListCell<Choice>() {
            @Override
            protected void updateItem(Choice choice, boolean empty) {
                super.updateItem(choice, empty);
                setText(empty || choice == null ? null : choice.id() + ": " + choice.title());
                setGraphic(null);
                setStyle(!empty && choice != null && choice.discard() ? "-fx-text-fill: gray;" : "");
                setOpacity(!empty && choice != null && choice.discard() ? 0.5 : 1.0);
            }
        });
        this.questSelector.setButtonCell(this.questSelector.getCellFactory().call(null));
        this.questSelector.setItems(FXCollections.observableArrayList(choices()));
        this.questSelector.getSelectionModel().selectedItemProperty()
                .addListener((observable, old, selected) -> load(selected));
    }

    private void load(Choice choice) {
        selectionVersion++;
        session = null;
        discard.setSelected(false);
        comment.clear();
        discard.setDisable(true);
        comment.setDisable(true);
        questDetail.setText(choice == null ? "" : choice.detail());
        if (choice == null)
            return;
        try {
            var opened = ArsenalQuestDocumentsV1.at(questDirectory()).open(choice.id());
            discard.setSelected(opened.value().discard());
            comment.setText(opened.value().comment());
            session = opened;
            discard.setDisable(false);
            comment.setDisable(false);
        } catch (Exception failure) {
            LoggerHolder.get().error("工廠設定を読み込めません。元ファイルは保持しています。", failure);
            questDetail.appendText("\n工廠設定を読み込めません。元ファイルを確認し、選択し直してください。");
        }
    }

    @FXML
    void save(ActionEvent event) {
        var opened = session;
        var choice = questSelector.getValue();
        long expectedVersion = selectionVersion;
        if (opened == null || choice == null)
            return;
        try {
            var plan = opened.prepareSave(new ArsenalQuestDocumentsV1.Value(discard.isSelected(), comment.getText()));
            var confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.initOwner(getWindow());
            confirm.setTitle("工廠設定の保存確認");
            confirm.setHeaderText("任務 " + plan.questId() + " の設定を保存します");
            confirm.setContentText(
                    "対象: " + plan.target() + "\n既存ファイルがある場合は、変更前の検証済みバックアップを別名で保持します。\n読込後に変更されたファイルは上書きしません。");
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK)
                return;
            if (opened != session || expectedVersion != selectionVersion || choice != questSelector.getValue())
                throw new java.io.IOException("確認中に対象が変わりました。保存を中止しました。");
            var result = opened.apply(plan, true);
            message(Alert.AlertType.INFORMATION, "保存成功", result.retained() == null
                    ? "設定を保存しました。"
                    : "設定を保存しました。変更前の設定: " + result.retained());
        } catch (Exception failure) {
            LoggerHolder.get().error("工廠設定の保存に失敗しました", failure);
            message(Alert.AlertType.ERROR, "保存失敗", failure.getMessage());
        }
    }

    private void message(Alert.AlertType type, String title, String text) {
        var alert = new Alert(type);
        alert.initOwner(getWindow());
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(text);
        alert.showAndWait();
    }
}
