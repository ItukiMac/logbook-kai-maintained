package logbook.core;

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
