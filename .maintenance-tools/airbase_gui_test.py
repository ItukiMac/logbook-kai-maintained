from pathlib import Path

def new(path,text):
    p=Path(path);assert not p.exists(),path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf8')
new('scripts/java/AirBaseStatusGuiProbe.java',r'''package logbook.internal.gui;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.animation.Timeline;
import javafx.animation.Animation;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import logbook.bean.*;
import logbook.bean.Mapinfo.*;
import logbook.core.LogBookCoreServices;
import logbook.core.GameDefinitionRuntime;
import logbook.plugin.api.FeatureUiV1;

/** Only synthetic state in a new working directory, loaded from the actual product JARs. */
public final class AirBaseStatusGuiProbe {
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static AirBase base;private static PlaneInfo first;private static SlotitemMst master;
    public static void main(String[] args) throws Exception {
        require(args.length==1 && "synthetic-airbase-only".equals(args[0]),"Explicit isolated mode required");
        require(!Files.exists(Path.of("config")) && !Files.exists(Path.of("history.csv")),"Existing runtime rejected");
        byte[] history="unchanged synthetic history\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(Path.of("history.csv"),history,StandardOpenOption.CREATE_NEW);
        require(AirBaseController.class.getProtectionDomain().getCodeSource().getLocation().toString().endsWith("/features/logbook-feature-status.jar"),"Host fallback must not hide feature errors");
        LogBookCoreServices.init(List.of());GameDefinitionRuntime.initialize();fixture();
        var errors=new CopyOnWriteArrayList<Throwable>();Thread.setDefaultUncaughtExceptionHandler((t,e)->{errors.add(e);e.printStackTrace();});
        var ready=new CompletableFuture<Void>();Platform.startup(()->ready.complete(null));ready.get(20,TimeUnit.SECONDS);Platform.setImplicitExit(false);
        try {
            var done=new CompletableFuture<Void>();Platform.runLater(()->{try{window();done.complete(null);}catch(Throwable e){done.completeExceptionally(e);}});done.get(45,TimeUnit.SECONDS);
            require(errors.isEmpty(),"Unexpected FX exception: "+errors);
            require(Arrays.equals(history,Files.readAllBytes(Path.of("history.csv"))),"Original history changed");
            System.out.println("AIRBASE_STATUS_GUI_OK product_status_jar=true captured_power=40 refresh_power=60 frozen_selection=true identity_preserved=true sorted_planes=true unknown_visible=true stale_values_cleared=true legacy_export=true timeline_stopped=true original_history_unchanged=true production_accessed=false");
        } finally {Platform.exit();LogBookCoreServices.closeContainer();}
    }
    private static AirBase empty(int area,int id) {
        AirBase b=new AirBase();b.setAreaId(area);b.setRid(id);b.setName("base "+area+"/"+id);b.setActionKind(1);
        Distance d=new Distance();d.setBase(5);d.setBonus(2);b.setDistance(d);b.setPlaneInfo(new ArrayList<>());return b;
    }
    private static void fixture() {
        Maparea area=new Maparea();area.setId(6);area.setName("synthetic sea");area.setType(0);MapareaCollection.get().getMaparea().put(6,area);
        SlotitemEquiptype category=new SlotitemEquiptype();category.setId(6);category.setName("synthetic fighter");SlotitemEquiptypeCollection.get().getEquiptypeMap().put(6,category);
        master=new SlotitemMst();master.setId(1001);master.setName("captured fighter");master.setType(List.of(0,0,6,6));
        master.setTyku(10);master.setHouk(2);master.setHoum(1);master.setSaku(9);master.setRaig(0);master.setBaku(0);master.setTais(0);master.setDistance(5);master.setCost(2);
        SlotitemMstCollection.get().getSlotitemMap().put(1001,master);
        base=empty(6,1);
        for(int n=1;n<=2;n++) {
            SlotItem item=new SlotItem();item.setId(100+n);item.setSlotitemId(1001);item.setAlv(0);item.setLevel(0);item.setLocked(true);SlotItemCollection.get().getSlotitemMap().put(100+n,item);
            PlaneInfo p=new PlaneInfo();p.setSquadronId(n);p.setState(1);p.setSlotid(100+n);p.setCount(n==1?16:0);p.setMaxCount(18);p.setCond(n==1?2:1);base.getPlaneInfo().add(p);if(n==1)first=p;
        }
        Mapinfo.get().setAirBase(new ArrayList<>(List.of(base,empty(6,2))));
    }
    @SuppressWarnings({"unchecked","rawtypes"}) private static void window() throws Exception {
        AirBaseController[] cs=new AirBaseController[1];Stage[] stages=new Stage[1];
        FeatureUiV1.showWindow("logbook/gui/airbase.fxml",null,"synthetic airbase",c->cs[0]=(AirBaseController)c,s->stages[0]=s);
        AirBaseController c=cs[0];Stage stage=stages[0];Timeline timer=field(c,"timeline");timer.pause();
        TreeTableView<AirBaseController.AreaTable> areas=field(c,"areaTable");TableView<AirBaseController.Plane> planes=field(c,"planeTable");
        try {
            select(areas,6,1);require("40".equals(((Label)field(c,"seikuTotal")).getText()),"Initial captured power");
            require(planes.getItems().size()==2,"Both recorded squadrons required");
            HBox info=field(c,"info");require(info.getChildren().stream().anyMatch(n->n instanceof Button b && b.getText().equals("疲労")),"Captured fatigue warning missing");
            TableColumn count=field(c,"count");count.setSortType(TableColumn.SortType.ASCENDING);planes.getSortOrder().setAll(count);planes.sort();
            require(planes.getItems().getFirst().countProperty().get()==0,"Bound sorted list did not sort actual rows");
            c.deckBuilderCopy();var exported=logbook.internal.json.JsonMappers.builder().build().readTree(Clipboard.getSystemClipboard().getString());
            require(exported.get("version").asInt()==4 && exported.get("a1").get("items").get("i1").get("id").asInt()==1001,"Existing explicit deck-builder export changed");
            first.setCount(9);master.setTyku(20);master.setName("new live name");
            select(areas,6,2);select(areas,6,1);
            require("40".equals(((Label)field(c,"seikuTotal")).getText()),"Selection reread live calculation instead of captured value");
            require(planes.getItems().stream().anyMatch(p->p.toString().contains("captured fighter")),"Copied text reread live equipment name");
            require(areas.getSelectionModel().getSelectedItem().getValue().getAirBase()!=base,"Legacy getter exposed live base");
            Maparea earlier=new Maparea();earlier.setId(5);earlier.setType(0);earlier.setName("earlier area");MapareaCollection.get().getMaparea().put(5,earlier);
            Mapinfo.get().getAirBase().addFirst(empty(5,1));c.update(null);
            var selected=areas.getSelectionModel().getSelectedItem().getValue().getAirBase();
            require(selected.getAreaId()==6 && selected.getRid()==1,"Refresh selected a different base after reordering");
            require("60".equals(((Label)field(c,"seikuTotal")).getText()),"Explicit refresh did not use new count/master values");
            first.setCount(null);c.update(null);
            require("未記録".equals(((Label)field(c,"seikuTotal")).getText()),"Missing count was fabricated as zero");
            require("未記録".equals(((Label)field(c,"lesser")).getText()),"Old thresholds survived unknown power");
            stage.getScene().getRoot().applyCss();stage.getScene().getRoot().layout();
            require(stage.getScene().getRoot().snapshot(null,null).getWidth()>0,"Actual rendering failed");
            Mapinfo.get().getAirBase().clear();c.update(null);
            require(planes.getItems().isEmpty() && "—".equals(((Label)field(c,"seikuTotal")).getText()),"Deleted selection retained stale details");
        } finally {timer.play();stage.close();}
        require(timer.getStatus()==Animation.Status.STOPPED,"Hidden window retained timer");
    }
    private static void select(TreeTableView<AirBaseController.AreaTable> table,int area,int id) {
        var queue=new ArrayDeque<TreeItem<AirBaseController.AreaTable>>();queue.add(table.getRoot());
        while(!queue.isEmpty()){var item=queue.removeFirst();queue.addAll(item.getChildren());var b=item.getValue().getAirBase();
            if(b!=null && b.getAreaId()==area && b.getRid()==id){table.getSelectionModel().select(item);return;}}
        throw new AssertionError("Base not displayed "+area+"/"+id);
    }
    @SuppressWarnings("unchecked") private static <T>T field(Object owner,String name)throws Exception {var f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return (T)f.get(owner);}
}
''')
new('scripts/tests/test_airbase_status_ui.py',r'''"""Actual product-JAR airbase GUI, synthetic state only."""
import hashlib,json,os,shutil,subprocess,sys,tempfile,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts'))
from build_feature_distribution import build
from run_feature_distribution import FILES,verify

@unittest.skipUnless((ROOT/'target/logbook-kai.jar').is_file() and shutil.which('javac') and shutil.which('xvfb-run'),
                     'Requires built JAR and isolated display')
class AirBaseStatusUiTest(unittest.TestCase):
    def test_frozen_values_refresh_unknowns_and_identity_in_product_window(self):
        source=ROOT/'target/logbook-kai.jar';before=hashlib.sha256(source.read_bytes()).hexdigest()
        fixture=Path(tempfile.mkdtemp(prefix='airbase-status-fixture-',dir=ROOT/'target'))
        evidence=ROOT/'target/r3-verification';evidence.mkdir(exist_ok=True)
        report={'fixture':str(fixture),'result':'not_completed','production_accessed':False};logs=[]
        try:
            dist=fixture/'distribution';build(source,dist,'a'*40);verify(dist)
            cp=os.pathsep.join(str(dist/name) for name in FILES if name.endswith('.jar'))
            classes=fixture/'classes';classes.mkdir();runtime=fixture/'runtime';runtime.mkdir()
            env={k:v for k,v in os.environ.items() if k not in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH')}
            result=subprocess.run(['javac','-proc:none','-encoding','UTF-8','-cp',cp,'-d',str(classes),str(ROOT/'scripts/java/AirBaseStatusGuiProbe.java')],env=env,capture_output=True,text=True,timeout=60)
            logs.append(result.stdout+result.stderr);self.assertEqual(0,result.returncode,logs[-1][-16000:])
            result=subprocess.run(['xvfb-run','-a','java','-Dlogbook.data.autoUpdate=false','-Duser.home='+str(fixture/'home'),'-cp',str(classes)+os.pathsep+cp,
                'logbook.internal.gui.AirBaseStatusGuiProbe','synthetic-airbase-only'],env=env,cwd=runtime,capture_output=True,text=True,timeout=90)
            logs.append(result.stdout+result.stderr);self.assertEqual(0,result.returncode,logs[-1][-22000:]);self.assertIn('AIRBASE_STATUS_GUI_OK',result.stdout)
            for marker in ('FXMLの初期化に失敗しました','画面の更新に失敗しました','FXMLのロードに失敗しました'):self.assertNotIn(marker,logs[-1])
            self.assertEqual(before,hashlib.sha256(source.read_bytes()).hexdigest())
            report.update(result='passed',product_status_jar=True,frozen_selection=True,identity_preserved=True,
                captured_power=40,refresh_power=60,sorted_planes=True,unknown_visible=True,stale_values_cleared=True,
                legacy_export=True,timeline_stopped=True,original_history_unchanged=True,source_jar_unchanged=True)
        finally:
            (evidence/(fixture.name+'.json')).write_text(json.dumps(report,indent=2)+'\n')
            (evidence/(fixture.name+'.log')).write_text('\n\n'.join(logs),encoding='utf8')
        print('AIRBASE_STATUS_GUI_OK '+json.dumps(report))
if __name__=='__main__':unittest.main()
''')
p=Path('scripts/verify_r3.sh');s=p.read_text();anchor="report={'ship_list_ui':ship_list,"
assert s.count(anchor)==1
s=s.replace(anchor,"""airbase=[json.loads(f.read_text()) for f in p.glob('airbase-status-fixture-*.json')]
assert len(airbase)==1 and airbase[0]['result']=='passed'
assert airbase[0]['product_status_jar'] and airbase[0]['captured_power']==40 and airbase[0]['refresh_power']==60
assert all(airbase[0][k] for k in ('frozen_selection','identity_preserved','sorted_planes','unknown_visible','stale_values_cleared','legacy_export','timeline_stopped','original_history_unchanged','source_jar_unchanged'))
assert not airbase[0]['production_accessed']
airbase_suites=[s for s in suites if s.get('name') in {'logbook.core.AirBaseStatusContractTest','logbook.internal.gui.AirBaseStatusBoundaryTest'}]
assert len(airbase_suites)==2 and all(int(s.get('skipped',0))==0 for s in airbase_suites)
assert sum(int(s.get('tests',0)) for s in airbase_suites)>=22
report={'airbase_status':airbase[0],'ship_list_ui':ship_list,""")
p.write_text(s)
print('AIRBASE_GUI_GATE_ADDED product_gui_required=true old_gates_retained=true')
