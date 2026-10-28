from pathlib import Path

def change(path,fn):
    p=Path(path);raw=p.read_bytes();e='\r\n' if b'\r\n' in raw else '\n';s=raw.decode().replace('\r\n','\n');n=fn(s);assert s!=n;p.write_bytes(n.replace('\n',e).encode())
def once(s,a,b):
    assert s.count(a)==1,(a,s.count(a));return s.replace(a,b)
def ui(s):
    s=once(s,'BaseView before = selected == null || selected.getValue() == null ? null : selected.getValue().view;',
             'String selection = selected == null || selected.getValue() == null ? null : selected.getValue().selectionKey;')
    s=once(s,'var normal = group("通常海域"); var event = group("イベント海域"); var unknown = group("不明海域");',
             'var normal = group("通常海域", "normal"); var event = group("イベント海域", "event"); var unknown = group("不明海域", "unknown");')
    s=once(s,'TreeItem<AreaTable> restore = null;','TreeItem<AreaTable> restore = null;') if False else s
    s=once(s,'sea = group(value.areaName()); areas.put(areaKey, sea);','sea = group(value.areaName(), "area:" + areaKey); areas.put(areaKey, sea);')
    s=once(s,'AreaTable row = new AreaTable(); row.view = value;', 'AreaTable row = new AreaTable(); row.view = value; row.selectionKey = "base:" + value.key();')
    s=once(s,'            if (before != null && before.key().equals(value.key())) restore = leaf;\n','')
    s=once(s,'        if (restore != null) this.areaTable.getSelectionModel().select(restore);', '''        if (selection != null) {
            var queue = new java.util.ArrayDeque<TreeItem<AreaTable>>(); queue.add(root);
            while (!queue.isEmpty()) {
                var item = queue.removeFirst(); queue.addAll(item.getChildren());
                if (selection.equals(item.getValue().selectionKey)) { restore = item; break; }
            }
        }
        if (restore != null) this.areaTable.getSelectionModel().select(restore);''')
    s=once(s,'private static TreeItem<AreaTable> group(String name) {\n        AreaTable value = new AreaTable(); value.setName(name);',
             'private static TreeItem<AreaTable> group(String name, String key) {\n        AreaTable value = new AreaTable(); value.setName(name); value.selectionKey = key;')
    s=once(s,'        private BaseView view;', '        private BaseView view;\n        private String selectionKey;')
    return s
change('src/main/java/logbook/internal/gui/AirBaseController.java',ui)
def tests(s):
    s=once(s,'class AirBaseStatusContractTest {','''class AirBaseStatusContractTest {
    @org.junit.jupiter.api.BeforeAll static void initResourceReader() { LogBookCoreServices.init(List.of()); }
    @org.junit.jupiter.api.AfterAll static void closeResourceReader() { LogBookCoreServices.closeContainer(); }
    @Test void reconnaissanceKeepsExistingMaximumMultiplierAndRounding() {
        Fixture f=new Fixture();SlotItem item=new SlotItem();item.setId(102);item.setSlotitemId(1002);item.setAlv(0);item.setLevel(0);
        SlotitemMst recon=new SlotitemMst();recon.setId(1002);recon.setName("recon");recon.setType(List.of(0,0,49,49));
        recon.setTyku(0);recon.setHouk(0);recon.setHoum(0);recon.setSaku(9);
        PlaneInfo p=new PlaneInfo();p.setSquadronId(2);p.setSlotid(102);p.setCount(4);p.setMaxCount(4);p.setCond(1);p.setState(1);f.base.getPlaneInfo().add(p);
        var items=Map.of(101,f.item,102,item);var masters=Map.of(1001,f.master,1002,recon);
        var sortie=AirBaseStatusService.snapshot(List.of(f.base),Map.of(6,f.area),items,masters).bases().getFirst();
        assertEquals(46,sortie.airPower());
        f.base.setActionKind(2);
        var defense=AirBaseStatusService.snapshot(List.of(f.base),Map.of(6,f.area),items,masters).bases().getFirst();
        assertEquals(47,defense.airPower());assertEquals(46,sortie.airPower());
    }''')
    return s
change('src/test/java/logbook/core/AirBaseStatusContractTest.java',tests)
def probe(s):
    return once(s,'            Mapinfo.get().getAirBase().clear();c.update(null);', '''            var group=areas.getRoot().getChildren().getFirst();areas.getSelectionModel().select(group);
            base.setName("renamed");c.update(null);
            require(areas.getSelectionModel().getSelectedItem()!=null && "通常海域".equals(areas.getSelectionModel().getSelectedItem().getValue().nameProperty().get()),"Group selection was lost on refresh");
            select(areas,6,1);
            Mapinfo.get().getAirBase().clear();c.update(null);''')
change('scripts/java/AirBaseStatusGuiProbe.java',probe)
change('scripts/verify_r3.sh',lambda s:once(s,"for s in airbase_suites)>=22","for s in airbase_suites)>=23"))
print('AIRBASE_REFINED group_selection_retained=true additional_recon_regression=true')
