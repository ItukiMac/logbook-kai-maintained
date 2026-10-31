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

public class QuestManagementWindow implements QuestManagementUi {
    protected QuestManagement.Table loadTable() throws java.io.IOException { return QuestManagement.snapshot(); }
    protected void correct(long revision,Key key,State state,Instant when,String reason) throws java.io.IOException { QuestManagement.correct(revision,key,state,when,reason); }
    protected void register(java.util.List<QuestManagement.CatalogEntry> entries) throws java.io.IOException { QuestManagement.register(QuestManagement.ledger(),entries); }
    private static String label(State state) {
        if(state==null)return "";
        return switch(state){case UNKNOWN->"不明";case AVAILABLE->"未受諾";case ACCEPTED->"受諾中";case ACHIEVED_UNCLAIMED->"達成・報酬未受領";case CLAIMED->"報酬受領済み";case EXPIRED->"期限終了";};
    }
    private static void column(TableView<QuestManagement.Row> table,String title,Function<QuestManagement.Row,String> value) {
        TableColumn<QuestManagement.Row,String> column=new TableColumn<>(title);column.setCellValueFactory(c->new ReadOnlyStringWrapper(value.apply(c.getValue())));column.setPrefWidth(140);table.getColumns().add(column);
    }
    @Override public void open(Window owner) {
        Stage stage=new Stage();stage.initOwner(owner);stage.setTitle("任務管理表・完了履歴");
        TableView<QuestManagement.Row> table=new TableView<>();
        column(table,"ID",r->""+r.key().questId());column(table,"任務",QuestManagement.Row::title);
        column(table,"開催",r->r.key().edition());column(table,"周期",r->r.key().period());
        column(table,"観測",r->label(r.observed()));column(table,"手動",r->label(r.manual()));column(table,"管理状態",r->label(r.effective()));column(table,"要確認",r->r.conflict()?"後続観測と不一致":"");column(table,"日時",QuestManagement.Row::recordedAt);column(table,"出典",QuestManagement.Row::source);
        TextField id=new TextField(),edition=new TextField("unresolved"),period=new TextField("unresolved"),reason=new TextField(),time=new TextField(Instant.now().toString());
        id.setPromptText("任務ID");edition.setPromptText("開催識別子");period.setPromptText("周期識別子");reason.setPromptText("変更理由（必須）");
        ComboBox<State> state=new ComboBox<>();state.getItems().addAll(State.values());state.setValue(State.UNKNOWN);state.setConverter(new javafx.util.StringConverter<State>() {
            public String toString(State value){return label(value);}public State fromString(String value){throw new UnsupportedOperationException();}
        });
        Label status=new Label();Button refresh=new Button("再読込"),apply=new Button("手動更新・追加"),history=new Button("履歴");
        Button importCatalog=new Button("任務台帳を読込…");
        table.setId("questManagementTable");id.setId("questId");edition.setId("questEdition");period.setId("questPeriod");reason.setId("questCorrectionReason");state.setId("questState");apply.setId("questCorrect");status.setId("questStatus");
        QuestManagement.Table[] current={null};
        TextField filter=new TextField();filter.setPromptText("ID・任務名・状態で絞り込み");
        filter.textProperty().addListener((o,a,text)->{if(current[0]!=null)table.getItems().setAll(current[0].rows().stream().filter(row->(row.key().questId()+" "+row.title()+" "+label(row.effective())+" "+row.key().edition()).toLowerCase(java.util.Locale.ROOT).contains(text.toLowerCase(java.util.Locale.ROOT))).toList());});
        Runnable reload=()->{
            apply.setDisable(true);refresh.setDisable(true);
            CompletableFuture.supplyAsync(()->{try{return loadTable();}catch(Exception e){throw new java.util.concurrent.CompletionException(e);}}).whenComplete((result,error)->Platform.runLater(()->{
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
                CompletableFuture.runAsync(()->{try{correct(revision,key,chosen,when,why);}catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}}).whenComplete((ignored,error)->Platform.runLater(()->{if(error!=null){status.setText("更新できません。再読込して確認してください: "+error.getCause());apply.setDisable(false);}else reload.run();}));
            } catch(Exception error){status.setText("入力エラー: "+error.getMessage());}
        });
        history.setOnAction(e->{if(current[0]!=null){TextArea text=new TextArea(logbook.internal.json.JsonMappers.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(current[0].history()));text.setEditable(false);Stage details=new Stage();details.initOwner(stage);details.setTitle("任務変更履歴");details.setScene(new Scene(text,850,550));details.show();}});
        importCatalog.setOnAction(e->{
            FileChooser chooser=new FileChooser();chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("任務台帳 JSON","*.json"));var file=chooser.showOpenDialog(stage);if(file==null)return;
            try {
                var entries=QuestManagement.readCatalog(file.toPath());
                Alert confirm=new Alert(Alert.AlertType.CONFIRMATION,entries.size()+" 件の任務定義を登録します。状態は不明から開始し、既存の履歴・手動状態は保持します。",ButtonType.OK,ButtonType.CANCEL);confirm.initOwner(stage);
                if(confirm.showAndWait().orElse(ButtonType.CANCEL)!=ButtonType.OK)return;
                importCatalog.setDisable(true);
                CompletableFuture.runAsync(()->{try{register(entries);}catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}}).whenComplete((ignored,error)->Platform.runLater(()->{
                    importCatalog.setDisable(false);if(error!=null)status.setText("読込中断。保存済みの行は保持され、同じファイルで再開できます: "+error.getCause());else reload.run();
                }));
            }catch(Exception error){status.setText("台帳を読み込めません: "+error.getMessage());}
        });
        refresh.setOnAction(e->reload.run());VBox root=new VBox(8,new HBox(8,refresh,history,importCatalog,filter),status,table,new HBox(8,id,edition,period,state),new HBox(8,time,reason,apply));root.setPadding(new javafx.geometry.Insets(12));VBox.setVgrow(table,Priority.ALWAYS);
        stage.setScene(new Scene(root,1200,720));stage.show();reload.run();
    }
}
