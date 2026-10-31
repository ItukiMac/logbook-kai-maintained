package local.kancolle.kqs;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.stage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
@EnabledIfSystemProperty(named="logbook.gui.tests",matches="true")
@Timeout(30)
class InformationShareGuiTest {
    @BeforeAll static void start()throws Exception{var latch=new CountDownLatch(1);try{Platform.startup(()->{Platform.setImplicitExit(false);latch.countDown();});}catch(IllegalStateException e){Platform.runLater(latch::countDown);}assertTrue(latch.await(10,TimeUnit.SECONDS));}
    static <T>T fx(Callable<T> call)throws Exception{var task=new FutureTask<T>(call);Platform.runLater(task);return task.get(15,TimeUnit.SECONDS);}
    static void await(Callable<Boolean> condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!fx(condition)){if(System.nanoTime()>end)fail("UI did not settle");Thread.sleep(20);}}
    @Test void realWindowShowsJsonAndCsvFromSameCapturedSnapshot()throws Exception{
        Stage stage=fx(()->{InformationShareWindow.open(null,()->Map.of("ships",List.of(Map.of("id",1,"name","fixture"))));return(Stage)Window.getWindows().stream().filter(w->w instanceof Stage s&&s.getTitle().equals("情報の共有・出力")).findFirst().orElseThrow();});
        try{await(()->((TextArea)stage.getScene().lookup(".text-area")).getText().contains("fixture"));
            fx(()->{for(var node:stage.getScene().getRoot().lookupAll(".combo-box"))if(node instanceof ComboBox<?> box&&box.getItems().contains("CSV"))((ComboBox)box).setValue("CSV");assertTrue(((TextArea)stage.getScene().lookup(".text-area")).getText().startsWith("path,type,value"));return null;});
        }finally{fx(()->{stage.close();return null;});}
    }
}
