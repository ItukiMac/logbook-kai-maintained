from pathlib import Path
import json,re,sys,subprocess
BASE='30d9c17e25adb9bdcffdf41c6ac71841a6f240ed'
assert subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()==BASE
assert not subprocess.check_output(['git','status','--porcelain'],text=True).strip()
PAYLOAD=Path(sys.argv[1]).resolve()
changed=[]
def new(path,content):
    p=Path(path);assert not p.exists(),path
    p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content,encoding='utf8');changed.append(path)
def edit(path,fn):
    p=Path(path);raw=p.read_bytes();crlf=b'\r\n' in raw;s=raw.decode().replace('\r\n','\n')
    after=fn(s);assert after!=s,path
    p.write_bytes(after.replace('\n','\r\n').encode() if crlf else after.encode());changed.append(path)
def one(s,old,new):
    assert s.count(old)==1,(old,s.count(old));return s.replace(old,new)
for name,dest in [('ShipTableUiV1','src/main/java/logbook/plugin/api/'),('ShipUiBridgeV1','src/main/java/logbook/internal/gui/'),('ShipUiModuleBoundaryTest','src/test/java/logbook/internal/gui/')]:
    new(dest+name+'.java',(PAYLOAD/(name+'.java')).read_text())

def api(s):
    s=one(s,'        public static String columnName(','''        /** Window-owner overload; the Stage overload remains binary compatible. */
        public static void store(TableView<?> table, String title, javafx.stage.Window owner) throws IOException {
            FeatureUiBridgeV1.storeTable(table, title, owner);
        }

        public static String columnName(''')
    return one(s,'        public static void bindChildCheckBoxes(','''        /** Window-owner overload used by embedded feature components. */
        public static java.util.Optional<javafx.scene.control.ButtonType> alert(
                javafx.scene.control.Alert.AlertType type, String title, String message, javafx.stage.Window owner) {
            return FeatureUiBridgeV1.alert(type, title, message, owner);
        }

        public static void bindChildCheckBoxes(''')
edit('src/main/java/logbook/plugin/api/FeatureUiV1.java',api)
def bridge(s):
    pos=s.rfind('}');assert pos>=0
    return s[:pos]+'''    public static void storeTable(TableView<?> table, String title, javafx.stage.Window owner) throws IOException {
        TableTool.store(table, title, owner);
    }

    public static java.util.Optional<javafx.scene.control.ButtonType> alert(
            javafx.scene.control.Alert.AlertType type, String title, String message, javafx.stage.Window owner) {
        return Tools.Controls.alert(type, title, message, owner);
    }
'''+s[pos:]
edit('src/main/java/logbook/internal/gui/FeatureUiBridgeV1.java',bridge)
edit('src/main/java/logbook/internal/gui/ShipController.java',lambda s:one(s,'extends WindowController','extends logbook.plugin.api.FeatureUiV1.Window'))
def pane(s):
    s=one(s,'package logbook.internal.gui;','package logbook.internal.gui;\n\nimport logbook.plugin.api.FeatureUiV1;\nimport logbook.plugin.api.ShipTableUiV1;')
    s=one(s,'extends VBox {','extends ShipTableUiV1.Pane {')
    s=one(s,'InternalFXMLLoader.load("logbook/gui/ship_table.fxml")','FeatureUiV1.componentLoader(ShipTablePane.class, "/logbook/gui/ship_table.fxml")')
    s=s.replace('ParameterFilterPane.ShipItemParameterFilterPane','ShipTableUiV1.ParameterFilter')
    s=one(s,'new ShipTableUiV1.ParameterFilter()','ShipTableUiV1.parameterFilter()')
    s=s.replace('ParameterFilterPane::','ShipTableUiV1.ParameterFilter::')
    s=re.sub(r'\bTableTool\b','FeatureUiV1.Tables',s)
    s=s.replace('Tools.Controls.','FeatureUiV1.Controls.')
    assert not re.search(r'\b(?:TableTool|Tools|InternalFXMLLoader|ParameterFilterPane)\b',s)
    return s
edit('src/main/java/logbook/internal/gui/ShipTablePane.java',pane)

def runner(s):
    anchor='def required_entries(module_id):'
    addition='''SHIP_UI_PROFILE = 'ship-ui-in-status-v1'
SHIP_UI_API = 'logbook/plugin/api/ShipTableUiV1.class'
SHIP_UI_FAMILIES = ('ShipController', 'ShipTablePane')
SHIP_UI_RESOURCES = ('ship', 'ship_table')


def ship_ui_entry(name):
    return (name in {'logbook/gui/' + resource + '.fxml' for resource in SHIP_UI_RESOURCES}
            or any(name == 'logbook/internal/gui/' + family + '.class'
                   or name.startswith('logbook/internal/gui/' + family + '$') and name.endswith('.class')
                   for family in SHIP_UI_FAMILIES))


def ship_ui_required_entries():
    return ({'logbook/internal/gui/' + family + '.class' for family in SHIP_UI_FAMILIES}
            | {'logbook/gui/' + resource + '.fxml' for resource in SHIP_UI_RESOURCES})


'''
    s=one(s,anchor,addition+anchor)
    s=one(s,"    statistics_profile = report.get('ship_statistics_profile')\n",'''    ship_profile = report.get('ship_ui_profile')
    if ship_profile is not None and ship_profile != SHIP_UI_PROFILE:
        raise ValueError('Unsupported ship UI packaging profile')
    statistics_profile = report.get('ship_statistics_profile')
''')
    s=one(s,"def verify_jars(root, report, catalog):\n", "def verify_jars(root, report, catalog):\n    ship_profile = report.get('ship_ui_profile') == SHIP_UI_PROFILE\n")
    s=one(s,"            if path == JARS[0]:\n",'''            if path == JARS[0]:
                if (SHIP_UI_API in names) != ship_profile:
                    raise ValueError('Ship UI API and packaging profile disagree')
                if ship_profile and any(ship_ui_entry(name) for name in names):
                    raise ValueError('Ship UI remains in the host instead of status')
''')
    return one(s,"            required = required_entries(module['id'])\n",'''            required = required_entries(module['id'])
            if ship_profile:
                if module['id'] == 'status':
                    required |= ship_ui_required_entries()
                elif any(ship_ui_entry(name) for name in names):
                    raise ValueError('Ship UI belongs to the status module')
''')
edit('scripts/run_feature_distribution.py',runner)
def builder(s):
    s=one(s,'                                     SHIP_STATISTICS_PROFILE, ship_statistics_entry, ship_statistics_required_entries)',
       '                                     SHIP_STATISTICS_PROFILE, ship_statistics_entry, ship_statistics_required_entries,\n                                     SHIP_UI_API, SHIP_UI_PROFILE, ship_ui_entry, ship_ui_required_entries)')
    s=one(s,'def owner(name: str, record_ui: bool = False, equipment_ui: bool = False, statistics_ui: bool = False) -> str | None:',
       "def owner(name: str, record_ui: bool = False, equipment_ui: bool = False, statistics_ui: bool = False, ship_ui: bool = False) -> str | None:\n    if ship_ui and ship_ui_entry(name):\n        return 'status'")
    s=one(s,'    statistics_ui = SHIP_STATISTICS_API in entries', '''    ship_ui = SHIP_UI_API in entries
    if ship_ui:
        missing = ship_ui_required_entries() - entries.keys()
        if missing:
            raise ValueError('Missing ship UI input: ' + sorted(missing)[0])
    statistics_ui = SHIP_STATISTICS_API in entries''')
    s=s.replace('owner(name, record_ui, equipment_ui, statistics_ui)','owner(name, record_ui, equipment_ui, statistics_ui, ship_ui)')
    s=s.replace('owner(n, record_ui, equipment_ui, statistics_ui)','owner(n, record_ui, equipment_ui, statistics_ui, ship_ui)')
    return one(s,"    if statistics_ui:\n        manifest['ship_statistics_profile']", "    if ship_ui:\n        manifest['ship_ui_profile'] = SHIP_UI_PROFILE\n    if statistics_ui:\n        manifest['ship_statistics_profile']")
edit('scripts/build_feature_distribution.py',builder)
# Separate synthetic profile suite uses the same ownership attacks, including rehashing.
s=Path('scripts/tests/test_ship_statistics_packaging.py').read_text()
for a,b in [('ShipStatisticsPackagingTest','ShipUiPackagingTest'),('SHIP_STATISTICS','SHIP_UI'),('ship_statistics','ship_ui'),('StatisticsPane','ShipTablePane'),('statistics.fxml','ship_table.fxml'),('ship statistics','ship UI'),('statistics API','Ship UI API'),('statistics UI','Ship UI'),('logbook-feature-charts.jar','logbook-feature-status.jar'),('to the charts','to the status'),('instead of charts','instead of status')]:s=s.replace(a,b)
new('scripts/tests/test_ship_ui_packaging.py',s)
new('scripts/java/ShipListGuiProbe.java',(PAYLOAD/'ShipListGuiProbe.java').read_text())
new('scripts/tests/test_ship_list_ui.py',(PAYLOAD/'test_ship_list_ui.py').read_text())
new('docs/maintenance/ship-ui-module-20261028.md',(PAYLOAD/'notes.md').read_text())
def verify(s):
    return one(s,"report={'ship_statistics':statistics[0]",'''# A skipped GUI test is not proof of product ship-list extraction.
ship_ui_reports=[json.loads(f.read_text()) for f in p.glob('ship-list-fixture-*.json')]
assert len(ship_ui_reports)==1 and ship_ui_reports[0]['result']=='passed'
ship_ui=ship_ui_reports[0]
assert ship_ui['product_distribution'] and ship_ui['no_core_fallback']
assert ship_ui['tabs_verified'] and ship_ui['filters_verified'] and ship_ui['legacy_exports_verified']
assert ship_ui['settings_reread'] and ship_ui['original_history_unchanged']
assert ship_ui['timeline_stopped'] and not ship_ui['production_accessed']
ship_suite=[s for s in suites if s.get('name')=='logbook.internal.gui.ShipUiModuleBoundaryTest']
assert len(ship_suite)==1 and int(ship_suite[0].get('tests'))>=4 and int(ship_suite[0].get('skipped'))==0
report={'ship_ui_packaging':ship_ui,'ship_statistics':statistics[0]''')
edit('scripts/verify_r3.sh',verify)
Path('../ship-ui-paths.json').write_text(json.dumps(changed))
print('SHIP_UI_PATCH_READY files='+str(len(changed))+' no_runtime_data_changed=true')
