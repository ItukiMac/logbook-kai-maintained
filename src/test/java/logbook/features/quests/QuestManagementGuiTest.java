package logbook.features.quests;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.stage.*;
import logbook.core.quest.*;
import logbook.core.quest.QuestLedger.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
@EnabledIfSystemProperty(named="logbook.gui.tests",matches="true")
@Timeout(30)
class QuestManagementGuiTest {
    @TempDir Path root;
    final Key key=new Key(1,"regular","once");
    @BeforeAll static void start() throws Exception {
        var latch=new CountDownLatch(1);try{Platform.startup(()->{Platform.setImplicitExit(false);latch.countDown();});}catch(IllegalStateException e){Platform.runLater(latch::countDown);}assertTrue(latch.await(10,TimeUnit.SECONDS));
    }
    static <T>T fx(Callable<T> call)throws Exception{var task=new FutureTask<T>(call);Platform.runLater(task);return task.get(15,TimeUnit.SECONDS);}
    static void await(Callable<Boolean> condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!fx(condition)){if(System.nanoTime()>end)fail("UI did not settle");Thread.sleep(20);}}
    Stage open()throws Exception{return fx(()->{
        new QuestManagementWindow(){
            protected QuestManagement.Table loadTable()throws java.io.IOException{return QuestManagement.project(new QuestLedger(root).snapshot(),Map.of(key,"fixture"));}
            protected void correct(long revision,Key key,State state,Instant when,String reason)throws java.io.IOException{new QuestLedger(root).append(revision,UUID.randomUUID().toString(),key,state,Source.MANUAL,when,reason);}
        }.open(null);
        return (Stage)Window.getWindows().stream().filter(w->w instanceof Stage s && s.getTitle().equals("任務管理表・完了履歴")).findFirst().orElseThrow();
    });}
    @SuppressWarnings("unchecked") void submit(Stage stage)throws Exception{fx(()->{
        var pane=stage.getScene().getRoot();((TableView<?>)pane.lookup("#questManagementTable")).getSelectionModel().select(0);
        ((TextField)pane.lookup("#questCorrectionReason")).setText("offline completion");((ComboBox<State>)pane.lookup("#questState")).setValue(State.CLAIMED);
        Platform.runLater(()->{for(var window:List.copyOf(Window.getWindows()))if(window.getScene()!=null && window.getScene().getRoot() instanceof DialogPane dialog)((Button)dialog.lookupButton(ButtonType.OK)).fire();});
        ((Button)pane.lookup("#questCorrect")).fire();return null;
    });}
    @Test void manualCorrectionUsesRealDialogAndSurvivesReopen()throws Exception {
        Stage stage=open();try{await(()->!((Button)stage.getScene().lookup("#questCorrect")).isDisabled());submit(stage);
            await(()->new QuestLedger(root).snapshot().latest(key,Source.MANUAL).isPresent());
            assertEquals(State.CLAIMED,new QuestLedger(root).snapshot().latest(key,Source.MANUAL).orElseThrow().state());
        }finally{fx(()->{stage.close();return null;});}
    }
    @Test void staleEditorDoesNotOverwriteLaterObservation()throws Exception {
        Stage stage=open();try{await(()->!((Button)stage.getScene().lookup("#questCorrect")).isDisabled());
            new QuestLedger(root).append(0,"new-game",key,State.AVAILABLE,Source.GAME,Instant.now(),"");submit(stage);
            await(()->((Label)stage.getScene().lookup("#questStatus")).getText().startsWith("更新できません"));
            assertEquals(1,new QuestLedger(root).snapshot().revision());
        }finally{fx(()->{stage.close();return null;});}
    }
}
