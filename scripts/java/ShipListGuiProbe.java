package logbook.internal.gui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.animation.Animation;
import javafx.animation.Timeline;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.stage.Stage;
import org.controlsfx.control.ToggleSwitch;
import logbook.bean.*;
import logbook.core.LogBookCoreServices;
import logbook.core.GameDefinitionRuntime;
import logbook.internal.Config;
import logbook.internal.kancolle.Operator;
import logbook.plugin.api.ShipListUiV1;
import logbook.plugin.api.FeatureUiV1;

/** New synthetic runtime only. No game requests, user data or native OS certification. */
public final class ShipListGuiProbe {
    private static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
    private static byte[] history="preserved synthetic ship history\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    public static void main(String[] args) throws Exception {
        check(args.length==1 && args[0].equals("synthetic-ship-list-only"),"Explicit fixture mode required");
        check(!Files.exists(Path.of("config")) && !Files.exists(Path.of("history-sentinel.csv")),"Refusing existing runtime");
        Files.write(Path.of("history-sentinel.csv"),history,StandardOpenOption.CREATE_NEW);
        productClass(ShipController.class,"ships");productClass(ShipTablePane.class,"ships");
        productClass(ParameterFilterPane.class,"ships");productClass(StatisticsPane.class,"charts");
        check(ShipItem.class.getProtectionDomain().getCodeSource().getLocation().toString().endsWith("/logbook-kai2-core.jar"),"Legacy shared row type changed owner");
        var errors=new CopyOnWriteArrayList<Throwable>();
        Thread.setDefaultUncaughtExceptionHandler((t,e)->{errors.add(e);e.printStackTrace();});
        LogBookCoreServices.init(List.of());GameDefinitionRuntime.initialize();fixture();
        var ready=new CompletableFuture<Void>();Platform.startup(()->ready.complete(null));ready.get(20,TimeUnit.SECONDS);
        Platform.setImplicitExit(false);
        try {
            fx(ShipListGuiProbe::window);fx(()->{});
            check(errors.isEmpty(),"Unhandled GUI failure: "+errors);
            Config.getDefault().store();
            var saved=new Config(Path.of("config")).get(AppShipTableConfig.class,AppShipTableConfig::new);
            var all=saved.getTabConfig().get("全員");
            check(all!=null && all.isTextEnabled() && "alpha".equals(all.getTextValue()),"Existing per-tab settings did not round-trip");
            check(ShipLabelCollection.get().getLabels().get(1).equals(Set.of("first")),"Viewing/filtering edited labels");
            check(ShipLabelCollection.get().getLabels().get(2).equals(Set.of("second")),"Viewing/filtering edited second labels");
            check(Arrays.equals(history,Files.readAllBytes(Path.of("history-sentinel.csv"))),"Original history changed");
            System.out.println("SHIP_LIST_PRODUCT_GUI_OK product_status_jar=true all_rows=2 ordered_fleet=true label_tabs=true filters=6 numeric_controls=true sorted_copy=true deck_export=true settings_reread=true timeline_stopped=true original_history_unchanged=true production_accessed=false");
        } finally {Platform.exit();LogBookCoreServices.closeContainer();}
    }
    private static void productClass(Class<?> type,String module) {
        check(type.getProtectionDomain().getCodeSource().getLocation().toString().endsWith("/features/logbook-kai2-feature-"+module+".jar"),"Hidden host fallback: "+type);
    }
    private static void fixture() {
        AppConfig.get().setHideShipImageFromShipTablePane(true);AppConfig.get().setHideItemImageFromShipTablePane(true);
        AppConfig.get().setAllDecksTab(true);AppConfig.get().setDeckTabs(true);AppConfig.get().setLabelTabs(true);
        for(int id:List.of(1,2)) {
            int st=id==1?2:3;Stype stype=new Stype();stype.setId(st);stype.setName(id==1?"駆逐艦":"軽巡洋艦");
            StypeCollection.get().getStypeMap().put(st,stype);
            ShipMst master=new ShipMst();master.setId(1000000+id);master.setName(id==1?"alpha":"bravo");master.setYomi(id==1?"あるふぁ":"ぶらぼー");
            master.setSortId(id);master.setStype(st);master.setCtype(id);master.setFuelMax(10);master.setBullMax(10);
            ShipMstCollection.get().getShipMap().put(master.getId(),master);
            Ship ship=new Ship();ship.setId(id);ship.setShipId(master.getId());ship.setLv(id*10);ship.setCond(49);
            ship.setExp(List.of(id*1000,100));ship.setSlot(List.of(-1,-1,-1,-1,-1));ship.setSlotEx(id==1?0:-1);ship.setSlotnum(0);
            ship.setOnslot(List.of(0,0,0,0,0));ship.setNowhp(15);ship.setMaxhp(15);ship.setSoku(10);ship.setLeng(1);
            ship.setFuel(10);ship.setBull(10);ship.setLocked(true);ship.setSallyArea(0);ship.setKyouka(List.of(0,0,0,0,0));
            ship.setKaryoku(List.of(10,20));ship.setRaisou(List.of(10,20));ship.setTaiku(List.of(10,20));
            ship.setSoukou(List.of(10,20));ship.setKaihi(List.of(10,20));ship.setTaisen(List.of(10,20));
            ship.setSakuteki(List.of(10,20));ship.setLucky(List.of(10,20));
            ShipCollection.get().getShipMap().put(id,ship);
            ShipLabelCollection.get().getLabels().put(id,new LinkedHashSet<>(Set.of(id==1?"first":"second")));
        }
        DeckPort fleet=new DeckPort();fleet.setId(1);fleet.setName("synthetic fleet");fleet.setShip(List.of(2,1,-1,-1,-1,-1));
        DeckPortCollection.get().getDeckPortMap().put(1,fleet);
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    private static void window() throws Exception {
        ShipController[] controllers=new ShipController[1];Stage[] stages=new Stage[1];
        ShipListUiV1.open(null,"synthetic ship UI",c->controllers[0]=(ShipController)c,s->stages[0]=s);
        ShipController controller=controllers[0];Stage stage=stages[0];
        check(controller!=null && stage!=null && stage.isShowing(),"Public window entry did not open");
        TabPane tabs=field(controller,"tab");
        try {
            var names=tabs.getTabs().stream().map(Tab::getText).toList();
            check(names.containsAll(List.of("全員","全艦隊","synthetic fleet","first","second","統計")),"Missing existing tabs: "+names);
            ShipTablePane all=select(tabs,"全員");all.enable();all.update();
            TableView<ShipItem> table=field(all,"table");expect(table,1,2);
            check(ShipTablePane.class.getResource("/logbook/gui/ship_table.fxml").toString().contains("logbook-kai2-feature-ships.jar"),"Ship FXML still in host");
            check(ParameterFilterPane.class.getResource("/logbook/gui/param_filter_pane.fxml").toString().contains("logbook-kai2-feature-ships.jar"),"Numeric filter FXML still in host");
            ShipTablePane fleet=select(tabs,"全艦隊");expect(field(fleet,"table"),2,1);
            expect(field(select(tabs,"synthetic fleet"),"table"),2,1);
            expect(field(select(tabs,"first"),"table"),1);expect(field(select(tabs,"second"),"table"),2);
            all=select(tabs,"全員");table=field(all,"table");expect(table,1,2);

            ToggleSwitch text=field(all,"textFilter");TextField input=field(all,"textValue");
            text.setSelected(true);input.setText("alpha");expect(table,1);text.setSelected(false);
            List<ParameterFilterPane.ShipItemParameterFilterPane> numeric=field(all,"parameterFilters");
            check(numeric.size()==3,"Missing numeric controls");
            ParameterFilterConfig cfg=new ParameterFilterConfig();cfg.setEnabled(true);cfg.setName("Lv");cfg.setType(Operator.GE);cfg.setValue(15);
            numeric.getFirst().loadConfig(cfg);expect(table,2);
            cfg.setEnabled(false);numeric.getFirst().loadConfig(cfg);expect(table,1,2);

            ToggleSwitch type=field(all,"typeFilter");var method=ShipTablePane.class.getDeclaredMethod("typeCheckBox");method.setAccessible(true);
            ((List<CheckBox>)method.invoke(all)).forEach(box->box.setSelected(false));((CheckBox)field(all,"destroyer")).setSelected(true);
            type.setSelected(true);expect(table,1);type.setSelected(false);
            ToggleSwitch label=field(all,"labelFilter");ChoiceBox<String> labels=field(all,"labelValue");labels.setValue("second");label.setSelected(true);expect(table,2);label.setSelected(false);
            ToggleSwitch extra=field(all,"slotExFilter");((CheckBox)field(all,"slotExValue")).setSelected(true);extra.setSelected(true);expect(table,2);extra.setSelected(false);
            ToggleSwitch ctype=field(all,"ctypeFilter");ComboBox<String> ctypes=field(all,"ctypeValue");
            ctypes.setValue(ctypes.getItems().stream().filter(s->s!=null && s.startsWith("2 - ")).findFirst().orElseThrow());
            ctype.setSelected(true);expect(table,2);ctype.setSelected(false);

            TableColumn<ShipItem,Integer> level=field(all,"lv");level.setSortType(TableColumn.SortType.DESCENDING);table.getSortOrder().setAll(level);table.sort();expect(table,2,1);
            all.selectAll();all.copy();String copied=Clipboard.getSystemClipboard().getString();
            check(copied!=null && copied.contains("alpha") && copied.contains("bravo"),"Real table copy lost ship names");
            all.deckBuilderDisplayCopy();String export=Clipboard.getSystemClipboard().getString();
            var data=logbook.internal.json.JsonMappers.builder().build().readTree(export);
            check(data.get("version").asInt()==4,"Deck export schema changed");
            check(data.get("f1").get("s1").get("id").asInt()==1000002 && data.get("f1").get("s2").get("id").asInt()==1000001,"Fleet export changed slots");
            text.setSelected(true);input.setText("alpha");expect(table,1);
            stage.getScene().getRoot().applyCss();stage.getScene().getRoot().layout();
            check(stage.getScene().getRoot().snapshot(null,null).getWidth()>0,"Ship rendering snapshot failed");
        } finally {stage.close();}
        Timeline timeline=field(controller,"timeline");check(timeline.getStatus()==Animation.Status.STOPPED,"Hidden window kept its update timer running");
    }
    private static ShipTablePane select(TabPane tabs,String name) {
        Tab tab=tabs.getTabs().stream().filter(t->name.equals(t.getText())).findFirst().orElseThrow();
        tabs.getSelectionModel().select(tab);ShipTablePane pane=(ShipTablePane)tab.getContent();pane.enable();pane.update();return pane;
    }
    private static void expect(TableView<ShipItem> table,Integer... ids) {
        check(table.getItems().stream().map(ShipItem::getId).toList().equals(List.of(ids)),"Wrong filtered/ordered rows: "+table.getItems().stream().map(ShipItem::getId).toList()+" expected "+List.of(ids));
    }
    @SuppressWarnings("unchecked") private static <T> T field(Object owner,String name) throws Exception {
        var f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return (T)f.get(owner);
    }
    @FunctionalInterface private interface Action {void run() throws Exception;}
    private static void fx(Action action) throws Exception {
        var done=new CompletableFuture<Void>();Platform.runLater(()->{try{action.run();done.complete(null);}catch(Throwable error){done.completeExceptionally(error);}});done.get(45,TimeUnit.SECONDS);
    }
}
