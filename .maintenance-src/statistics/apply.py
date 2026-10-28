from pathlib import Path
import sys

source = Path(sys.argv[1])

def edit(name, old, new):
    p=Path(name);raw=p.read_bytes();ending='\r\n' if b'\r\n' in raw else '\n'
    text=raw.decode().replace('\r\n','\n')
    assert text.count(old)==1,(name,old[:80],text.count(old))
    p.write_bytes(text.replace(old,new).replace('\n',ending).encode())

for name, target in {
    'ShipStatisticsV1.java':'src/main/java/logbook/plugin/api/ShipStatisticsV1.java',
    'ShipStatisticsService.java':'src/main/java/logbook/core/ShipStatisticsService.java',
    'ShipStatisticsContractTest.java':'src/test/java/logbook/core/ShipStatisticsContractTest.java',
    'ShipStatisticsBoundaryTest.java':'src/test/java/logbook/internal/gui/ShipStatisticsBoundaryTest.java',
    'ShipStatisticsGuiProbe.java':'scripts/java/ShipStatisticsGuiProbe.java',
    'test_ship_statistics_ui.py':'scripts/tests/test_ship_statistics_ui.py',
    'test_ship_statistics_packaging.py':'scripts/tests/test_ship_statistics_packaging.py',
    'statistics-boundary.md':'docs/maintenance/ship-statistics-boundary-20261028.md',
}.items():
    p=Path(target);assert not p.exists(),target;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes((source/name).read_bytes())
# An existing view is replaced only in this fresh, pinned source checkout. Its prior commit remains.
p=Path('src/main/java/logbook/internal/gui/StatisticsPane.java')
assert 'List<Ship> ships = ShipCollection.get()' in p.read_text()
p.write_bytes((source/'StatisticsPane.java').read_bytes())
edit('src/main/java/logbook/plugin/api/FeatureUiV1.java','        private Controls() { }',
'''        private Controls() { }

        /** Preserve the existing explicit save dialog and PNG output. Invoke only on user action. */
        public static void storeSnapshot(javafx.scene.Node node, String title, javafx.stage.Window owner) {
            FeatureUiBridgeV1.storeSnapshot(node, title, owner);
        }''')
edit('src/main/java/logbook/internal/gui/FeatureUiBridgeV1.java','    public static void restoreTable(TableView<?> table, String key) { TableTool.setVisible(table, key); }',
'''    public static void storeSnapshot(javafx.scene.Node node, String title, javafx.stage.Window owner) {
        Tools.Controls.storeSnapshot(node, title, owner);
    }

    public static void restoreTable(TableView<?> table, String key) { TableTool.setVisible(table, key); }''')
edit('src/main/resources/logbook/gui/statistics.fxml','                  <HBox>\n                     <children>',
'''                  <Text fx:id="quality" visible="false" managed="false" wrappingWidth="760.0" />
                  <HBox>
                     <children>''')

p='scripts/run_feature_distribution.py'
edit(p,'def required_entries(module_id):', '''SHIP_STATISTICS_PROFILE = 'ship-statistics-in-charts-v1'
SHIP_STATISTICS_API = 'logbook/plugin/api/ShipStatisticsV1.class'


def ship_statistics_entry(name):
    stem = 'logbook/internal/gui/StatisticsPane'
    return (name == 'logbook/gui/statistics.fxml' or name == stem + '.class'
            or name.startswith(stem + '$') and name.endswith('.class'))


def ship_statistics_required_entries():
    return {'logbook/internal/gui/StatisticsPane.class', 'logbook/gui/statistics.fxml'}


def required_entries(module_id):''')
edit(p,"    equipment_profile = report.get('equipment_ui_profile')\n", "    statistics_profile = report.get('ship_statistics_profile')\n    if statistics_profile is not None and statistics_profile != SHIP_STATISTICS_PROFILE:\n        raise ValueError('Unsupported ship statistics packaging profile')\n    equipment_profile = report.get('equipment_ui_profile')\n")
edit(p,"    equipment_profile = report.get('equipment_ui_profile') == EQUIPMENT_UI_PROFILE\n", "    statistics_profile = report.get('ship_statistics_profile') == SHIP_STATISTICS_PROFILE\n    equipment_profile = report.get('equipment_ui_profile') == EQUIPMENT_UI_PROFILE\n")
edit(p,'            if path == JARS[0]:\n', '''            if path == JARS[0]:
                if (SHIP_STATISTICS_API in names) != statistics_profile:
                    raise ValueError('Ship statistics API and packaging profile disagree')
                if statistics_profile and any(ship_statistics_entry(name) for name in names):
                    raise ValueError('Ship statistics UI remains in the host instead of charts')
''')
edit(p,"            required = required_entries(module['id'])\n", "            required = required_entries(module['id'])\n            if statistics_profile:\n                if module['id'] == 'charts':\n                    required |= ship_statistics_required_entries()\n                elif any(ship_statistics_entry(name) for name in names):\n                    raise ValueError('Ship statistics UI belongs to the charts module')\n")
p='scripts/build_feature_distribution.py'
edit(p,'                                     equipment_ui_entry, equipment_ui_required_entries)', '                                     equipment_ui_entry, equipment_ui_required_entries, SHIP_STATISTICS_API,\n                                     SHIP_STATISTICS_PROFILE, ship_statistics_entry, ship_statistics_required_entries)')
edit(p,'def owner(name: str, record_ui: bool = False, equipment_ui: bool = False) -> str | None:', 'def owner(name: str, record_ui: bool = False, equipment_ui: bool = False, statistics_ui: bool = False) -> str | None:\n    if statistics_ui and ship_statistics_entry(name):\n        return \'charts\'')
edit(p,'    equipment_ui = EQUIPMENT_UI_API in entries\n', "    statistics_ui = SHIP_STATISTICS_API in entries\n    if statistics_ui:\n        missing = ship_statistics_required_entries() - entries.keys()\n        if missing:\n            raise ValueError('Missing ship statistics input: ' + sorted(missing)[0])\n    equipment_ui = EQUIPMENT_UI_API in entries\n")
edit(p,'        group = owner(name, record_ui, equipment_ui)\n','        group = owner(name, record_ui, equipment_ui, statistics_ui)\n')
edit(p,"'extracted': {g: sorted(n for n in entries if owner(n, record_ui, equipment_ui) == g) for g in GROUPS}}", "'extracted': {g: sorted(n for n in entries if owner(n, record_ui, equipment_ui, statistics_ui) == g) for g in GROUPS}}")
edit(p,'    if equipment_ui:\n        manifest', "    if statistics_ui:\n        manifest['ship_statistics_profile'] = SHIP_STATISTICS_PROFILE\n    if equipment_ui:\n        manifest")

p='scripts/verify_r3.sh'
edit(p,"report={'parallel_integration':combined[0]", "statistics=[json.loads(f.read_text()) for f in p.glob('ship-statistics-fixture-*.json')]\nassert len(statistics)==1 and statistics[0]['result']=='passed'\nassert statistics[0]['product_distribution'] and statistics[0]['charts_rendered']==5\nassert statistics[0]['frozen_after_mutation'] and statistics[0]['source_jar_unchanged']\nassert not statistics[0]['production_accessed']\nstatistics_suites=[s for s in suites if s.get('name') in {'logbook.core.ShipStatisticsContractTest','logbook.internal.gui.ShipStatisticsBoundaryTest'}]\nassert len(statistics_suites)==2 and all(int(s.get('skipped',0))==0 for s in statistics_suites)\nassert sum(int(s.get('tests',0)) for s in statistics_suites)>=21\nreport={'ship_statistics':statistics[0],'parallel_integration':combined[0]")
print('SHIP_STATISTICS_SOURCE_APPLIED no_private_data=true no_storage_format_change=true')
