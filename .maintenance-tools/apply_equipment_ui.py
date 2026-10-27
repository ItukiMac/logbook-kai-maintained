from pathlib import Path
import re,json
changed=json.loads(Path('../evidence/equipment-data-paths.json').read_text())
def change(path,fn):
    p=Path(path);raw=p.read_bytes();ending='\r\n' if b'\r\n' in raw else '\n'
    old=raw.decode().replace('\r\n','\n');text=fn(old);assert old!=text,path
    p.write_bytes(text.replace('\n',ending).encode());changed.append(path)
def one(s,a,b):
    assert s.count(a)==1,(a,s.count(a));return s.replace(a,b)
def sub(s,pattern,replacement):
    value,count=re.subn(pattern,replacement,s,flags=re.S);assert count==1,(pattern,count);return value

def api(s):
    return one(s,'        public static String columnName(TableColumn<?, ?> column) {', '''        /** Same explicit save dialog/CSV behavior as the host table tool. */
        public static void store(TableView<?> table, String title, Stage owner) throws IOException {
            FeatureUiBridgeV1.storeTable(table, title, owner);
        }

        public static String columnName(TableColumn<?, ?> column) {''').replace('        public static void setSplitWidth(SplitPane pane, String key) {','''        public static void bindChildCheckBoxes(javafx.scene.control.CheckBox parent,
                java.util.List<javafx.scene.control.CheckBox> children) {
            FeatureUiBridgeV1.bindChildCheckBoxes(parent, children);
        }

        public static void setSplitWidth(SplitPane pane, String key) {''')
change('src/main/java/logbook/plugin/api/FeatureUiV1.java',api)
change('src/main/java/logbook/internal/gui/FeatureUiBridgeV1.java',lambda s:one(s,
    '    public static String columnName(TableColumn<?, ?> column) { return Tools.Tables.getColumnName(column); }',
'''    public static void storeTable(TableView<?> table, String title, Stage owner) throws IOException {
        TableTool.store(table, title, owner);
    }

    public static void bindChildCheckBoxes(javafx.scene.control.CheckBox parent,
            java.util.List<javafx.scene.control.CheckBox> children) {
        Tools.Controls.bindChildCheckBoxes(parent, children);
    }

    public static String columnName(TableColumn<?, ?> column) { return Tools.Tables.getColumnName(column); }'''))

icon_old=r'''                SlotitemMst mst = SlotitemMstCollection.get()
                        .getSlotitemMap()
                        .get(itemId);

                if (mst != null) {
                    this.setGraphic(Tools.Controls.zoomImage(new ImageView(Items.itemImage(mst))));
                    this.setText(mst.getName());
                }'''
icon_new=r'''                this.setGraphic(itemId == null ? null : FeatureUiV1.Controls.zoomImage(
                        new ImageView(EquipmentImagesV1.item(itemId))));
                this.setText(itemId == null ? null : EquipmentDataV1.itemName(itemId));'''

def common(s):
    s=one(s,'package logbook.internal.gui;','''package logbook.internal.gui;

import logbook.plugin.api.FeatureUiV1;
import logbook.plugin.api.EquipmentDataV1;
import logbook.plugin.api.EquipmentImagesV1;''')
    s=one(s,'extends WindowController','extends FeatureUiV1.Window')
    s=one(s,icon_old,icon_new)
    for imp in ['logbook.bean.SlotItemCollection','logbook.bean.SlotitemMst','logbook.bean.SlotitemMstCollection','logbook.internal.kancolle.Items']:
        s=s.replace('import '+imp+';\n','')
    s=re.sub(r'\bTableTool\b','FeatureUiV1.Tables',s)
    s=s.replace('Tools.Controls.','FeatureUiV1.Controls.')
    return s

def items(s):
    s=common(s)
    s=s.replace('import logbook.bean.Ship;\n','').replace('import logbook.bean.SlotItem;\n','')
    s=s.replace('import logbook.internal.kancolle.Ships;\n','')
    s=s.replace('import logbook.bean.SlotitemEquiptype;','import logbook.plugin.api.EquipmentDataV1.Category;')
    s=sub(s,r'ObservableList<Item> items = SlotitemMstCollection\.get\(\)\s*\.getSlotitemMap\(\)\s*\.values\(\)\s*\.stream\(\)',
          'ObservableList<Item> items = EquipmentDataV1.inventory().definitions().stream()')
    s=sub(s,r'Map<Integer, SlotitemMst> slotitemMap = SlotitemMstCollection\.get\(\)\.getSlotitemMap\(\);\s*Set<Integer> existingTypes = SlotItemCollection\.get\(\)\.getSlotitemMap\(\)\.values\(\)\.stream\(\).*?\.collect\(Collectors\.toSet\(\)\);',
          'Set<Integer> existingTypes = EquipmentDataV1.inventory().definitions().stream()\n                    .map(EquipmentDataV1.Definition::type2).collect(Collectors.toSet());')
    s=one(s,'Map<String, List<SlotitemEquiptype>> categories = Items.getCategories();',
            'Map<String, List<Category>> categories = EquipmentDataV1.categories();')
    s,count=re.subn(r'SlotItemCollection\.get\(\)\s*\.getSlotitemMap\(\)\s*\.values\(\)\s*\.stream\(\)',
                    'EquipmentDataV1.inventory().owned().stream()',s)
    assert count==2,count
    s=one(s,'e -> e.getSlotitemId().equals(value.idProperty().get())','e -> e.masterId() == value.idProperty().get()')
    s=re.sub(r'\bSlotItem\b','EquipmentDataV1.Owned',s)
    s=re.sub(r'\bDetailItem\b','EquipmentDetailItem',s)
    s=re.sub(r'\bShip\b','EquipmentDataV1.Owner',s)
    s=one(s,'Ships.shipWithItemImage(ship)','EquipmentImagesV1.owner(ship == null ? null : ship.id())')
    s=one(s,'Ships.toName(ship)','ship.displayName()')
    s=one(s,'toRemodelItem(SlotitemMst slotitem,','toRemodelItem(EquipmentDataV1.Definition slotitem,')
    s=one(s,'Item item = Item.toItem(slotitem);','Item item = EquipmentProjection.item(slotitem);')
    assert not re.search(r'\b(?:SlotItemCollection|SlotitemMstCollection|Ships|Items|SlotitemMst|Ship)\b',s)
    return s
change('src/main/java/logbook/internal/gui/ItemItemController.java',items)

def aircraft(s):
    s=common(s)
    s=sub(s,r'Stream<AirBaseItem> listItems = SlotItemCollection\.get\(\)\s*\.getSlotitemMap\(\)\s*\.values\(\)\.stream\(\)\s*\.filter\(AirBaseItem::isTarget\)\s*\.map\(AirBaseItem::toAirBaseItem\);',
          'Stream<AirBaseItem> listItems = EquipmentDataV1.aircraft().stream().map(EquipmentProjection::aircraft);')
    assert not re.search(r'\b(?:SlotItemCollection|SlotitemMstCollection|Items|SlotitemMst)\b',s)
    return s
change('src/main/java/logbook/internal/gui/ItemAirBaseController.java',aircraft)
change('src/main/java/logbook/internal/gui/ItemController.java',lambda s:one(s,
    'public class ItemController extends WindowController','public class ItemController extends logbook.plugin.api.FeatureUiV1.Window'))

# New profile is additive to the existing v2 and record-ui profiles. Older host builds still work.
def verifier(s):
    s=one(s,'def required_entries(module_id):',r'''EQUIPMENT_UI_PROFILE = 'equipment-ui-in-status-v1'
EQUIPMENT_UI_API = 'logbook/plugin/api/EquipmentDataV1.class'
EQUIPMENT_UI_FAMILIES = ('ItemController', 'ItemItemController', 'ItemAirBaseController',
                         'EquipmentProjection', 'EquipmentDetailItem')
EQUIPMENT_UI_RESOURCES = ('item', 'item_item', 'item_airbase')


def equipment_ui_entry(name):
    return (name in {'logbook/gui/' + resource + '.fxml' for resource in EQUIPMENT_UI_RESOURCES}
            or any(name == 'logbook/internal/gui/' + family + '.class'
                   or name.startswith('logbook/internal/gui/' + family + '$') and name.endswith('.class')
                   for family in EQUIPMENT_UI_FAMILIES))


def equipment_ui_required_entries():
    return ({'logbook/internal/gui/' + family + '.class' for family in EQUIPMENT_UI_FAMILIES}
            | {'logbook/gui/' + resource + '.fxml' for resource in EQUIPMENT_UI_RESOURCES})


def required_entries(module_id):''')
    s=one(s,"    profile = report.get('record_ui_profile')", "    equipment_profile = report.get('equipment_ui_profile')\n    if equipment_profile is not None and equipment_profile != EQUIPMENT_UI_PROFILE:\n        raise ValueError('Unsupported equipment UI packaging profile')\n    profile = report.get('record_ui_profile')")
    s=one(s,"    record_profile = report.get('record_ui_profile') == RECORD_UI_PROFILE", "    equipment_profile = report.get('equipment_ui_profile') == EQUIPMENT_UI_PROFILE\n    record_profile = report.get('record_ui_profile') == RECORD_UI_PROFILE")
    s=one(s,"            if path == JARS[0]:\n                if (RECORD_UI_API in names) != record_profile:", "            if path == JARS[0]:\n                if (EQUIPMENT_UI_API in names) != equipment_profile:\n                    raise ValueError('Equipment data API and packaging profile disagree')\n                if equipment_profile and any(equipment_ui_entry(name) for name in names):\n                    raise ValueError('Equipment UI remains in the host instead of status')\n                if (RECORD_UI_API in names) != record_profile:")
    s=one(s,"            required = required_entries(module['id'])", "            required = required_entries(module['id'])\n            if equipment_profile:\n                if module['id'] == 'status':\n                    required |= equipment_ui_required_entries()\n                elif any(equipment_ui_entry(name) for name in names):\n                    raise ValueError('Equipment UI belongs to the status module')")
    return s
change('scripts/run_feature_distribution.py',verifier)

def builder(s):
    s=one(s,'record_ui_entry, record_ui_required_entries)', 'record_ui_entry, record_ui_required_entries, EQUIPMENT_UI_API, EQUIPMENT_UI_PROFILE,\n                                     equipment_ui_entry, equipment_ui_required_entries)')
    s=one(s,'def owner(name: str, record_ui: bool = False) -> str | None:',
          "def owner(name: str, record_ui: bool = False, equipment_ui: bool = False) -> str | None:\n    if equipment_ui and equipment_ui_entry(name):\n        return 'status'")
    s=one(s,'    record_ui = RECORD_UI_API in entries', "    equipment_ui = EQUIPMENT_UI_API in entries\n    if equipment_ui:\n        missing = equipment_ui_required_entries() - entries.keys()\n        if missing:\n            raise ValueError('Missing equipment UI input: ' + sorted(missing)[0])\n    record_ui = RECORD_UI_API in entries")
    assert s.count('owner(name, record_ui)')==1
    s=s.replace('owner(name, record_ui)','owner(name, record_ui, equipment_ui)')
    assert s.count('owner(n, record_ui)')==1
    s=s.replace('owner(n, record_ui)','owner(n, record_ui, equipment_ui)')
    s=one(s,'    if record_ui:\n        manifest[\'record_ui_profile\'] = RECORD_UI_PROFILE',
          "    if equipment_ui:\n        manifest['equipment_ui_profile'] = EQUIPMENT_UI_PROFILE\n    if record_ui:\n        manifest['record_ui_profile'] = RECORD_UI_PROFILE")
    return s
change('scripts/build_feature_distribution.py',builder)
Path('../evidence/equipment-data-paths.json').write_text(json.dumps(changed,indent=2))
print('EQUIPMENT_UI_PREPARED controllers=3 data_contract=true module=status legacy_v2_retained=true')
