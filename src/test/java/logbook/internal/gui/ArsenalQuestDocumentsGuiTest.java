package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.stage.*;
import javafx.util.Duration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfSystemProperty(named="logbook.gui.tests",matches="true")
@Timeout(40)
class ArsenalQuestDocumentsGuiTest {
    @TempDir Path root;
    static final String ORIGINAL="/*keep*/{\"discard\":false,\"comment\":\"before\",\"future\":7}";
    @BeforeAll static void startFx() throws Exception {
        var ready=new CountDownLatch(1);
        try { Platform.startup(()->{Platform.setImplicitExit(false);ready.countDown();}); }
        catch(IllegalStateException running) { Platform.runLater(ready::countDown); }
        assertTrue(ready.await(10,TimeUnit.SECONDS));
    }
    static <T>T fx(Callable<T> task)throws Exception { var future=new FutureTask<T>(task);Platform.runLater(future);return future.get(25,TimeUnit.SECONDS); }
    final class Editor extends ArsenalQuestConditionEditController {
        @Override Path questDirectory(){return root;}
        @Override List<Choice> choices(){return List.of(new Choice(605,"first","detail",false),new Choice(606,"second","other",false));}
    }
    record View(Stage stage,Parent pane,Editor editor) implements AutoCloseable {
        @SuppressWarnings("unchecked") ComboBox<ArsenalQuestConditionEditController.Choice> selector(){return (ComboBox<ArsenalQuestConditionEditController.Choice>)pane.lookup("#questSelector");}
        TextArea comment(){return (TextArea)pane.lookup("#comment");}
        public void close(){stage.close();}
    }
    View open()throws Exception {
        var loader=new FXMLLoader(getClass().getResource("/logbook/gui/arsenal_quest_condition_edit.fxml"));loader.setControllerFactory(t->new Editor());
        Parent pane=loader.load();Stage stage=new Stage();stage.setScene(new Scene(pane));stage.show();Editor editor=loader.getController();editor.setWindow(stage);
        var view=new View(stage,pane,editor);view.selector().getSelectionModel().select(0);return view;
    }
    @FunctionalInterface interface Action { void run()throws Exception; }
    void dialogs(Action action,Action during,Boolean... decisions)throws Exception {
        var answers=new ArrayDeque<Boolean>(List.of(decisions));var failures=new ArrayList<Throwable>();boolean[] first={true};
        Timeline poll=new Timeline();poll.getKeyFrames().add(new KeyFrame(Duration.millis(30),event->{
            try {
                for(Window window:List.copyOf(Window.getWindows())) {
                    if(!window.isShowing()||window.getScene()==null)continue;
                    Parent scene=window.getScene().getRoot();DialogPane pane=scene instanceof DialogPane p?p:(DialogPane)scene.lookup(".dialog-pane");
                    if(pane==null)continue;
                    if(first[0]) {first[0]=false;assertTrue(pane.getContentText().contains("605.json"));assertTrue(pane.getContentText().contains("バックアップ"));if(during!=null)during.run();}
                    if(answers.isEmpty())throw new AssertionError("Unexpected modal dialog");boolean yes=answers.removeFirst();
                    var button=pane.getButtonTypes().stream().filter(b->yes?b.getButtonData()==ButtonBar.ButtonData.OK_DONE:b.getButtonData().isCancelButton()).findFirst().orElseThrow();
                    ((ButtonBase)pane.lookupButton(button)).fire();return;
                }
            }catch(Throwable failure){failures.add(failure);for(Window w:List.copyOf(Window.getWindows()))if(w instanceof Stage s&&w.isShowing()&&w.getScene().getRoot() instanceof DialogPane)s.close();poll.stop();}
        }));poll.setCycleCount(Animation.INDEFINITE);poll.play();try{action.run();}finally{poll.stop();}
        assertTrue(failures.isEmpty(),failures.toString());assertTrue(answers.isEmpty());assertFalse(first[0]);
    }
    @Test void confirmedSaveKeepsBackupAndUnknownFields()throws Exception {
        Files.writeString(root.resolve("605.json"),ORIGINAL,StandardOpenOption.CREATE_NEW);
        fx(()->{try(var v=open()){
            v.comment().setText("after");dialogs(()->v.editor().save(null),null,true,true);
            var bytes=Files.readAllBytes(root.resolve("605.json"));var json=logbook.core.QuestDocumentValidation.parse(bytes);
            assertEquals("after",json.path("comment").asText());assertEquals(7,json.path("future").asInt());
            try(var paths=Files.list(root)){var backups=paths.filter(p->p.toString().endsWith(".bak")).toList();assertEquals(1,backups.size());assertEquals(ORIGINAL,Files.readString(backups.getFirst()));}
        }return null;});
    }
    @Test void cancellationLeavesBytesAndUnsavedInput()throws Exception {
        Files.writeString(root.resolve("605.json"),ORIGINAL,StandardOpenOption.CREATE_NEW);
        fx(()->{try(var v=open()){v.comment().setText("unsaved");dialogs(()->v.editor().save(null),null,false);assertEquals("unsaved",v.comment().getText());assertEquals(ORIGINAL,Files.readString(root.resolve("605.json")));}return null;});
    }
    @Test void externalEditDuringConfirmationIsNotOverwritten()throws Exception {
        Files.writeString(root.resolve("605.json"),ORIGINAL,StandardOpenOption.CREATE_NEW);
        fx(()->{try(var v=open()){v.comment().setText("after");dialogs(()->v.editor().save(null),()->Files.writeString(root.resolve("605.json"),"external"),true,true);assertEquals("external",Files.readString(root.resolve("605.json")));}return null;});
    }
    @Test void changedTargetDuringConfirmationWritesNeitherFile()throws Exception {
        Files.writeString(root.resolve("605.json"),ORIGINAL,StandardOpenOption.CREATE_NEW);
        fx(()->{try(var v=open()){dialogs(()->v.editor().save(null),()->v.selector().getSelectionModel().select(1),true,true);assertEquals(ORIGINAL,Files.readString(root.resolve("605.json")));assertFalse(Files.exists(root.resolve("606.json")));}return null;});
    }
    @Test void clearedSelectionDuringConfirmationCancelsOldTarget()throws Exception {
        Files.writeString(root.resolve("605.json"),ORIGINAL,StandardOpenOption.CREATE_NEW);
        fx(()->{try(var v=open()){dialogs(()->v.editor().save(null),()->v.selector().getSelectionModel().clearSelection(),true,true);assertEquals(ORIGINAL,Files.readString(root.resolve("605.json")));assertEquals("",v.comment().getText());assertTrue(v.comment().isDisabled());}return null;});
    }
    @Test void corruptSelectionClearsPreviousValuesAndCannotBeSaved()throws Exception {
        Files.writeString(root.resolve("605.json"),ORIGINAL,StandardOpenOption.CREATE_NEW);Files.writeString(root.resolve("606.json"),"broken",StandardOpenOption.CREATE_NEW);
        fx(()->{try(var v=open()){assertEquals("before",v.comment().getText());v.selector().getSelectionModel().select(1);assertEquals("",v.comment().getText());assertTrue(v.comment().isDisabled());v.editor().save(null);assertEquals("broken",Files.readString(root.resolve("606.json")));}return null;});
    }
}
