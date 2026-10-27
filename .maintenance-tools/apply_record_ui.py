from pathlib import Path
import re

def new(path,text):
    p=Path(path);assert not p.exists(),path
    p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf8')

def change(path,fn):
    p=Path(path);raw=p.read_bytes();ending='\r\n' if b'\r\n' in raw else '\n'
    old=raw.decode().replace('\r\n','\n');text=fn(old);assert old!=text,path
    p.write_bytes(text.replace('\n',ending).encode())

def once(s,old,new):
    assert s.count(old)==1,(old,s.count(old))
    return s.replace(old,new)

def api(s):
    s=once(s,'    /** Table helpers keep the existing persisted keys and shortcuts. */',r'''    /**
     * Prepare a custom-root component using its owner's loader, not the host's
     * loader. The absolute resource must belong to the calling component.
     * Call setRoot/setController before load, as with a standard FXMLLoader.
     * Invoke on the JavaFX application thread.
     */
    public static javafx.fxml.FXMLLoader componentLoader(Class<?> owner, String resource) throws IOException {
        return FeatureUiBridgeV1.componentLoader(owner, resource);
    }

    /** Apply the host font/size preferences without exposing its loader implementation. */
    public static javafx.scene.Parent applyGlobalStyles(javafx.scene.Parent root) {
        return FeatureUiBridgeV1.applyGlobalStyles(root);
    }

    /** Tree layout only: existing width/order/sort keys are preserved; visibility is not invented. */
    public static final class Trees {
        private Trees() { }
        public static void restoreLayout(javafx.scene.control.TreeTableView<?> table, String key) {
            FeatureUiBridgeV1.restoreTree(table, key);
        }
    }

    /** Table helpers keep the existing persisted keys and shortcuts. */''')
    s=once(s,'        private Tables() { }',r'''        private Tables() { }

        public static String columnName(TableColumn<?, ?> column) {
            return FeatureUiBridgeV1.columnName(column);
        }''')
    s=once(s,'        private Controls() { }',r'''        private Controls() { }

        /** Same modal confirmation and cancellation behavior as the host. Call on the FX thread. */
        public static java.util.Optional<javafx.scene.control.ButtonType> alert(
                javafx.scene.control.Alert.AlertType type, String title, String message, Stage owner) {
            return FeatureUiBridgeV1.alert(type, title, message, owner);
        }''')
    return s
change('src/main/java/logbook/plugin/api/FeatureUiV1.java',api)

def bridge(s):
    return once(s,'    public static void restoreTable(TableView<?> table, String key) { TableTool.setVisible(table, key); }',r'''    public static javafx.fxml.FXMLLoader componentLoader(Class<?> owner, String resource) throws IOException {
        java.util.Objects.requireNonNull(owner, "component owner");
        if (resource == null || !resource.startsWith("/"))
            throw new IOException("An absolute component resource is required");
        var url = owner.getResource(resource);
        if (url == null) throw new IOException("Missing component resource: " + resource);
        var loader = InternalFXMLLoader.load(url);
        loader.setClassLoader(owner.getClassLoader());
        return loader;
    }

    public static javafx.scene.Parent applyGlobalStyles(javafx.scene.Parent root) {
        return InternalFXMLLoader.setGlobal(root);
    }

    public static void restoreTree(javafx.scene.control.TreeTableView<?> table, String key) {
        TreeTableTool.setVisible(table, key);
    }

    public static String columnName(TableColumn<?, ?> column) { return Tools.Tables.getColumnName(column); }

    public static java.util.Optional<javafx.scene.control.ButtonType> alert(
            javafx.scene.control.Alert.AlertType type, String title, String message, Stage owner) {
        return Tools.Controls.alert(type, title, message, owner);
    }

    public static void restoreTable(TableView<?> table, String key) { TableTool.setVisible(table, key); }''')
change('src/main/java/logbook/internal/gui/FeatureUiBridgeV1.java',bridge)

new('src/main/java/logbook/plugin/api/RecordViewsV1.java',r'''package logbook.plugin.api;

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
''')
new('src/main/java/logbook/internal/gui/RecordViewBridgeV1.java',r'''package logbook.internal.gui;

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
    private RecordViewBridgeV1() { }

    public static boolean showBattleDetail(String recordedAt, Stage owner, String title) throws IOException {
        if (!Platform.isFxApplicationThread())
            throw new IllegalStateException("Record windows require the JavaFX application thread");
        if (recordedAt == null || !recordedAt.matches("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}"))
            throw new IllegalArgumentException("Expected a recorded timestamp, not a file path");
        try { LocalDateTime.parse(recordedAt, TIMESTAMP); }
        catch (DateTimeParseException error) { throw new IllegalArgumentException("Invalid recorded timestamp", error); }
        var log = BattleLogs.read(recordedAt);
        if (log == null) return false;
        InternalFXMLLoader.showWindow("logbook/gui/battle_detail.fxml", owner, title,
                controller -> ((BattleDetail) controller).setData(log), null);
        return true;
    }
}
''')

for name,row in [('BattleLogController','BattleLogDetail'),('MissionLogController','MissionLogDetail'),('CreateItemController','CreateItemController.CreateItem')]:
    def controller(s,name=name,row=row):
        s=once(s,'package logbook.internal.gui;','package logbook.internal.gui;\n\nimport logbook.plugin.api.FeatureUiV1;')
        s=once(s,'extends WindowController','extends FeatureUiV1.Window')
        s=re.sub(r'\bTreeTableTool\.setVisible\b','FeatureUiV1.Trees.restoreLayout',s)
        s=re.sub(r'\bTableTool\b','FeatureUiV1.Tables',s)
        s=s.replace('Tools.Controls.setSplitWidth','FeatureUiV1.Controls.setSplitWidth')
        s=s.replace('Tools.Controls.alert','FeatureUiV1.Controls.alert')
        s=s.replace('Tools.Tables.getColumnName','FeatureUiV1.Tables.columnName')
        if name!='BattleLogController':
            s=once(s,'private TableColumn<BattleLogDetail, Integer> row;',f'private TableColumn<{row}, Integer> row;')
        else:
            s=once(s,'import logbook.bean.BattleLog;','import logbook.plugin.api.RecordViewsV1;')
            s=once(s,'''                        BattleLog log = BattleLogs.read(d.getDate());
                        if (log != null) {
                            try {
                                InternalFXMLLoader.showWindow("logbook/gui/battle_detail.fxml", this.getWindow(),
                                        "戦闘ログ", c -> {
                                            ((BattleDetail) c).setData(log);
                                        }, null);
                            } catch (Exception ex) {
                                LoggerHolder.get().error("詳細の表示に失敗しました", ex);
                            }
                        }''','''                        try {
                            RecordViewsV1.showBattleDetail(d.getDate(), this.getWindow(), "戦闘ログ");
                        } catch (Exception ex) {
                            LoggerHolder.get().error("詳細の表示に失敗しました", ex);
                        }''')
            s=once(s,'InternalFXMLLoader.setGlobal(alert.getDialogPane())','FeatureUiV1.applyGlobalStyles(alert.getDialogPane())')
            s=once(s,'InternalFXMLLoader.load("logbook/gui/battlelog_dialog.fxml")','FeatureUiV1.componentLoader(getClass(), "/logbook/gui/battlelog_dialog.fxml")')
        assert not re.search(r'\b(?:InternalFXMLLoader|TreeTableTool|TableTool|WindowController|Tools)\b',s),name
        return s
    change('src/main/java/logbook/internal/gui/'+name+'.java',controller)

new('src/test/java/logbook/internal/gui/RecordUiContractTest.java',r'''package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.stage.Stage;
import logbook.plugin.api.FeatureUiV1;
import logbook.plugin.api.RecordViewsV1;
import org.junit.jupiter.api.Test;

class RecordUiContractTest {
    @Test void existingGuiVersionIsRetainedAndRecordContractIsExplicit() {
        assertEquals(1, FeatureUiV1.version());
        assertEquals(1, RecordViewsV1.version());
        assertTrue(FeatureUiV1.Window.class.isAssignableFrom(BattleLogController.class));
        assertTrue(FeatureUiV1.Window.class.isAssignableFrom(MissionLogController.class));
        assertTrue(FeatureUiV1.Window.class.isAssignableFrom(CreateItemController.class));
    }
    @Test void missingComponentIsACheckedFailureRatherThanAnEmptyLoader() {
        assertThrows(IOException.class, () -> FeatureUiV1.componentLoader(getClass(), "/missing-record-component.fxml"));
        assertThrows(IOException.class, () -> FeatureUiV1.componentLoader(getClass(), "relative.fxml"));
    }
    @Test void historicalWindowCannotReadOrRenderFromABackgroundThread() {
        assertThrows(IllegalStateException.class,
                () -> RecordViewsV1.showBattleDetail("2026-10-27 12:00:00", null, "test"));
    }
    @Test void publicRecordMethodDoesNotExposeMutableInternalModels() throws Exception {
        var method = RecordViewsV1.class.getMethod("showBattleDetail", String.class, Stage.class, String.class);
        assertEquals(boolean.class, method.getReturnType());
        assertArrayEquals(new Class<?>[] {IOException.class}, method.getExceptionTypes());
        assertEquals(FXMLLoader.class, FeatureUiV1.class.getMethod("componentLoader", Class.class, String.class).getReturnType());
        assertEquals(Parent.class, FeatureUiV1.class.getMethod("applyGlobalStyles", Parent.class).getReturnType());
    }
}
''')
print('RECORD_UI_SOURCE_APPLIED no_storage_schema_change=true no_distribution_contract_change=true')
