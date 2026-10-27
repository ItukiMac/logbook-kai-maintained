package logbook.plugin.api;

import java.io.IOException;
import javafx.stage.Stage;
import logbook.internal.gui.RecordViewBridgeV1;

/**
 * Versioned record-window contract. Record storage, decoding and the detailed
 * battle renderer stay in the host; a feature never receives a mutable BattleLog.
 * Call on the JavaFX application thread. This is not a cross-core ABI guarantee;
 * existing feature bundles still require their exact host digest.
 */
public final class RecordViewsV1 {
    private RecordViewsV1() { }
    public static int version() { return 1; }

    /**
     * Open a historical battle using the existing host reader, without repairing,
     * moving or rewriting records. Returns false when the host cannot read it.
     * recordedAt is the persisted yyyy-MM-dd HH:mm:ss timestamp, not a path.
     * Invalid timestamps are rejected before any record lookup.
     */
    public static boolean showBattleDetail(String recordedAt, Stage owner, String title) throws IOException {
        return RecordViewBridgeV1.showBattleDetail(recordedAt, owner, title);
    }
}
