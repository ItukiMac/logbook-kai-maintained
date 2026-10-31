package logbook.plugin.api;

import java.util.List;
import javafx.scene.Node;
import javafx.event.Event;
import javafx.event.EventType;
import logbook.internal.gui.QuestUiBridgeV1;

/** Versioned quest presentation entry, with exact-core bundle compatibility. FX thread only. */
public final class QuestUiV1 {
    private QuestUiV1() {
    }

    public static int version() {
        return 1;
    }

    public static EventType<Event> visibilityChanged() {
        return QuestUiBridgeV1.visibilityChanged();
    }

    public static void hide(Node source, List<QuestListV1.Quest> rows) {
        QuestUiBridgeV1.hide(source, rows);
    }
}
