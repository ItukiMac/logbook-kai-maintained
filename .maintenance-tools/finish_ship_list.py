from pathlib import Path
import shutil

for source,target in [('ShipListGuiProbe.java','scripts/java/ShipListGuiProbe.java'),
                      ('test_ship_list_ui_integration.py','scripts/tests/test_ship_list_ui_integration.py')]:
    p=Path(target);assert not p.exists(),target
    shutil.copyfile(Path('../tooling/.maintenance-tools')/source,p)
p=Path('scripts/verify_r3.sh');s=p.read_text()
anchor="report={'ship_statistics':statistics[0],"
assert s.count(anchor)==1
added="""ship_list_reports=[json.loads(f.read_text()) for f in p.glob('ship-list-fixture-*.json')]
assert len(ship_list_reports)==1 and ship_list_reports[0]['result']=='passed'
ship_list=ship_list_reports[0]
assert ship_list['product_status_jar'] and ship_list['all_rows']==2 and ship_list['filters']==6
assert ship_list['numeric_controls'] and ship_list['ordered_fleet'] and ship_list['label_tabs']
assert ship_list['sorted_copy'] and ship_list['deck_export'] and ship_list['settings_reread']
assert ship_list['timeline_stopped'] and ship_list['original_history_unchanged'] and not ship_list['production_accessed']
list_suites=[s for s in suites if s.get('name') in {'logbook.internal.gui.ShipListUiBoundaryTest','logbook.internal.gui.ShipListUiContractTest'}]
assert len(list_suites)==2 and all(int(s.get('skipped',0))==0 for s in list_suites)
assert sum(int(s.get('tests',0)) for s in list_suites)>=4
"""
s=s.replace(anchor,added+"report={'ship_list_ui':ship_list,'ship_statistics':statistics[0],")
p.write_text(s)
p=Path('src/test/java/logbook/internal/gui/ShipListUiBoundaryTest.java');s=p.read_text()
s=s.replace('Path classes=Path(ShipController', 'Path classes=Path.of(ShipController')
s=s.replace('    private static Path Path(java.net.URI uri) {return java.nio.file.Path.of(uri);}\n','')
p.write_text(s)
print('SHIP_LIST_GATE_PREPARED previous_statistics_and_label_gates_retained=true')
