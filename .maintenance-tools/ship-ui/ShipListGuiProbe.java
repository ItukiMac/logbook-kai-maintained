package logbook.internal.gui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import javafx.animation.Animation;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.stage.Stage;
import logbook.bean.AppConfig;
import logbook.bean.AppShipTableConfig;
import logbook.bean.DeckPort;
import logbook.bean.DeckPortCollection;
import logbook.bean.ParameterFilterConfig;
import logbook.bean.ShipCollection;
import logbook.bean.ShipMstCollection;
import logbook.core.GameDefinitionRuntime;
import logbook.core.LogBookCoreServices;
import logbook.internal.Config;
import logbook.internal.kancolle.Operator;
import logbook.plugin.api.ShipTableUiV1;
import org.controlsfx.control.ToggleSwitch;

/** Real product ship window, synthetic data only, no browser/game/private profile. */
public final class ShipListGuiProbe {
    private static void check(boolean value, String text) { if (!value) throw new AssertionError(text); }
    @SuppressWarnings("unchecked")
    private static <T> T field(Object object, String name) throws Exception {
        var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return (T)f.get(object);
    }
    private static List<Integer> ids(ShipTablePane pane) throws Exception {
        TableView<ShipItem> table=field(pane,"table");return table.getItems().stream().map(ShipItem::getId).toList();
    }
    private static ShipTablePane select(TabPane tabs, String name) {
        Tab tab=tabs.getTabs().stream().filter(t->name.equals(t.getText())).findFirst().orElseThrow();
        tabs.getSelectionModel().select(tab);
        var pane=(ShipTablePane)tab.getContent();pane.enable();pane.update();return pane;
    }
    public static void main(String[] args) throws Exception {
        check(args.length==1 && args[0].equals("new-synthetic-ship-list-only"),"Explicit isolated mode required");
        check(!Files.exists(Path.of("config")) && !Files.exists(Path.of("history.csv")),"Existing runtime refused");
        byte[] sentinel="untouched synthetic history\n".getBytes(StandardCharsets.UTF_8);
        Files.write(Path.of("history.csv"),sentinel,StandardOpenOption.CREATE_NEW);
        var failures=new CopyOnWriteArrayList<Throwable>();
        Thread.setDefaultUncaughtExceptionHandler((t,e)->{failures.add(e);e.printStackTrace();});
        LogBookCoreServices.init(List.of());GameDefinitionRuntime.initialize();
        // Reuse the prior tested two-ship fixture without executing its GUI/main.
        var seed=ShipLabelGuiProbe.class.getDeclaredMethod("fixture");seed.setAccessible(true);seed.invoke(null);
        AppConfig.get().setAllDecksTab(true);AppConfig.get().setDeckTabs(true);AppConfig.get().setLabelTabs(true);
        ShipCollection.get().getShipMap().get(2).setLv(20);
        ShipMstCollection.get().getShipMap().get(1000001).setAftershipid(1000002);
        ShipMstCollection.get().getShipMap().get(1000001).setAfterlv(20);
        ShipMstCollection.get().getShipMap().get(1000002).setAftershipid(0);
        ShipMstCollection.get().getShipMap().get(1000002).setAfterlv(0);
        for (int id:List.of(1,2)) {
            var fleet=new DeckPort();fleet.setId(id);fleet.setName("fixture-fleet-"+id);
            fleet.setShip(List.of(id,-1,-1,-1,-1,-1));fleet.setMission(List.of(0L,0L,0L));
            DeckPortCollection.get().getDeckPortMap().put(id,fleet);
        }
        var start=new CompletableFuture<Void>();Platform.startup(()->start.complete(null));start.get(20,TimeUnit.SECONDS);
        Platform.setImplicitExit(false);
        try {
            runFx(ShipListGuiProbe::window);
            runFx(()->{});check(failures.isEmpty(),"Uncaught GUI failure: "+failures);
            Config.getDefault().store();
            var restored=new Config(Path.of("config")).get(AppShipTableConfig.class,AppShipTableConfig::new);
            var all=restored.getTabConfig().get("全員");
            check(all!=null && all.isLabelEnabled() && "first".equals(all.getLabelValue()),"Saved old filter keys did not round-trip");
            check(Arrays.equals(sentinel,Files.readAllBytes(Path.of("history.csv"))),"Original history changed");
            System.out.println("SHIP_LIST_PRODUCT_GUI_OK product_distribution=true tabs_verified=true filters_verified=true legacy_exports_verified=true timeline_stopped=true settings_reread=true original_history_unchanged=true production_accessed=false");
        } finally { Platform.exit(); }
    }
    @SuppressWarnings("unchecked")
    private static void window() throws Exception {
        for (Class<?> type:List.of(ShipController.class,ShipTablePane.class))
            check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).getFileName().toString()
                    .equals("logbook-feature-status.jar"),"Host fallback for "+type.getName());
        check(Path.of(StatisticsPane.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getFileName().toString()
                .equals("logbook-feature-charts.jar"),"Statistics no longer belongs to charts");
        var loader=new FXMLLoader(ShipController.class.getResource("/logbook/gui/ship.fxml"));
        loader.setClassLoader(ShipController.class.getClassLoader());Parent root=loader.load();
        ShipController controller=loader.getController();Stage stage=new Stage();controller.bindWindow(stage);
        try {
            stage.setScene(new Scene(root,1100,700));stage.show();root.applyCss();root.layout();
            TabPane tabs=(TabPane)loader.getNamespace().get("tab");
            check(tabs.getTabs().stream().anyMatch(t->"統計".equals(t.getText())),"Statistics tab lost");
            var all=select(tabs,"全員");check(ids(all).equals(List.of(1,2)),"All roster ordering changed");
            check(ids(select(tabs,"全艦隊")).equals(List.of(1,2)),"All fleet membership changed");
            check(ids(select(tabs,"fixture-fleet-1")).equals(List.of(1)),"First fleet tab lost");
            check(ids(select(tabs,"fixture-fleet-2")).equals(List.of(2)),"Second fleet tab lost");
            check(ids(select(tabs,"first")).equals(List.of(1)),"Manual label tab lost");
            check(ids(select(tabs,"second")).equals(List.of(2)),"Second label tab lost");
            all=select(tabs,"全員");
            ((TextField)field(all,"textValue")).setText("synthetic ship 2");
            ToggleSwitch text=field(all,"textFilter");text.setSelected(true);
            check(ids(all).equals(List.of(2)),"Text filter failed");text.setSelected(false);
            List<ShipTableUiV1.ParameterFilter> filters=field(all,"parameterFilters");
            check(filters.size()==3,"Parameter widgets lost");
            var parameter=new ParameterFilterConfig();parameter.setEnabled(true);parameter.setName("Lv");
            parameter.setValue(15);parameter.setType(Operator.GE);filters.getFirst().loadConfig(parameter);
            check(ids(all).equals(List.of(2)),"Public numeric-filter adapter changed Lv>=15");
            parameter.setEnabled(false);filters.getFirst().loadConfig(parameter);check(ids(all).size()==2,"Numeric filter cannot be disabled");
            ToggleSwitch labels=field(all,"labelFilter");ChoiceBox<String> label=field(all,"labelValue");
            label.setValue("first");labels.setSelected(true);check(ids(all).equals(List.of(1)),"Label filter failed");
            labels.setSelected(false);
            TableView<ShipItem> table=field(all,"table");TableColumn<ShipItem,Integer> level=field(all,"lv");
            level.setSortType(TableColumn.SortType.DESCENDING);table.getSortOrder().setAll(level);table.sort();
            check(ids(all).equals(List.of(2,1)),"Level sorting failed");
            table.getSelectionModel().clearAndSelect(0);all.copy();
            check(Clipboard.getSystemClipboard().getString().contains("synthetic ship 2"),"Selected TSV copy lost names");
            all.kancolleFleetanalysis();
            var json=logbook.internal.json.JsonMappers.builder().build().readTree(Clipboard.getSystemClipboard().getString());
            check(json.size()==2 && json.get(0).get("api_ship_id").asInt()==1000001
                    && json.get(1).get("api_lv").asInt()==20,"Legacy fleetanalysis JSON changed");
            check(json.get(0).has("api_kyouka") && json.get(0).has("api_exp") && json.get(0).has("api_slot_ex"),"Export keys changed");
            ShipTablePane.KanmusuList.copyAll();
            check(".2|1000001:20.2,10.1".equals(Clipboard.getSystemClipboard().getString()),"Legacy remodel export changed");
            all.deckBuilderSelectionCopy();
            json=logbook.internal.json.JsonMappers.builder().build().readTree(Clipboard.getSystemClipboard().getString());
            check(json.get("f1").get("s1").get("id").asInt()==1000002,"Selected deck-builder export changed");
            label.setValue("first");labels.setSelected(true);
        } finally {
            stage.close();
            Timeline timer=field(controller,"timeline");
            check(timer.getStatus()==Animation.Status.STOPPED,"Window close did not stop refresh timeline");
        }
    }
    @FunctionalInterface private interface Action {void run() throws Exception;}
    private static void runFx(Action action) throws Exception {
        var done=new CompletableFuture<Void>();Platform.runLater(()->{try{action.run();done.complete(null);}catch(Throwable e){done.completeExceptionally(e);}});
        done.get(45,TimeUnit.SECONDS);
    }
}
