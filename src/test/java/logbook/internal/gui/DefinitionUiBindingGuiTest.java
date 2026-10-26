package logbook.internal.gui;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import logbook.core.GameDefinitionRuntime;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
@EnabledIfSystemProperty(named="logbook.gui.tests",matches="true")
@Timeout(20)
class DefinitionUiBindingGuiTest {
    @BeforeAll static void start()throws Exception{FxTestSupport.start();}
    @Test void windowLifecycleReleasesSubscriptionsAndQueuedRefreshCannotResurrectClosedWindow()throws Exception{
        String old=System.getProperty("logbook.data.root");System.clearProperty("logbook.data.root");
        var reset=GameDefinitionRuntime.class.getDeclaredMethod("stopForTest");reset.setAccessible(true);reset.invoke(null);
        try{
            GameDefinitionRuntime.initialize();AtomicInteger calls=new AtomicInteger();
            Stage stage=FxTestSupport.fx(()->{Stage s=new Stage();s.setScene(new Scene(new StackPane(),200,80));return s;});
            DefinitionUiBinding binding=FxTestSupport.fx(()->new DefinitionUiBinding(stage,calls::incrementAndGet));
            try{
                FxTestSupport.fx(()->{stage.show();return null;});FxTestSupport.fx(()->null);assertEquals(1,calls.get());
                FxTestSupport.fx(()->{stage.hide();stage.show();stage.hide();return null;});FxTestSupport.fx(()->null);assertEquals(1,calls.get());
                FxTestSupport.fx(()->{stage.show();return null;});FxTestSupport.fx(()->null);assertEquals(2,calls.get());
                FxTestSupport.fx(()->{binding.close();binding.close();stage.hide();stage.show();return null;});FxTestSupport.fx(()->null);assertEquals(2,calls.get());
            }finally{FxTestSupport.fx(()->{binding.close();stage.close();return null;});}
        }finally{reset.invoke(null);if(old==null)System.clearProperty("logbook.data.root");else System.setProperty("logbook.data.root",old);}
    }
}
