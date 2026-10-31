package logbook.internal.gui;

import java.util.List;
import javafx.scene.Node;
import javafx.event.Event;
import javafx.event.EventType;
import logbook.plugin.api.QuestListV1;

/** Host-only visibility dialogs share their event identity with MainController. */
public final class QuestUiBridgeV1 {
    private QuestUiBridgeV1() {
    }

    public static EventType<Event> visibilityChanged() {
        return QuestVisibilityUi.CHANGED;
    }

    public static void hide(Node source, List<QuestListV1.Quest> rows) {
        QuestVisibilityUi.hide(source, rows);
    }
}
