from pathlib import Path
import re,sys

BASE='30d9c17e25adb9bdcffdf41c6ac71841a6f240ed'

def new(path,text):
    p=Path(path);assert not p.exists(),path
    p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf8')

def edit(path,fn):
    p=Path(path);raw=p.read_bytes();crlf=b'\r\n' in raw;s=raw.decode().replace('\r\n','\n')
    t=fn(s);assert t!=s,path
    p.write_bytes(t.replace('\n','\r\n').encode() if crlf else t.encode())

def replace(s,old,new,count=1):
    assert s.count(old)==count,(old,s.count(old),count)
    return s.replace(old,new)

new('src/test/java/logbook/internal/gui/ShipListUiBoundaryTest.java',r'''package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Inspect nested cells/filters too; outer-controller checks miss runtime package access. */
class ShipListUiBoundaryTest {
    @Test void shipUiDoesNotLinkPrivateHostGuiHelpers() throws Exception {
        var families=List.of("ShipController","ShipTablePane","ParameterFilterPane");
        var denied=List.of("TableTool","InternalFXMLLoader","Tools","WindowController");
        Path classes=Path(ShipController.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .resolve("logbook/internal/gui");
        int count=0;
        try(var paths=Files.list(classes)) {
            for(Path file:paths.filter(p->p.toString().endsWith(".class")).toList()) {
                String name=file.getFileName().toString();
                if(families.stream().noneMatch(f->name.equals(f+".class")||name.startsWith(f+"$")))continue;
                count++;
                String constants=new String(Files.readAllBytes(file),StandardCharsets.ISO_8859_1);
                for(String type:denied)assertFalse(constants.contains("logbook/internal/gui/"+type),name+" links private host GUI "+type);
            }
        }
        assertTrue(count>=3);
    }
    private static Path Path(java.net.URI uri) {return java.nio.file.Path.of(uri);}
}
''')
if sys.argv[1]=='baseline':raise SystemExit(0)

new('src/main/java/logbook/plugin/api/ShipListUiV1.java',r'''package logbook.plugin.api;

import java.io.IOException;
import java.util.function.Consumer;
import javafx.stage.Stage;

/**
 * Versioned entry to the bundled owned-ship window. UI lives in status, statistics
 * in charts; legacy Ship/ShipItem/filter/export types stay in core for ABI support.
 * Modules still require the exact host digest and the shared application loader.
 * This GUI contract does not claim a new immutable row model or global state lock.
 */
public final class ShipListUiV1 {
    private ShipListUiV1() { }
    public static int version() { return 1; }
    /** Invoke on the JavaFX thread. Opening does not start a game request or install anything. */
    public static void open(Stage owner, String title, Consumer<FeatureUiV1.Window> controller,
            Consumer<Stage> window) throws IOException {
        FeatureUiV1.showWindow("logbook/gui/ship.fxml", owner, title, controller, window);
    }
}
''')

def controller(s):
    s=replace(s,'package logbook.internal.gui;','package logbook.internal.gui;\n\nimport logbook.plugin.api.FeatureUiV1;')
    return replace(s,'extends WindowController','extends FeatureUiV1.Window')
edit('src/main/java/logbook/internal/gui/ShipController.java',controller)

def table(s):
    s=replace(s,'package logbook.internal.gui;','package logbook.internal.gui;\n\nimport logbook.plugin.api.FeatureUiV1;')
    s=replace(s,'InternalFXMLLoader.load("logbook/gui/ship_table.fxml")','FeatureUiV1.componentLoader(ShipTablePane.class, "/logbook/gui/ship_table.fxml")')
    assert 'TableTool' in s;s=re.sub(r'\bTableTool\b','FeatureUiV1.Tables',s)
    s=replace(s,'Tools.Controls.zoomImage','FeatureUiV1.Controls.zoomImage')
    assert s.count('Tools.Controls.alert')==4,s.count('Tools.Controls.alert')
    s=s.replace('Tools.Controls.alert','FeatureUiV1.Controls.alert')
    # Existing modal/table-save handlers are only shown in a Stage, exactly as the
    # existing column-visibility handler already requires. No new storage action.
    s=s.replace('this.getScene().getWindow()', '(Stage) this.getScene().getWindow()')
    s=s.replace('(Stage) (Stage) this.getScene().getWindow()', '(Stage) this.getScene().getWindow()')
    assert not re.search(r'\b(?:TableTool|InternalFXMLLoader|Tools)\b',s)
    return s
edit('src/main/java/logbook/internal/gui/ShipTablePane.java',table)

def param(s):
    s=replace(s,'package logbook.internal.gui;','package logbook.internal.gui;\n\nimport logbook.plugin.api.FeatureUiV1;')
    return replace(s,'InternalFXMLLoader.load("logbook/gui/param_filter_pane.fxml")',
        'FeatureUiV1.componentLoader(ParameterFilterPane.class, "/logbook/gui/param_filter_pane.fxml")')
edit('src/main/java/logbook/internal/gui/ParameterFilterPane.java',param)

profile=r'''
SHIP_LIST_UI_PROFILE = 'ship-list-in-status-v1'
SHIP_LIST_UI_API = 'logbook/plugin/api/ShipListUiV1.class'
SHIP_LIST_UI_FAMILIES = ('ShipController', 'ShipTablePane', 'ParameterFilterPane')
SHIP_LIST_UI_RESOURCES = ('ship', 'ship_table', 'param_filter_pane')


def ship_list_ui_entry(name):
    return (name in {'logbook/gui/' + resource + '.fxml' for resource in SHIP_LIST_UI_RESOURCES}
            or any(name == 'logbook/internal/gui/' + family + '.class'
                   or name.startswith('logbook/internal/gui/' + family + '$') and name.endswith('.class')
                   for family in SHIP_LIST_UI_FAMILIES))


def ship_list_ui_required_entries():
    return ({'logbook/internal/gui/' + family + '.class' for family in SHIP_LIST_UI_FAMILIES}
            | {'logbook/gui/' + resource + '.fxml' for resource in SHIP_LIST_UI_RESOURCES}
            | {'logbook/internal/gui/ParameterFilterPane$' + inner + '.class' for inner in
               ('IntegerParameter', 'ShipItemParameterFilterPane', 'ItemParameterFilterPane', 'AirBaseParameterFilterPane')})

'''

def validator(s):
    s=replace(s,'\ndef required_entries(module_id):',profile+'\ndef required_entries(module_id):')
    s=replace(s,"    statistics_profile = report.get('ship_statistics_profile')\n", "    ship_list_profile = report.get('ship_list_ui_profile')\n    if ship_list_profile is not None and ship_list_profile != SHIP_LIST_UI_PROFILE:\n        raise ValueError('Unsupported ship list UI packaging profile')\n    statistics_profile = report.get('ship_statistics_profile')\n")
    s=replace(s,"def verify_jars(root, report, catalog):\n", "def verify_jars(root, report, catalog):\n    ship_list_profile = report.get('ship_list_ui_profile') == SHIP_LIST_UI_PROFILE\n")
    s=replace(s,"            if path == JARS[0]:\n", """            if path == JARS[0]:
                if (SHIP_LIST_UI_API in names) != ship_list_profile:
                    raise ValueError('Ship list UI API and packaging profile disagree')
                if ship_list_profile and any(ship_list_ui_entry(name) for name in names):
                    raise ValueError('Ship list UI remains in the host instead of status')
""")
    s=replace(s,"            required = required_entries(module['id'])\n", """            required = required_entries(module['id'])
            if ship_list_profile:
                if module['id'] == 'status':
                    required |= ship_list_ui_required_entries()
                elif any(ship_list_ui_entry(name) for name in names):
                    raise ValueError('Ship list UI belongs to the status module')
""")
    return s
edit('scripts/run_feature_distribution.py',validator)

def builder(s):
    s=replace(s,'SHIP_STATISTICS_PROFILE, ship_statistics_entry, ship_statistics_required_entries)',
        'SHIP_STATISTICS_PROFILE, ship_statistics_entry, ship_statistics_required_entries,\n                                     SHIP_LIST_UI_API, SHIP_LIST_UI_PROFILE, ship_list_ui_entry, ship_list_ui_required_entries)')
    s=replace(s,'statistics_ui: bool = False) -> str | None:', 'statistics_ui: bool = False, ship_list_ui: bool = False) -> str | None:\n    if ship_list_ui and ship_list_ui_entry(name):\n        return \'status\'')
    s=replace(s,'    statistics_ui = SHIP_STATISTICS_API in entries\n', """    ship_list_ui = SHIP_LIST_UI_API in entries
    if ship_list_ui:
        missing = ship_list_ui_required_entries() - entries.keys()
        if missing:
            raise ValueError('Missing ship list UI input: ' + sorted(missing)[0])
    statistics_ui = SHIP_STATISTICS_API in entries
""")
    s=replace(s,'owner(name, record_ui, equipment_ui, statistics_ui)', 'owner(name, record_ui, equipment_ui, statistics_ui, ship_list_ui)')
    s=replace(s,'owner(n, record_ui, equipment_ui, statistics_ui)', 'owner(n, record_ui, equipment_ui, statistics_ui, ship_list_ui)')
    s=replace(s,"    if statistics_ui:\n        manifest['ship_statistics_profile']", "    if ship_list_ui:\n        manifest['ship_list_ui_profile'] = SHIP_LIST_UI_PROFILE\n    if statistics_ui:\n        manifest['ship_statistics_profile']")
    return s
edit('scripts/build_feature_distribution.py',builder)

new('src/test/java/logbook/internal/gui/ShipListUiContractTest.java',r'''package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.function.Supplier;
import javafx.stage.Stage;
import logbook.bean.DeckPort;
import logbook.plugin.api.FeatureUiV1;
import logbook.plugin.api.ShipListUiV1;
import org.junit.jupiter.api.Test;

class ShipListUiContractTest {
    @Test void additiveGuiApiRetainsTheExistingLifecycle() {
        assertEquals(1, ShipListUiV1.version());
        assertEquals(1, FeatureUiV1.version());
        assertTrue(FeatureUiV1.Window.class.isAssignableFrom(ShipController.class));
    }
    @Test void existingConstructorsAndPublicLabelExporterRemainAvailable() throws Exception {
        assertNotNull(ShipTablePane.class.getConstructor(DeckPort.class));
        assertNotNull(ShipTablePane.class.getConstructor(Supplier.class, String.class));
        assertNotNull(ShipTablePane.KanmusuList.class.getMethod("copyAll"));
        for(String method:List.of("enable","disable","update"))assertNotNull(ShipTablePane.class.getMethod(method));
    }
    @Test void publicWindowEntryDoesNotExposeMutableShipBeans() throws Exception {
        var method=ShipListUiV1.class.getMethod("open", Stage.class, String.class,
                java.util.function.Consumer.class, java.util.function.Consumer.class);
        assertEquals(void.class,method.getReturnType());
        assertFalse(method.toGenericString().contains("logbook.bean"));
    }
}
''')

# Reuse only the test mechanics of the existing synthetic archive checker, not a
# product implementation. Its transformed file is separately inspected and run.
s=Path('scripts/tests/test_ship_statistics_packaging.py').read_text()
s=s.replace('ShipStatisticsPackagingTest','ShipListPackagingTest').replace('SHIP_STATISTICS_API','SHIP_LIST_UI_API').replace('SHIP_STATISTICS_PROFILE','SHIP_LIST_UI_PROFILE')
s=s.replace('ship_statistics_required_entries','ship_list_ui_required_entries').replace('ship_statistics_profile','ship_list_ui_profile')
s=s.replace('statistics','ship list UI').replace('logbook/gui/ship list UI.fxml','logbook/gui/ship_table.fxml')
s=s.replace('StatisticsPane.class','ShipTablePane.class').replace('logbook-feature-charts.jar','logbook-feature-status.jar')
s=s.replace('instead of charts','instead of status').replace('belongs to the charts','belongs to the status')
# Replace spaces introduced in method identifiers, preserving readable assertions.
s=re.sub(r'(?<=test_)([^(:]+)',lambda m:m.group(0).replace(' ','_'),s)
s=s.replace('Missing ship ship list UI input','Missing ship list UI input')
s=s.replace('Unsupported ship ship list UI','Unsupported ship list UI')
s=s.replace('ship list UI API and packaging profile disagree','Ship list UI API and packaging profile disagree')
s=s.replace('ship list UI UI remains in the host','Ship list UI remains in the host')
s=s.replace('ship list UI UI belongs to the status','Ship list UI belongs to the status')
s=s.replace("    def test_ship_list_UI_cannot_be_moved", "    def test_ship_list_UI_cannot_be_moved")
new('scripts/tests/test_ship_list_packaging.py',s)
print('SHIP_LIST_APPLIED product_status_module=true legacy_shared_models_retained=true no_user_data=true')
