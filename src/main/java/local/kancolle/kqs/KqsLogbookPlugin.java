package local.kancolle.kqs;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.ButtonType;
import javafx.scene.layout.HBox;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import logbook.bean.AppQuestCollection;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import logbook.listener.ContentListenerSpi;
import logbook.net.RequestMetaData;
import logbook.net.ResponseMetaData;
import logbook.plugin.lifecycle.StartUp;

/**
 * KQS shares the full available quest snapshot without adding controls to
 * logbook-kai's main layout. Right-click any quest pane / quest area.
 */
public final class KqsLogbookPlugin implements ContentListenerSpi, StartUp {
    private static final QuestStore STORE = new QuestStore();
    private static final AtomicBoolean EXPORTING = new AtomicBoolean(false);
    private static final String FILTER_PROPERTY = "kqs.quest.contextMenuFilter.v2";

    @Override
    public boolean test(RequestMetaData request) {
        if (request == null) return false;
        String path = request.getRequestURI();
        return path != null && (path.equals("/kcsapi/api_get_member/questlist")
            || path.equals("/kcsapi/api_req_quest/start")
            || path.equals("/kcsapi/api_req_quest/stop")
            || path.equals("/kcsapi/api_req_quest/clearitemget"));
    }

    @Override
    public void accept(RequestMetaData request, ResponseMetaData response) {
        if (request == null || response == null || response.getStatus() < 200 || response.getStatus() >= 300) return;
        try {
            Optional<InputStream> body = response.getResponseBody();
            if (body.isEmpty()) return;
            byte[] bytes;
            try (InputStream in = body.get()) { bytes = in.readNBytes(2_000_001); }
            if (bytes.length > 2_000_000) return;

            // Only the whitelisted quest parameters: no api_token or raw POST body.
            Map<String, String> params = new HashMap<>();
            params.put("api_page_no", request.getParameter("api_page_no", "1"));
            params.put("api_tab_id", request.getParameter("api_tab_id", "unknown"));
            params.put("api_quest_id", request.getParameter("api_quest_id", ""));
            STORE.accept(request.getRequestURI(), params, new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("[KQS] Quest response rejected: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Platform.runLater(KqsLogbookPlugin::installFilters);
            } catch (RuntimeException e) {
                System.err.println("[KQS] UI not ready: " + e.getClass().getSimpleName());
            }
            try { Thread.sleep(2000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private static void installFilters() {
        for (Window window : Window.getWindows()) {
            Scene scene = window.getScene();
            if (scene == null || scene.getRoot() == null) continue;
            Parent root = scene.getRoot();
            if (Boolean.TRUE.equals(root.getProperties().get(FILTER_PROPERTY))) continue;
            if (!root.getStyleClass().contains("mainWindow")) continue;
            root.addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, event -> {
                if (!(event.getTarget() instanceof Node node) || !insideQuestArea(node)) return;
                // QuestPane contains an upstream "全て除去" context menu. Consume only
                // quest-area events, preventing destructive operations by mistake.
                event.consume();
                showMenu(window, event.getScreenX(), event.getScreenY(), isQuestItem(node));
            });
            root.getProperties().put(FILTER_PROPERTY, true);
        }
    }

    private static boolean insideQuestArea(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            String id = n.getId();
            if ("questPane".equals(id) || "questbox".equals(id)) return true;
            if (n.getClass().getName().equals("logbook.internal.gui.QuestPane")) return true;
            if (n instanceof TitledPane pane && "任務".equals(pane.getText())) return true;
        }
        return false;
    }

    private static boolean isQuestItem(Node node) {
        for(Node n=node;n!=null;n=n.getParent()) {
            if(n.getClass().getName().equals("logbook.internal.gui.QuestPane"))return true;
        }
        return false;
    }

    private static void showMenu(Window window, double x, double y, boolean showOriginal) {
        MenuItem copyJson = new MenuItem("KQS：現在の全任務をJSONコピー");
        copyJson.setOnAction(e -> exportAsync(window, QuestExporter::json, text->copy(text,window)));

        MenuItem copyPrompt = new MenuItem("KQS：任務消化計画用のJSON＋依頼文をコピー");
        copyPrompt.setOnAction(e -> exportAsync(window, QuestExporter::prompt, text->copy(text,window)));

        MenuItem preview = new MenuItem("KQS：全任務JSONを別ウィンドウで表示");
        preview.setOnAction(e -> preview(window));

        MenuItem save = new MenuItem("KQS：全任務JSONをファイル保存...");
        save.setOnAction(e -> save(window));

        ContextMenu menu=new ContextMenu(copyJson,copyPrompt,preview,save);
        if(showOriginal) {
            Menu original=new Menu("航海日誌改（元の操作）");
            MenuItem clear=new MenuItem("全て除去...（確認あり）");
            clear.setOnAction(e->confirmClear(window));
            original.getItems().add(clear);
            menu.getItems().add(original);
        }
        menu.show(window, x, y);
    }

    private static void confirmClear(Window owner) {
        Alert alert=new Alert(Alert.AlertType.CONFIRMATION,
            "航海日誌改に記録された任務一覧を全て除去します。\nゲームの任務は変更されません。実行しますか？",
            ButtonType.YES,ButtonType.NO);
        alert.setTitle("航海日誌改：全て除去");
        alert.setHeaderText("既存操作の確認");
        if(owner!=null)alert.initOwner(owner);
        if(alert.showAndWait().filter(ButtonType.YES::equals).isPresent())
            AppQuestCollection.get().getQuest().clear();
    }

    private static void exportAsync(Window owner, java.util.function.Function<QuestStore,String> generator,
                                    Consumer<String> onSuccess) {
        if(!EXPORTING.compareAndSet(false,true)) {
            message(owner,"KQS","別のJSON生成処理を実行中です。",Alert.AlertType.INFORMATION);
            return;
        }
        Thread worker=new Thread(()-> {
            try {
                String value=generator.apply(STORE);
                Platform.runLater(()-> {
                    EXPORTING.set(false);
                    onSuccess.accept(value);
                });
            } catch(Exception error) {
                Platform.runLater(()-> {
                    EXPORTING.set(false);
                    message(owner,"KQS","JSON生成に失敗: "+error.getClass().getSimpleName(),Alert.AlertType.ERROR);
                });
            }
        },"KQS-Export");
        worker.setDaemon(true);
        worker.start();
    }

    private static void copy(String text, Window owner) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        if (!Clipboard.getSystemClipboard().setContent(content)) {
            message(owner, "KQS", "コピーに失敗しました。", Alert.AlertType.ERROR);
        }
    }

    private static void preview(Window owner) {
        Stage stage = new Stage();
        if (owner != null) stage.initOwner(owner);
        stage.setTitle("KQS Logbook Plugin | 全任務JSON");
        TextArea area = new TextArea("JSONを生成しています...");
        exportAsync(stage, QuestExporter::json, area::setText);
        area.setEditable(false);
        area.setWrapText(false);
        Button copy = new Button("JSONをコピー");
        copy.setOnAction(e -> copy(area.getText(), stage));
        Button refresh = new Button("最新状態を再取得（表示更新）");
        refresh.setOnAction(e -> exportAsync(stage, QuestExporter::json, area::setText));
        VBox layout = new VBox(8, new javafx.scene.layout.HBox(8, copy, refresh), area);
        layout.setStyle("-fx-padding:12");
        VBox.setVgrow(area, javafx.scene.layout.Priority.ALWAYS);
        stage.setScene(new Scene(layout, 900, 640));
        stage.show();
    }

    private static void save(Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("KQS 全任務JSONの保存先を指定");
        chooser.setInitialFileName("kqs-quests-" +
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".json");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        java.io.File destination = chooser.showSaveDialog(owner);
        if (destination == null) return;
        Path path = destination.toPath();
        exportAsync(owner,QuestExporter::json,text->{
            try {
                // CREATE_NEW refuses to overwrite existing files, even with a stale dialog.
                Files.writeString(path,text,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
                message(owner,"KQS","JSONファイルを保存しました。",Alert.AlertType.INFORMATION);
            } catch(java.nio.file.FileAlreadyExistsException ex) {
                message(owner,"KQS","既存ファイルは上書きしません。",Alert.AlertType.WARNING);
            } catch(Exception ex) {
                message(owner,"KQS","保存に失敗しました: "+ex.getClass().getSimpleName(),Alert.AlertType.ERROR);
            }
        });
    }

    private static void message(Window owner, String title, String msg, Alert.AlertType type) {
        Alert alert = new Alert(type, msg);
        alert.setTitle(title);
        if (owner != null) alert.initOwner(owner);
        alert.show();
    }
}
