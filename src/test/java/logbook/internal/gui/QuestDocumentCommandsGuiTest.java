package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import logbook.bean.AppQuest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Real editor and modal controls with an injected NEW directory, never a user runtime. */
@EnabledIfSystemProperty(named="logbook.gui.tests",matches="true")
@Timeout(40)
class QuestDocumentCommandsGuiTest {
    @TempDir Path root;
    static final String ORIGINAL="/*keep*/{\"type\":\"出撃\",\"resetType\":\"単発\",\"customName\":\"before\",\"future\":7,\"conditions\":[]}";
    @BeforeAll static void startFx() throws Exception {
        var ready=new CountDownLatch(1);
        try{Platform.startup(()->{Platform.setImplicitExit(false);ready.countDown();});}
        catch(IllegalStateException alreadyRunning){Platform.runLater(ready::countDown);}
        assertTrue(ready.await(10,TimeUnit.SECONDS));
    }
    static <T>T fx(Callable<T> task)throws Exception{var future=new FutureTask<T>(task);Platform.runLater(future);return future.get(25,TimeUnit.SECONDS);}
    final class Editor extends QuestConditionEditController{@Override Path questDirectory(){return root;}}
    record View(Stage stage,Parent pane,QuestConditionEditController editor) implements AutoCloseable {
        @SuppressWarnings("unchecked") ComboBox<AppQuest> selector(){return (ComboBox<AppQuest>)pane.lookup("#questSelector");}
        TextField title(){return (TextField)pane.lookup("#customNameField");}
        public void close(){stage.close();}
    }
    View open()throws Exception{
        var loader=new FXMLLoader(getClass().getResource("/logbook/gui/quest_condition_edit.fxml"));loader.setControllerFactory(t->new Editor());
        Parent pane=loader.load();Stage stage=new Stage();stage.setScene(new Scene(pane));stage.show();
        ((RadioButton)pane.lookup("#customQuestRadio")).fire();var v=new View(stage,pane,loader.getController());v.selector().getSelectionModel().select(0);return v;
    }
    @FunctionalInterface interface Action{void run()throws Exception;}
    void dialogs(Action action,Action during,Boolean... decisions)throws Exception{
        var answers=new ArrayDeque<Boolean>(List.of(decisions));var failures=new ArrayList<Throwable>();boolean[] first={true};
        Timeline poll=new Timeline();
        poll.getKeyFrames().add(new KeyFrame(Duration.millis(30),e->{
            try{
                for(Window window:List.copyOf(Window.getWindows())) {
                    if(!window.isShowing()||window.getScene()==null)continue;Parent root=window.getScene().getRoot();
                    DialogPane pane=root instanceof DialogPane p?p:(DialogPane)root.lookup(".dialog-pane");if(pane==null)continue;
                    if(first[0]){first[0]=false;assertEquals("完全削除ではなく、復旧可能な別名退避です",pane.getHeaderText());assertTrue(pane.getContentText().contains("10001.json"));assertTrue(pane.getContentText().contains("SHA-256"));if(during!=null)during.run();}
                    if(answers.isEmpty())throw new AssertionError("Unexpected extra modal dialog");boolean yes=answers.removeFirst();
                    var button=pane.getButtonTypes().stream().filter(b->yes?b.getButtonData()==ButtonBar.ButtonData.OK_DONE:b.getButtonData().isCancelButton()).findFirst().orElseThrow();
                    ((ButtonBase)pane.lookupButton(button)).fire();return;
                }
            }catch(Throwable failure){failures.add(failure);for(Window w:List.copyOf(Window.getWindows()))if(w.isShowing()&&w instanceof Stage s&&s.getScene().getRoot() instanceof DialogPane)s.close();poll.stop();}
        }));
        poll.setCycleCount(Animation.INDEFINITE);poll.play();
        try{action.run();}finally{poll.stop();}
        assertTrue(failures.isEmpty(),failures.toString());assertTrue(answers.isEmpty(),"Expected confirmations were not shown");assertFalse(first[0]);
    }
    @Test void saveUsesCapturedTargetAndKeepsOriginalBackupAndUnknownFields()throws Exception{
        Files.writeString(root.resolve("10001.json"),ORIGINAL,StandardOpenOption.CREATE_NEW);
        fx(()->{try(var v=open()){
            v.title().setText("after");Path backup=v.editor().saveDocument();assertEquals(ORIGINAL,Files.readString(backup));
            var saved=QuestEditorDocument.parse(Files.readAllBytes(root.resolve("10001.json")));assertEquals("after",saved.path("customName").asText());assertEquals(7,saved.path("future").asInt());
        }return null;});
    }
    @Test void cancelledRetirementLeavesFileAndEditorInputUntouched()throws Exception{
        Files.writeString(root.resolve("10001.json"),ORIGINAL,StandardOpenOption.CREATE_NEW);
        fx(()->{try(var v=open()){
            v.title().setText("unsaved");dialogs(()->v.editor().remove(null),null,false);
            assertEquals(ORIGINAL,Files.readString(root.resolve("10001.json")));assertEquals("unsaved",v.title().getText());
            try(var p=Files.list(root)){assertEquals(1,p.count());}
        }return null;});
    }
    @Test void confirmedRetirementRetainsVerifiedCopiesAndResetsOnlyAfterSuccess()throws Exception{
        Files.writeString(root.resolve("10001.json"),ORIGINAL,StandardOpenOption.CREATE_NEW);
        fx(()->{try(var v=open()){
            dialogs(()->v.editor().remove(null),null,true,true);assertFalse(Files.exists(root.resolve("10001.json")));
            try(var paths=Files.list(root)){var values=paths.toList();assertEquals(2,values.size());for(Path p:values)assertEquals(ORIGINAL,Files.readString(p));}
            assertTrue(((Button)v.pane().lookup("#removeBtn")).isDisabled());
        }return null;});
    }
    @Test void editDuringConfirmationRefusesRetirementWithoutTouchingExternalBytes()throws Exception{
        Files.writeString(root.resolve("10001.json"),ORIGINAL,StandardOpenOption.CREATE_NEW);
        fx(()->{try(var v=open()){
            dialogs(()->v.editor().remove(null),()->Files.writeString(root.resolve("10001.json"),"external bytes retained"),true,true);
            assertEquals("external bytes retained",Files.readString(root.resolve("10001.json")));
            try(var p=Files.list(root)){assertEquals(1,p.count());}
        }return null;});
    }
    @Test void targetSwitchDuringConfirmationCannotRetireEitherDefinition()throws Exception{
        Files.writeString(root.resolve("10001.json"),ORIGINAL,StandardOpenOption.CREATE_NEW);Files.writeString(root.resolve("10002.json"),ORIGINAL,StandardOpenOption.CREATE_NEW);
        fx(()->{try(var v=open()){
            dialogs(()->v.editor().remove(null),()->v.selector().getSelectionModel().select(1),true,true);
            assertEquals(ORIGINAL,Files.readString(root.resolve("10001.json")));assertEquals(ORIGINAL,Files.readString(root.resolve("10002.json")));
            assertEquals(10002,v.selector().getValue().getNo());try(var p=Files.list(root)){assertEquals(2,p.count());}
        }return null;});
    }
    @Test void clearedSelectionDuringConfirmationDoesNotRetireTheOldTarget()throws Exception{
        Files.writeString(root.resolve("10001.json"),ORIGINAL,StandardOpenOption.CREATE_NEW);
        fx(()->{try(var v=open()){
  dialogs(()->v.editor().remove(null),()->v.selector().getSelectionModel().clearSelection(),true,true);
  assertEquals(ORIGINAL,Files.readString(root.resolve("10001.json")));assertNull(v.selector().getValue());
  try(var p=Files.list(root)){assertEquals(1,p.count());}
        }return null;});
    }
    @Test void cancellingAfterSelectionChangesDoesNotRunTheOldOrNewCommand()throws Exception{
        Files.writeString(root.resolve("10001.json"),ORIGINAL,StandardOpenOption.CREATE_NEW);
        Files.writeString(root.resolve("10002.json"),ORIGINAL,StandardOpenOption.CREATE_NEW);
        fx(()->{try(var v=open()){
  dialogs(()->v.editor().remove(null),()->v.selector().getSelectionModel().select(1),false);
  assertEquals(ORIGINAL,Files.readString(root.resolve("10001.json")));assertEquals(ORIGINAL,Files.readString(root.resolve("10002.json")));
  assertEquals(10002,v.selector().getValue().getNo());try(var p=Files.list(root)){assertEquals(2,p.count());}
        }return null;});
    }
}
