package local.kancolle.kqs;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.*;

/** One explicit preview/copy/save flow shared by every available export domain. */
public final class InformationShareWindow {
    private InformationShareWindow() { }
    public static void open(Window owner) {
        Stage stage=new Stage(); stage.initOwner(owner); stage.setTitle("情報の共有・出力");
        ComboBox<String> domain=new ComboBox<>(); domain.getItems().add("全情報");domain.setValue("全情報");
        ComboBox<String> format=new ComboBox<>();format.getItems().addAll("JSON","CSV");format.setValue("JSON");
        TextArea preview=new TextArea();preview.setEditable(false);preview.setWrapText(false);
        Label status=new Label("取得中…"); Button refresh=new Button("再取得"),copy=new Button("コピー"),save=new Button("保存…");
        Map<String,Object> snapshot=new LinkedHashMap<>();
        Runnable render=()->{
            Object value="全情報".equals(domain.getValue())?snapshot:snapshot.get(domain.getValue());
            preview.setText("CSV".equals(format.getValue())?InformationExport.csv(value):InformationExport.json(value));
        };
        Runnable capture=()->{
            refresh.setDisable(true);copy.setDisable(true);save.setDisable(true);
            CompletableFuture.supplyAsync(()->{try{return InformationExport.capture();}catch(Exception e){throw new java.util.concurrent.CompletionException(e);}})
                .whenComplete((data,error)->Platform.runLater(()->{
                    refresh.setDisable(false);
                    if(error!=null){preview.clear();status.setText("取得失敗: "+error.getCause());return;}
                    snapshot.clear();snapshot.putAll(data);domain.getItems().setAll("全情報");domain.getItems().addAll(data.keySet());domain.setValue("全情報");
                    render.run();copy.setDisable(false);save.setDisable(false);status.setText("取得済み。未観測情報は復元されません。共有前に内容を確認してください。");
                }));
        };
        refresh.setOnAction(e->capture.run());domain.setOnAction(e->{if(!snapshot.isEmpty())render.run();});format.setOnAction(e->{if(!snapshot.isEmpty())render.run();});
        copy.setOnAction(e->{ClipboardContent content=new ClipboardContent();content.putString(preview.getText());status.setText(Clipboard.getSystemClipboard().setContent(content)?"コピーしました":"コピーできませんでした");});
        save.setOnAction(e->{FileChooser chooser=new FileChooser();chooser.setInitialFileName("logbook-information."+format.getValue().toLowerCase(Locale.ROOT));var file=chooser.showSaveDialog(stage);if(file!=null)try{Files.writeString(file.toPath(),preview.getText(),StandardCharsets.UTF_8);status.setText("保存しました");}catch(Exception error){status.setText("保存失敗: "+error.getMessage());}});
        VBox box=new VBox(8,new HBox(8,domain,format,refresh,copy,save),status,preview);box.setPadding(new javafx.geometry.Insets(12));VBox.setVgrow(preview,Priority.ALWAYS);
        stage.setScene(new Scene(box,960,680));stage.show();capture.run();
    }
}
