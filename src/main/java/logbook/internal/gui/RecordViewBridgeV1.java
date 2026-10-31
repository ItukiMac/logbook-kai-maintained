package logbook.internal.gui;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import javafx.application.Platform;
import javafx.stage.Stage;
import logbook.internal.kancolle.BattleLogs;

/** Host-owned implementation; never invoke record-maintenance/write operations from a viewer. */
public final class RecordViewBridgeV1 {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT);

    private RecordViewBridgeV1() {
    }

    public static boolean showBattleDetail(String recordedAt, Stage owner, String title) throws IOException {
        if (!Platform.isFxApplicationThread())
            throw new IllegalStateException("Record windows require the JavaFX application thread");
        if (recordedAt == null || !recordedAt.matches("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}"))
            throw new IllegalArgumentException("Expected a recorded timestamp, not a file path");
        try {
            LocalDateTime.parse(recordedAt, TIMESTAMP);
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException("Invalid recorded timestamp", error);
        }
        var log = BattleLogs.read(recordedAt);
        if (log == null)
            return false;
        InternalFXMLLoader.showWindow("logbook/gui/battle_detail.fxml", owner, title,
                controller -> ((BattleDetail) controller).setData(log), null);
        return true;
    }
}
