package logbook.internal.gui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.input.Clipboard;
import javafx.stage.Stage;
import org.controlsfx.control.ToggleSwitch;
import logbook.bean.*;
import logbook.core.GameDefinitionRuntime;
import logbook.core.LogBookCoreServices;
import logbook.internal.Config;
import logbook.internal.kancolle.Operator;
import logbook.plugin.api.EquipmentDataV1;
import logbook.plugin.api.FeatureUiV1;

/** Synthetic isolated process only; never connects a browser or starts the main application/proxy. */
public final class EquipmentInventoryProbe {
    private static final int GUN = 1000001, FIGHTER = 1000002;
    private static void check(boolean condition,String message) {
        if (!condition) throw new AssertionError(message);
    }
    @SuppressWarnings("unchecked")
    private static <T> T field(Object value,String name) throws Exception {
        var field=value.getClass().getDeclaredField(name);field.setAccessible(true);return (T)field.get(value);
    }
    public static void main(String[] args) throws Exception {
        check(args.length==1 && "synthetic-equipment-only".equals(args[0]),"Explicit fixture mode required");
        check(!Files.exists(Path.of("config")) && !Files.exists(Path.of("history-sentinel.csv")),"Refusing an existing runtime");
        byte[] history="synthetic history retained\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(Path.of("history-sentinel.csv"),history,StandardOpenOption.CREATE_NEW);
        var failures=new CopyOnWriteArrayList<Throwable>();
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{failures.add(error);error.printStackTrace();});
        LogBookCoreServices.init(List.of());GameDefinitionRuntime.initialize();fixture();
        var ready=new CompletableFuture<Void>();Platform.startup(()->ready.complete(null));ready.get(20,TimeUnit.SECONDS);
        Platform.setImplicitExit(false);
        try {
            snapshots();
            runFx(EquipmentInventoryProbe::window);
            runFx(()->{});
            check(failures.isEmpty(),"Uncaught UI failure: "+failures);
            Config.getDefault().store();
            var reread=new Config(Path.of("config")).get(AppItemTableConfig.class,AppItemTableConfig::new);
            check(reread.getTabIndex()==1,"Parent tab setting did not survive reread");
            check(reread.getItemTabConfig().isTextFilterEnabled(),"Text filter setting was lost");
            check("synthetic fighter".equals(reread.getItemTabConfig().getTextFilter()),"Filter query changed");
            check(reread.getAirbaseTabConfig().isDisableGrouping(),"Grouping setting was lost");
            check(Arrays.equals(history,Files.readAllBytes(Path.of("history-sentinel.csv"))),"Original history changed");
            System.out.println("EQUIPMENT_PRODUCT_GUI_OK tabs=2 summaries=2 owned=6 normal_and_extra_owner=true aircraft=3 grouped=2 locked_export=3 settings_reread=true original_history_unchanged=true production_accessed=false");
        } finally { Platform.exit(); }
    }
    private static void fixture() {
        AppConfig.get().setVisibleExpGauge(false);
        for (int type:List.of(1,6)) {
            SlotitemEquiptype value=new SlotitemEquiptype();value.setId(type);value.setName("synthetic type "+type);
            SlotitemEquiptypeCollection.get().getEquiptypeMap().put(type,value);
        }
        for (int id:List.of(GUN,FIGHTER)) {
            SlotitemMst value=new SlotitemMst();value.setId(id);value.setName(id==GUN?"synthetic gun":"synthetic fighter");
            value.setType(List.of(0,0,id==GUN?1:6,id==GUN?1:6,0));
            value.setHoug(id==GUN?2:0);value.setHoum(0);value.setLeng(1);value.setLuck(0);value.setHouk(0);
            value.setBaku(0);value.setRaig(0);value.setSaku(0);value.setTais(0);value.setTyku(id==GUN?0:10);value.setSouk(0);
            value.setDistance(id==GUN?0:3);value.setCost(1);
            SlotitemMstCollection.get().getSlotitemMap().put(id,value);
        }
        owned(101,GUN,2,0,true);owned(102,GUN,3,0,false);
        owned(201,FIGHTER,0,0,true);owned(202,FIGHTER,0,0,false);owned(203,FIGHTER,3,7,true);
        owned(999,1999999,0,0,false); // Unknown master remains an instance, not fabricated stats.
        ShipMst master=new ShipMst();master.setId(1000001);master.setName("synthetic owner");
        ShipMstCollection.get().getShipMap().put(master.getId(),master);
        Ship ship=new Ship();ship.setId(1);ship.setShipId(master.getId());ship.setLv(10);
        ship.setSlot(List.of(101));ship.setSlotEx(102);ship.setSlotnum(1);ship.setCond(49);
        ship.setNowhp(15);ship.setMaxhp(15);ship.setSallyArea(0);ship.setExp(List.of(0,0));
        ShipCollection.get().getShipMap().put(1,ship);
    }
    private static void owned(int id,int master,int level,int alv,boolean locked) {
        SlotItem value=new SlotItem();value.setId(id);value.setSlotitemId(master);value.setLevel(level);
        value.setAlv(alv);value.setLocked(locked);SlotItemCollection.get().getSlotitemMap().put(id,value);
    }
    private static void snapshots() {
        var snapshot=EquipmentDataV1.inventory();
        check(snapshot.owned().size()==6,"Unknown owned equipment was silently dropped");
        check(snapshot.definitions().size()==2,"Unknown master stats were invented");
        check(snapshot.warnings().stream().anyMatch(v->v.contains("1999999")),"Missing-master diagnostic absent");
        check(snapshot.definitions().stream().mapToInt(EquipmentDataV1.Definition::count).sum()==5,"Owned counts changed");
        var normal=snapshot.owned().stream().filter(v->v.id()==101).findFirst().orElseThrow();
        var extra=snapshot.owned().stream().filter(v->v.id()==102).findFirst().orElseThrow();
        check(normal.owner()!=null && normal.owner().equals(extra.owner()),"Normal/expansion ownership mismatch");
        check(normal.owner().displayName().contains("synthetic owner"),"Owning ship label lost");
        var raw=SlotItemCollection.get().getSlotitemMap().get(101);raw.setLevel(8);
        check(normal.level()==2,"Returned snapshot retained a mutable item");raw.setLevel(2);
        for (var list:List.of(snapshot.owned(),snapshot.definitions(),snapshot.warnings(),EquipmentDataV1.aircraft())) {
            try {list.clear();throw new AssertionError("Mutable query result");}catch(UnsupportedOperationException expected){}
        }
        var categories=EquipmentDataV1.categories();
        try {categories.clear();throw new AssertionError("Mutable categories");}catch(UnsupportedOperationException expected){}
        for(var values:categories.values()) {
            try {values.clear();throw new AssertionError("Mutable category list");}catch(UnsupportedOperationException expected){}
        }
        var planes=EquipmentDataV1.aircraft();check(planes.size()==3,"Aircraft eligibility changed");
        var old=AirBaseItem.toAirBaseItem(SlotItemCollection.get().getSlotitemMap().get(201));
        check(planes.stream().filter(v->v.level()==0).allMatch(v->v.seiku()==old.getSeiku()
                && v.interceptSeiku()==old.getInterceptSeiku() && v.distanceTaiteichan()==old.getDistanceTaiteichan()
                && v.distanceCatalina()==old.getDistanceCatalina() && v.cost()==old.getCost()),"Aircraft formulas changed");
        check(old.getSeiku()==42,"Synthetic fighter calculation no longer equals 42");
    }
    @SuppressWarnings("unchecked")
    private static void window() throws Exception {
        Stage stage=new Stage();FeatureUiV1.Window controller=null;
        try {
            FXMLLoader loader=InternalFXMLLoader.load("logbook/gui/item.fxml");
            check(loader.getLocation().toString().contains("logbook-feature-equipment.jar!/"),"Parent FXML remains in core");
            Parent root=loader.load();controller=loader.getController();controller.bindWindow(stage);
            stage.setScene(new Scene(root));stage.show();root.applyCss();root.layout();
            ItemItemController items=(ItemItemController)loader.getNamespace().get("itemController");
            ItemAirBaseController air=(ItemAirBaseController)loader.getNamespace().get("airbaseController");
            for(Object value:List.of(controller,items,air))
                check(value.getClass().getProtectionDomain().getCodeSource().getLocation().toString()
                        .endsWith("logbook-feature-equipment.jar"),"Controller silently fell back to core");
            TableView<Item> table=field(items,"typeTable");
            TableView<EquipmentDetailItem> detail=field(items,"detailTable");
            check(table.getItems().size()==2,"Equipment summary rows missing");
            Item gun=table.getItems().stream().filter(v->v.getId()==GUN).findFirst().orElseThrow();
            Item fighter=table.getItems().stream().filter(v->v.getId()==FIGHTER).findFirst().orElseThrow();
            check(gun.getCount()==2 && fighter.getCount()==3,"Count aggregation changed");
            table.getSelectionModel().select(gun);
            check(detail.getItems().size()==2 && detail.getItems().stream().allMatch(v->Integer.valueOf(1).equals(v.getShipId())),
                    "Expansion or normal slot owner missing from actual table");
            table.getSelectionModel().select(fighter);
            check(detail.getItems().size()==3 && detail.getItems().getFirst().getId()==203,"Detail ordering/proficiency changed");
            items.selectAllDetail();check(detail.getSelectionModel().getSelectedItems().size()==3,"Detail selection changed");
            items.copyDetail();check(Clipboard.getSystemClipboard().getString().contains("未所持"),"Detail copy lost owner label");
            items.kancolleFleetanalysis();
            try(var json=jakarta.json.Json.createReader(new java.io.StringReader(Clipboard.getSystemClipboard().getString()))) {
                var data=json.readArray();check(data.size()==3,"Locked-equipment export changed");
                check(data.getJsonObject(0).getInt("api_slotitem_id")==GUN && data.getJsonObject(0).getInt("api_level")==2,
                        "External export key/value contract changed");
                check(data.getJsonObject(1).getInt("api_slotitem_id")==FIGHTER && data.getJsonObject(2).getInt("api_level")==3,
                        "Export order or lock filtering changed");
            }
            List<ParameterFilterPane<Item>> filters=field(items,"parameterFilters");
            ParameterFilterConfig numeric=new ParameterFilterConfig();numeric.setEnabled(true);numeric.setName("対空");
            numeric.setValue(5);numeric.setType(Operator.GE);filters.getFirst().loadConfig(numeric);
            check(table.getItems().size()==1 && table.getItems().getFirst().getId()==FIGHTER,"Numeric filter changed");
            numeric.setEnabled(false);filters.getFirst().loadConfig(numeric);check(table.getItems().size()==2,"Filter disable failed");
            ToggleSwitch text=field(items,"textFilter");ComboBox<String> textValue=field(items,"textValue");
            text.setSelected(true);textValue.setValue("synthetic fighter");
            check(table.getItems().size()==1,"Text filter failed");
            TabPane tabs=(TabPane)loader.getNamespace().get("tab");tabs.getSelectionModel().select(1);
            root.applyCss();root.layout();
            TableView<AirBaseItem> planes=field(air,"itemTable");ToggleSwitch grouping=field(air,"disableGrouping");
            check(planes.getItems().size()==2,"Equivalent aircraft were not grouped");
            check(planes.getItems().stream().mapToInt(AirBaseItem::getCount).sum()==3,"Grouped counts changed");
            grouping.setSelected(true);check(planes.getItems().size()==3,"Ungrouped aircraft lost instances");
            air.selectAllAirBase();check(planes.getSelectionModel().getSelectedItems().size()==3,"Aircraft selection changed");
            air.copyAirBase();check(Clipboard.getSystemClipboard().getString().contains("synthetic fighter"),"Aircraft copy changed");
            check(planes.getItems().stream().filter(v->v.getLevel()==0).allMatch(v->v.getSeiku()==42),"Displayed aircraft calculation changed");
        } finally {stage.close();if(controller!=null)controller.setWindow(null);}
    }
    @FunctionalInterface private interface Action {void run() throws Exception;}
    private static void runFx(Action action) throws Exception {
        var done=new CompletableFuture<Void>();
        Platform.runLater(()->{try{action.run();done.complete(null);}catch(Throwable error){done.completeExceptionally(error);}});
        done.get(30,TimeUnit.SECONDS);
    }
}
