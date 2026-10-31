package logbook.internal.gui;

import java.util.List;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import logbook.plugin.api.QuestListV1.Quest;
import logbook.plugin.api.QuestVisibilityV1;
import logbook.plugin.api.QuestVisibilityV1.Plan;
import logbook.plugin.api.QuestVisibilityV1.State;

/** Confirmation and reversible presentation only; no quest or file mutation. */
final class QuestVisibilityUi {
    static final EventType<Event> CHANGED = new EventType<>(Event.ANY, "QUEST_VISIBILITY_CHANGED_V1");
    private static final String RETAINED = "定義・受諾履歴・キャッシュ・カスタム任務ファイルは削除しません。\n"
            + "この起動中だけ一覧から隠します。再表示またはアプリ再起動で戻ります。\n"
            + "カスタム任務の枠は空きません。";

    private QuestVisibilityUi() {
    }

    static void hide(Node source, List<Quest> rows) {
        if (rows.isEmpty())
            return;
        Plan plan = QuestVisibilityV1.prepareHide(rows.stream().map(Quest::no).toList());
        String targets = rows.stream().map(q -> q.no() + " : " + java.util.Objects.toString(q.title(), "名称未記録"))
                .collect(java.util.stream.Collectors.joining("\n"));
        confirm(source, plan, "この起動中だけ非表示", "対象: " + rows.size() + "件\n" + targets + "\n\n" + RETAINED);
    }

    static HBox banner(State state) {
        Label label = new Label("非表示 " + state.hiddenIds().size() + "件（この起動中のみ・データ保持）");
        label.setWrapText(true);
        Hyperlink restore = new Hyperlink("すべて再表示");
        restore.setId("quest-visibility-restore");
        restore.setOnAction(event -> {
            Plan plan = QuestVisibilityV1.prepareRestoreAll();
            confirm(restore, plan, "一覧へ再表示", "対象ID: " + plan.questIds()
                    + "\n非表示設定だけを取り消します。定義・履歴・ファイルは変更しません。");
        });
        HBox banner = new HBox(6, label, restore);
        banner.setId("quest-visibility-banner");
        return banner;
    }

    private static void confirm(Node source, Plan plan, String operation, String content) {
        ButtonType apply = new ButtonType(operation, ButtonData.OK_DONE);
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION, content, apply, ButtonType.CANCEL);
        dialog.setTitle("任務一覧の表示");
        dialog.setHeaderText(operation);
        if (source.getScene() != null)
            dialog.initOwner(source.getScene().getWindow());
        boolean confirmed = dialog.showAndWait().filter(apply::equals).isPresent();
        var result = QuestVisibilityV1.apply(plan, confirmed);
        if (result.outcome() == QuestVisibilityV1.Outcome.CONFLICT) {
            Alert conflict = new Alert(Alert.AlertType.WARNING,
                    "確認中に非表示状態が変わりました。変更は適用していません。もう一度対象を確認してください。", ButtonType.OK);
            if (source.getScene() != null)
                conflict.initOwner(source.getScene().getWindow());
            conflict.setTitle("任務一覧の状態変更");
            conflict.showAndWait();
        }
        // Also refresh after a conflict, but never silently retry an outdated command.
        if (result.accepted() || result.outcome() == QuestVisibilityV1.Outcome.CONFLICT)
            source.fireEvent(new Event(CHANGED));
    }
}
