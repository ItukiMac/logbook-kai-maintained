package logbook.features.quests;
import javafx.scene.control.MenuItem;
import logbook.plugin.gui.MainExtMenu;
public final class QuestManagementMenu implements MainExtMenu {
    @Override public MenuItem getContent() {
        MenuItem item=new MenuItem("任務管理表・完了履歴…");item.setId("logbook.quest-management");
        item.setOnAction(e->new QuestManagementWindow().open(null));return item;
    }
}
