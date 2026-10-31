package logbook.features.quests;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import logbook.core.quest.QuestManagement;
import logbook.core.quest.QuestLedger.*;
import logbook.plugin.api.QuestManagementUi;

public final class QuestManagementWindow implements QuestManagementUi {
    private static void column(TableView<QuestManagement.Row> table,String title,Function<QuestManagement.Row,String> value) {
        TableColumn<QuestManagement.Row,String> column=new TableColumn<>(title);column.setCellValueFactory(c->new ReadOnlyStringWrapper(value.apply(c.getValue())));column.setPrefWidth(140);table.getColumns().add(column);
    }
    @Override public void open(Window owner) {
        Stage stage=new Stage();stage.initOwner(owner);stage.setTitle("任務管理表・完了履歴");
        TableView<QuestManagement.Row> table=new TableView<>();
        column(table,"ID",r->""+r.key().questId());column(table,"任務",QuestManagement.Row::title);
        column(table,"開催",r->r.key().edition());column(table,"周期",r->r.key().period());
        column(table,"観測",r->r.observed().name());column(table,"手動",r->r.manual()==null?"":r.manual().name());column(table,"管理状態",r->r.effective().name());column(table,"要確認",r->r.conflict()?"後続観測と不一致":"");column(table,"日時",QuestManagement.Row::recordedAt);
        TextField id=new TextField(),edition=new TextField("unresolved"),period=new TextField("unresolved"),reason=new TextField(),time=new TextField(Instant.now().toString());
        id.setPromptText("任務ID");edition.setPromptText("開催識別子");period.setPromptText("周期識別子");reason.setPromptText("変更理由（必須）");
        ComboBox<State> state=new ComboBox<>();state.getItems().addAll(State.values());state.setValue(State.UNKNOWN);
        Label status=new Label();Button refresh=new Button("再読込"),apply=new Button("手動更新・追加"),history=new Button("履歴");
        QuestManagement.Table[] current={null};
        Runnable reload=()->{
            apply.setDisable(true);refresh.setDisable(true);
            CompletableFuture.supplyAsync(()->{try{return QuestManagement.snapshot();}catch(Exception e){throw new java.util.concurrent.CompletionException(e);}}).whenComplete((result,error)->Platform.runLater(()->{
                refresh.setDisable(false);if(error!=null){current[0]=null;status.setText("読込失敗: "+error.getCause());return;}
                current[0]=result;table.getItems().setAll(result.rows());status.setText(result.coverage());apply.setDisable(false);
            }));
        };
        table.getSelectionModel().selectedItemProperty().addListener((o,a,row)->{if(row!=null){id.setText(""+row.key().questId());edition.setText(row.key().edition());period.setText(row.key().period());state.setValue(row.effective());}});
        apply.setOnAction(e->{
            if(current[0]==null)return;
            try {
                Key key=new Key(Integer.parseInt(id.getText()),edition.getText(),period.getText());Instant when=Instant.parse(time.getText());String why=reason.getText();
                if(why.isBlank())throw new IllegalArgumentException("変更理由を入力してください");
                Alert confirm=new Alert(Alert.AlertType.CONFIRMATION,"任務 "+key.questId()+" を "+state.getValue()+" に記録します。ゲーム内の状態は変更しません。",ButtonType.OK,ButtonType.CANCEL);confirm.initOwner(stage);
                if(confirm.showAndWait().orElse(ButtonType.CANCEL)!=ButtonType.OK)return;
                long revision=current[0].revision();State chosen=state.getValue();apply.setDisable(true);
                CompletableFuture.runAsync(()->{try{QuestManagement.correct(revision,key,chosen,when,why);}catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}}).whenComplete((ignored,error)->Platform.runLater(()->{if(error!=null){status.setText("更新できません。再読込して確認してください: "+error.getCause());apply.setDisable(false);}else reload.run();}));
            } catch(Exception error){status.setText("入力エラー: "+error.getMessage());}
        });
        history.setOnAction(e->{if(current[0]!=null){TextArea text=new TextArea(logbook.internal.json.JsonMappers.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(current[0].history()));text.setEditable(false);Stage details=new Stage();details.initOwner(stage);details.setTitle("任務変更履歴");details.setScene(new Scene(text,850,550));details.show();}});
        refresh.setOnAction(e->reload.run());VBox root=new VBox(8,new HBox(8,refresh,history),status,table,new HBox(8,id,edition,period,state),new HBox(8,time,reason,apply));root.setPadding(new javafx.geometry.Insets(12));VBox.setVgrow(table,Priority.ALWAYS);
        stage.setScene(new Scene(root,1200,720));stage.show();reload.run();
    }
}
