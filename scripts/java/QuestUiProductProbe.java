package logbook.internal.gui;

import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.stage.*;
import logbook.core.LogBookCoreServices;
import logbook.core.GameDefinitionRuntime;
import logbook.plugin.api.*;

/** Run only with NEW synthetic cwd and the actual split product JARs. */
public final class QuestUiProductProbe {
    static void check(boolean okay, String message) {
        if (!okay)
            throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 1 && "synthetic-quest-ui-only".equals(args[0]), "Synthetic-only argument required");
        check(!Files.exists(Path.of("config")) && !Files.exists(Path.of("arsenalquest")), "Existing runtime refused");
        LogBookCoreServices.init(List.of());
        GameDefinitionRuntime.initialize();
        var ready = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            ready.countDown();
        });
        check(ready.await(10, TimeUnit.SECONDS), "FX initialization");
        var task = new FutureTask<Void>(() -> {
            var row = new QuestListV1.Quest(987654, 2, 4, 2, "synthetic", "detail", List.of(), 0, 0, 0, null,
                    "2099-01-01 00:00:00", true);
            var pane = new QuestPane(row, null);
            var stage = new Stage();
            stage.setScene(new Scene(pane));
            stage.show();
            check(pane.getClass().getProtectionDomain().getCodeSource().getLocation().toString()
                    .contains("logbook-kai2-feature-quests.jar"), "Row accidentally loaded from core");
            check(!pane.getChildren().isEmpty(), "Quest FXML did not load");
            stage.close();
            for (String name : List.of("quest_progress", "quest_condition_edit", "arsenal_quest_condition_edit")) {
                var loader = new FXMLLoader(QuestUiProductProbe.class.getResource("/logbook/gui/" + name + ".fxml"));
                Parent root = loader.load();
                Object controller = loader.getController();
                check(controller instanceof FeatureUiV1.Window, "Public window lifecycle missing: " + name);
                check(controller.getClass().getProtectionDomain().getCodeSource().getLocation().toString()
                        .contains("logbook-kai2-feature-quests.jar"), "Controller fallback: " + name);
                Stage window = new Stage();
                window.setScene(new Scene(root));
                ((FeatureUiV1.Window) controller).bindWindow(window);
                window.show();
                window.close();
            }
            var session = ArsenalQuestDocumentsV1.at(Path.of("arsenalquest")).open(605);
            session.apply(session.prepareSave(new ArsenalQuestDocumentsV1.Value(false, "split note")), true);
            check("split note".equals(ArsenalQuestDocumentsV1.at(Path.of("arsenalquest")).open(605).value().comment()),
                    "Split note roundtrip");
            return null;
        });
        try {
            Platform.runLater(task);
            task.get(35, TimeUnit.SECONDS);
            System.out.println("QUEST_UI_PRODUCT_OK windows=4 guarded_note_roundtrip=true synthetic_only=true");
        } finally {
            Platform.exit();
            LogBookCoreServices.closeContainer();
            logbook.internal.ThreadManager.getExecutorService().shutdownNow();
        }
    }
}
