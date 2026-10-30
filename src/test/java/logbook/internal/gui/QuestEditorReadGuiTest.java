package logbook.internal.gui;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import logbook.bean.AppQuest;
import logbook.core.QuestListReadService;
import logbook.plugin.api.QuestEditorDataV1;
import logbook.plugin.api.QuestEditorDataV1.Choice;
import logbook.plugin.api.QuestEditorDataV1.Selection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Real unchanged FXML with only new synthetic directories; no live collection or init/close. */
@EnabledIfSystemProperty(named="logbook.gui.tests",matches="true")
@Timeout(30)
class QuestEditorReadGuiTest {
    @TempDir Path root;
    AppQuest source=source();
    static AppQuest source() {
        var raw=new logbook.bean.QuestList.Quest();raw.setNo(900001);raw.setCategory(2);raw.setType(1);raw.setTitle("captured title");
        var q=new AppQuest();q.setNo(900001);q.setQuest(raw);q.setActive(true);return q;
    }
    final class Editor extends QuestConditionEditController {
        @Override Path questDirectory(){return root;}
        @Override Selection editorChoices(boolean custom) {
            if(custom)return QuestEditorDataV1.choices(true);
            var q=QuestListReadService.copy(source);
            return new Selection(List.of(new Choice(q.no(),q.category(),q.type(),q.title())),List.of());
        }
    }
    @BeforeAll static void startFx()throws Exception {
        var ready=new CountDownLatch(1);
        try{Platform.startup(()->{Platform.setImplicitExit(false);ready.countDown();});}catch(IllegalStateException started){Platform.runLater(ready::countDown);}
        assertTrue(ready.await(10,TimeUnit.SECONDS));
    }
    static <T>T fx(Callable<T> work)throws Exception{var task=new FutureTask<T>(work);Platform.runLater(task);return task.get(20,TimeUnit.SECONDS);}
    record View(Stage stage,Parent pane,QuestConditionEditController editor)implements AutoCloseable {
        @SuppressWarnings("unchecked") ComboBox<Choice> selector(){return (ComboBox<Choice>)pane.lookup("#questSelector");}
        TextField text(String id){return (TextField)pane.lookup("#"+id);}
        void custom(){((RadioButton)pane.lookup("#customQuestRadio")).fire();selector().getSelectionModel().select(0);}
        void normal(){((RadioButton)pane.lookup("#normalQuestRadio")).fire();selector().getSelectionModel().select(0);}
        public void close(){stage.close();}
    }
    View open()throws Exception {
        var loader=new FXMLLoader(getClass().getResource("/logbook/gui/quest_condition_edit.fxml"));loader.setControllerFactory(t->new Editor());
        Parent pane=loader.load();Stage stage=new Stage();stage.setScene(new Scene(pane));stage.show();return new View(stage,pane,loader.getController());
    }
    @Test void selectedIdentityAndFormDefaultsAreDetachedFromLaterSourceMutation()throws Exception {
        fx(()->{try(var v=open()){
            v.normal();var selected=v.selector().getValue();source.setNo(900099);source.getQuest().setNo(900099);source.getQuest().setCategory(4);source.getQuest().setTitle("changed");
            assertEquals(900001,selected.id());assertEquals("captured title",selected.title());assertEquals("900001: captured title",v.selector().getConverter().toString(selected));
            assertEquals(logbook.bean.AppQuestCondition.Type.出撃,v.editor().readForm().getType());assertEquals("デイリー",v.editor().readForm().getResetType());
            v.editor().saveDocument();assertTrue(Files.isRegularFile(root.resolve("900001.json")));assertFalse(Files.exists(root.resolve("900099.json")));
        }return null;});
    }
    @Test void switchingModesClearsSelectionAndCannotSaveOldUnsavedForm()throws Exception {
        String before="/*keep*/{\"customName\":\"original\",\"conditions\":[]}";Files.writeString(root.resolve("10001.json"),before,StandardOpenOption.CREATE_NEW);
        fx(()->{try(var v=open()){
            v.custom();v.text("customNameField").setText("unsaved");((RadioButton)v.pane().lookup("#normalQuestRadio")).fire();
            assertNull(v.selector().getValue());assertThrows(IllegalStateException.class,()->v.editor().saveDocument());assertEquals(before,Files.readString(root.resolve("10001.json")));
            assertTrue(((Button)v.pane().lookup("#removeBtn")).isDisabled());
        }return null;});
    }
    static String complex() {return "/*original*/{\"resetType\":\"イヤリー\",\"yearlyResetMonth\":6,\"customName\":\"loaded\",\"startDate\":\"2026-01-01 00:00:00\",\"future\":{\"keep\":1},\"filter\":{\"area\":[\"1-1\"],\"deckIds\":[3,1],\"noRetreat\":true,\"fleet\":{\"operator\":\"OR\",\"conditions\":[{\"stype\":[\"駆逐艦\",\"軽巡洋艦\"],\"count\":2,\"operator\":\"GE\"},{\"name\":[\"赤城\"],\"count\":1,\"operator\":\"EQ\"}]}},\"conditions\":[{\"area\":[\"1-1\"],\"rank\":[\"S\",\"A\"],\"boss\":true,\"count\":3,\"futureGoal\":7}]}";}
    @Test void immutableProjectionPopulatesFleetFilterAndTimingAndNoEditKeepsExactBytes()throws Exception {
        Files.writeString(root.resolve("10001.json"),complex(),StandardOpenOption.CREATE_NEW);
        fx(()->{try(var v=open()){
            v.custom();var form=v.editor().readForm();assertEquals("loaded",form.getCustomName());assertEquals("イヤリー",form.getResetType());assertEquals(6,form.getYearlyResetMonth());
            assertEquals("1,3",v.text("filterDeckIds").getText());assertTrue(form.getFilter().getNoRetreat());
            assertEquals("OR",form.getFilter().getFleet().getOperator());assertEquals(2,form.getFilter().getFleet().getConditions().getFirst().getCount());
            assertEquals(Set.of("赤城"),form.getFilter().getFleet().getConditions().get(1).getName());assertEquals(3,form.getConditions().getFirst().getCount());
            assertNull(v.editor().saveDocument());assertEquals(complex(),Files.readString(root.resolve("10001.json")));
        }return null;});
    }
    @Test void editingProjectedTitleKeepsUnrepresentedFieldsAndVerifiedOriginalBackup()throws Exception {
        Files.writeString(root.resolve("10001.json"),complex(),StandardOpenOption.CREATE_NEW);
        fx(()->{try(var v=open()){
            v.custom();v.text("customNameField").setText("changed title");Path backup=v.editor().saveDocument();assertEquals(complex(),Files.readString(backup));
            var result=QuestEditorDocument.parse(Files.readAllBytes(root.resolve("10001.json")));assertEquals("changed title",result.path("customName").asText());
            assertEquals(1,result.path("future").path("keep").asInt());assertEquals(7,result.path("conditions").get(0).path("futureGoal").asInt());
        }return null;});
    }
    @Test void invalidProjectionBlocksSaveAndRetainsOriginalWithoutBackupWrites()throws Exception {
        String bad="{\"filter\":{\"deckIds\":[1,1]}}";Files.writeString(root.resolve("10001.json"),bad,StandardOpenOption.CREATE_NEW);
        fx(()->{try(var v=open()){
            v.custom();assertTrue(((Label)v.pane().lookup("#editNotice")).getText().contains("読込に失敗"));
            assertThrows(java.io.IOException.class,()->v.editor().saveDocument());assertEquals(bad,Files.readString(root.resolve("10001.json")));
            try(var files=Files.list(root)){assertEquals(1,files.count());}
        }return null;});
    }
    @Test void customChoicesRemainFiveImmutableValuesWithExistingConverterAndResetOptions()throws Exception {
        fx(()->{try(var v=open()){
            v.custom();assertEquals(List.of(10001,10002,10003,10004,10005),v.selector().getItems().stream().map(Choice::id).toList());
            assertEquals("10001: カスタム任務1",v.selector().getConverter().toString(v.selector().getValue()));
            assertNull(v.selector().getConverter().fromString("10099"));
            assertEquals(QuestEditorDataV1.resetTypes(),((ComboBox<?>)v.pane().lookup("#resetType")).getItems());
        }return null;});
    }
}
