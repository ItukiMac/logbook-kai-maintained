from pathlib import Path
import json
changed=json.loads(Path('../evidence/equipment-data-paths.json').read_text())
def new(path,text):
    p=Path(path);assert not p.exists(),path;p.parent.mkdir(parents=True,exist_ok=True)
    p.write_text(text,encoding='utf8');changed.append(path)

new('src/test/java/logbook/core/EquipmentDataContractTest.java',r'''package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import logbook.bean.Ship;
import logbook.bean.SlotItem;
import logbook.bean.SlotitemMst;
import logbook.plugin.api.EquipmentDataV1;
import logbook.plugin.api.EquipmentDataV1.*;
import logbook.plugin.api.EquipmentImagesV1;
import org.junit.jupiter.api.Test;

class EquipmentDataContractTest {
    static SlotItem item(int id, int master) {
        SlotItem value = new SlotItem(); value.setId(id); value.setSlotitemId(master);
        value.setAlv(7); value.setLevel(4); value.setLocked(true); return value;
    }
    static Ship ship(int id, List<Integer> slots, Integer extra) {
        Ship value = new Ship(); value.setId(id); value.setShipId(100 + id);
        value.setSlot(slots); value.setSlotEx(extra); return value;
    }
    static SlotitemMst master() {
        SlotitemMst value = new SlotitemMst(); value.setId(7); value.setName("synthetic");
        value.setType(new ArrayList<>(List.of(0,0,6,7,0)));
        value.setHoug(1); value.setHoum(2); value.setLeng(3); value.setLuck(4);
        value.setHouk(5); value.setBaku(6); value.setRaig(7); value.setSaku(8);
        value.setTais(9); value.setTyku(10); value.setSouk(11); return value;
    }
    @Test void versionIsExplicit() { assertEquals(1, EquipmentDataV1.version()); }
    @Test void publicDataSignaturesContainNeitherMutableBeansNorJavafx() {
        for (var method : EquipmentDataV1.class.getMethods()) {
            String signature = method.toGenericString();
            assertFalse(signature.contains("logbook.bean"));
            assertFalse(signature.contains("javafx"));
            assertFalse(signature.contains("logbook.internal"));
        }
    }
    @Test void publicRowsHaveOnlyImmutableComponents() {
        for (var type : List.of(Owner.class, Owned.class, Definition.class, Aircraft.class, Category.class)) {
            assertTrue(type.isRecord()); assertTrue(Modifier.isFinal(type.getModifiers()));
            for (var part : type.getRecordComponents()) {
                var t = part.getType();
                assertTrue(t.isPrimitive() || t == String.class || t == Integer.class || t == Owner.class, part.toString());
            }
        }
    }
    @Test void inventoryCopiesAllInputContainersAndRejectsMutation() {
        var values = new ArrayList<Owned>(); values.add(EquipmentDataService.copyOwned(item(1,7),null));
        var definitions = new ArrayList<Definition>(); definitions.add(EquipmentDataService.copyDefinition(master(),"fighter",1));
        var warnings = new ArrayList<>(List.of("example"));
        var snapshot = new Inventory(definitions,values,warnings);
        values.clear(); definitions.clear(); warnings.clear();
        assertEquals(1,snapshot.owned().size()); assertEquals(1,snapshot.definitions().size());
        assertEquals(List.of("example"),snapshot.warnings());
        assertThrows(UnsupportedOperationException.class,()->snapshot.owned().clear());
        assertThrows(UnsupportedOperationException.class,()->snapshot.definitions().clear());
        assertThrows(UnsupportedOperationException.class,()->snapshot.warnings().clear());
    }
    @Test void ownedSnapshotDoesNotShareMutableItemState() {
        var raw = item(1,7); var owner = new Owner(2,102,"owner");
        var value = EquipmentDataService.copyOwned(raw,owner);
        raw.setAlv(0);raw.setLevel(0);raw.setLocked(false);raw.setSlotitemId(99);
        assertEquals(7,value.masterId());assertEquals(7,value.alv());assertEquals(4,value.level());
        assertTrue(value.locked());assertEquals(owner,value.owner());
    }
    @Test void missingProficiencyIsRetainedRatherThanStoredBackAsZero() {
        var raw=item(1,7);raw.setAlv(null);raw.setLevel(null);raw.setLocked(null);
        var value=EquipmentDataService.copyOwned(raw,null);
        assertNull(value.alv());assertNull(value.level());assertFalse(value.locked());assertNull(raw.getAlv());
    }
    @Test void definitionSnapshotPreservesStatsAndDetachedTypeValues() {
        var raw=master();var value=EquipmentDataService.copyDefinition(raw,"fighter",3);
        raw.getType().set(2,99);raw.setTyku(90);raw.setName("changed");
        assertEquals(6,value.type2());assertEquals(7,value.type3());assertEquals(10,value.tyku());
        assertEquals("synthetic",value.name());assertEquals(3,value.count());
        assertEquals(List.of(1,2,3,4,5,6,7,8,9,10,11),List.of(value.houg(),value.houm(),value.leng(),value.luck(),
                value.houk(),value.baku(),value.raig(),value.saku(),value.tais(),value.tyku(),value.souk()));
    }
    @Test void countsIncludeAllOwnedInstancesWithoutScanningPerMaster() {
        var values=List.of(EquipmentDataService.copyOwned(item(1,7),null), EquipmentDataService.copyOwned(item(2,7),null),
                EquipmentDataService.copyOwned(item(3,999),null));
        assertEquals(java.util.Map.of(7,2,999,1),EquipmentDataService.counts(values));
    }
    @Test void normalAndExpansionSlotsMapToTheSameDetachedOwner() {
        var raw=ship(2,new ArrayList<>(List.of(11,12,-1)),13);
        var index=EquipmentDataService.owners(List.of(raw),s->"name"+s.getId());
        assertEquals(index.get(11),index.get(13));assertEquals(2,index.get(12).id());
        assertEquals("name2",index.get(11).displayName());assertEquals(3,index.size());
        raw.getSlot().clear();raw.setSlotEx(-1);raw.setId(3);
        assertEquals(2,index.get(13).id());assertEquals(3,index.size());
    }
    @Test void emptyAndUnopenedSlotIdsAreNotEquipmentOwners() {
        var raw=ship(1,Arrays.asList(-1,0,null),-1);
        assertTrue(EquipmentDataService.owners(List.of(raw),s->"owner").isEmpty());
    }
    @Test void nullSlotsDoNotPreventAnExpansionSlotOwner() {
        var index=EquipmentDataService.owners(List.of(ship(1,null,11)),s->"owner");
        assertEquals(1,index.size());assertEquals(1,index.get(11).id());
    }
    @Test void duplicateObservedAssignmentsKeepTheExistingFirstOwnerPolicy() {
        var index=EquipmentDataService.owners(List.of(ship(1,List.of(11),-1),ship(2,List.of(11),-1)),s->"owner");
        assertEquals(1,index.get(11).id());
    }
    @Test void noOwnersDoesNotInventAnOwningShip() {
        assertTrue(EquipmentDataService.owners(List.of(),s->"unused").isEmpty());
        assertNull(EquipmentDataService.copyOwned(item(1,7),null).owner());
    }
    @Test void hostImageRenderingRejectsBackgroundThreads() {
        assertThrows(IllegalStateException.class,()->EquipmentImagesV1.item(7));
        assertThrows(IllegalStateException.class,()->EquipmentImagesV1.owner(null));
    }
    @Test void legacyViewFactoriesRemainAvailableForExistingBinaries() throws Exception {
        assertEquals(logbook.internal.gui.Item.class,
                logbook.internal.gui.Item.class.getMethod("toItem",SlotitemMst.class).getReturnType());
        assertEquals(logbook.internal.gui.AirBaseItem.class,
                logbook.internal.gui.AirBaseItem.class.getMethod("toAirBaseItem",SlotItem.class).getReturnType());
        assertEquals(logbook.internal.gui.DetailItem.class,
                logbook.internal.gui.DetailItem.class.getMethod("toDetailItem",SlotItem.class).getReturnType());
    }
}
''')

new('src/test/java/logbook/internal/gui/EquipmentProjectionTest.java',r'''package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import logbook.plugin.api.EquipmentDataV1.*;
import logbook.plugin.api.FeatureUiV1;
import org.junit.jupiter.api.Test;

class EquipmentProjectionTest {
    static Definition definition() { return new Definition(7,6,7,"fixture","fighter",3,1,2,3,4,5,6,7,8,9,10,11); }
    @Test void mutableTablePropertiesAreLocalNotSharedGameState() {
        var source=definition();var view=EquipmentProjection.item(source);
        assertEquals("fixture",view.getName());assertEquals(3,view.getCount());assertEquals(10,view.getTyku());
        view.setCount(99);view.setTyku(0);assertEquals(3,source.count());assertEquals(10,source.tyku());
    }
    @Test void detailRowNeverRetainsAMutableShip() {
        var owner=new Owner(42,100,"synthetic owner");
        var view=EquipmentDetailItem.toDetailItem(new Owned(1,7,6,4,true,owner));
        assertEquals(42,view.getShipId());assertEquals(owner,view.getShip());assertEquals(6,view.getAlv());
        assertTrue(view.toString().endsWith("synthetic owner"));
    }
    @Test void nullableLevelsKeepThePriorDisplayOnlyDefaults() {
        var source=new Owned(1,7,null,null,false,null);var view=EquipmentDetailItem.toDetailItem(source);
        assertEquals(0,view.getAlv());assertEquals(0,view.getLevel());assertNull(view.getShipId());
        assertTrue(view.toString().endsWith("未所持"));assertNull(source.alv());assertNull(source.level());
    }
    @Test void aircraftProjectionCopiesExistingComputedValuesWithoutRecalculation() {
        var source=new Aircraft(7,6,7,"fighter","aircraft",7,3,42,60,3,5,4,18,10,0,0,1,2,3,4);
        var view=EquipmentProjection.aircraft(source);
        assertEquals(42,view.getSeiku());assertEquals(60,view.getInterceptSeiku());assertEquals(18,view.getCost());
        assertEquals(5,view.getDistanceTaiteichan());assertEquals(4,view.getDistanceCatalina());assertEquals(1,view.getCount());
        view.setCount(8);view.setSeiku(0);assertEquals(42,source.seiku());
    }
    @Test void parentAndBothTabsUsePublicWindowLifecycle() {
        for(var type:List.of(ItemController.class,ItemItemController.class,ItemAirBaseController.class))
            assertTrue(FeatureUiV1.Window.class.isAssignableFrom(type));
    }
    @Test void allControllerClassesIncludingCellsUseDataContractsNotLiveBeans() throws Exception {
        Path root=Path.of(ItemController.class.getProtectionDomain().getCodeSource().getLocation().toURI()).resolve("logbook/internal/gui");
        int count=0;
        try(var paths=Files.list(root)) {
            for(Path path:paths.filter(p->p.toString().endsWith(".class")).toList()) {
                String name=path.getFileName().toString();
                if(List.of("ItemController","ItemItemController","ItemAirBaseController").stream()
                        .noneMatch(n->name.equals(n+".class")||name.startsWith(n+"$")))continue;
                count++;String bytes=new String(Files.readAllBytes(path),StandardCharsets.ISO_8859_1);
                for(String forbidden:List.of("logbook/bean/Ship;","logbook/bean/SlotItem;","logbook/bean/SlotItemCollection",
                        "logbook/bean/SlotitemMstCollection","logbook/bean/ShipCollection","logbook/internal/kancolle/Items",
                        "logbook/internal/kancolle/Ships","logbook/internal/gui/TableTool","logbook/internal/gui/Tools"))
                    assertFalse(bytes.contains(forbidden),name+" -> "+forbidden);
            }
        }
        assertTrue(count>=3,"Controller bytecode was not inspected");
    }
}
''')

# Mirror the already-reviewed record layout tests for this independent, additive profile.
s=Path('scripts/tests/test_record_distribution_profile.py').read_text()
s=s.replace('RecordDistributionProfileTest','EquipmentDistributionProfileTest')
s=s.replace('BattleLogController$UnitDialog','ItemItemController$ShipImageCell')
s=s.replace('BattleLogController','ItemController').replace('BattleRecordDetail','EquipmentDetailItem')
s=s.replace('RECORD_UI','EQUIPMENT_UI').replace('record_ui','equipment_ui')
s=s.replace('logbook-feature-charts.jar','logbook-feature-status.jar')
s=s.replace('record_views_and_nested_dialog_to_charts','equipment_tabs_and_nested_cell_to_status')
s=s.replace('record','equipment').replace('Record','Equipment')
new('scripts/tests/test_equipment_distribution_profile.py',s)
Path('../evidence/equipment-data-paths.json').write_text(json.dumps(changed,indent=2))
print('EQUIPMENT_TESTS_PREPARED java=21 python_profile=9 fixtures_only=true')
