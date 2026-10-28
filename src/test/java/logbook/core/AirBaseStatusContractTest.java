package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import logbook.bean.*;
import logbook.bean.Mapinfo.*;
import logbook.internal.kancolle.AirBases;
import logbook.plugin.api.AirBaseStatusV1;
import logbook.plugin.api.AirBaseStatusV1.*;
import org.junit.jupiter.api.Test;

/** Pure synthetic inputs; never loads the default runtime or writes a user file. */
class AirBaseStatusContractTest {
    private static final class Fixture {
        AirBase base=new AirBase();PlaneInfo plane=new PlaneInfo();SlotItem item=new SlotItem();SlotitemMst master=new SlotitemMst();Maparea area=new Maparea();
        Fixture() {
            base.setAreaId(6);base.setRid(1);base.setName("first");base.setActionKind(1);
            var d=new Distance();d.setBase(5);d.setBonus(2);base.setDistance(d);
            plane.setSquadronId(1);plane.setState(1);plane.setSlotid(101);plane.setCount(16);plane.setMaxCount(18);plane.setCond(1);
            base.setPlaneInfo(new ArrayList<>(List.of(plane)));
            item.setId(101);item.setSlotitemId(1001);item.setAlv(0);item.setLevel(0);
            master.setId(1001);master.setName("synthetic fighter");master.setType(new ArrayList<>(List.of(0,0,6,6)));
            master.setTyku(10);master.setHouk(2);master.setHoum(1);master.setSaku(9);
            area.setId(6);area.setName("normal");area.setType(0);
        }
        Snapshot read(){return AirBaseStatusService.snapshot(List.of(base),Map.of(6,area),Map.of(101,item),Map.of(1001,master));}
        BaseView row(){return read().bases().getFirst();}
    }
    @Test void publicContractExposesNoBeansOrJavafx() {
        assertEquals(1,AirBaseStatusV1.version());
        for(Class<?> c:List.of(AirBaseStatusV1.class,BaseView.class,PlaneView.class,Snapshot.class))
            for(var m:c.getDeclaredMethods()) {
                assertFalse(m.toGenericString().contains("logbook.bean"));assertFalse(m.toGenericString().contains("javafx"));
            }
    }
    @Test void listsAndReturnedValuesAreDetached() {
        Fixture f=new Fixture();Snapshot s=f.read();BaseView b=s.bases().getFirst();
        f.base.setName("changed");f.plane.setCount(0);f.item.setAlv(7);f.master.setTyku(99);f.master.setName("other");f.area.setName("changed area");f.base.getPlaneInfo().clear();
        assertEquals("first",b.name());assertEquals("normal",b.areaName());assertEquals(40,b.airPower());assertEquals(16,b.planes().getFirst().count());
        assertEquals("synthetic fighter",b.planes().getFirst().name());assertEquals(0,b.planes().getFirst().proficiency());
        assertThrows(UnsupportedOperationException.class,()->s.bases().clear());
        assertThrows(UnsupportedOperationException.class,()->b.planes().clear());assertThrows(UnsupportedOperationException.class,()->b.warnings().add("x"));
    }
    @Test void completeInputsKeepExistingNumericalFormula() {
        Fixture f=new Fixture();assertEquals(AirBases.airSuperiority(f.base,Map.of(101,f.item),Map.of(1001,f.master)),f.row().airPower());
        assertEquals(40,f.row().airPower());assertEquals(7,f.row().distance());
    }
    @Test void interceptorSortieAndDefenseRetainSeparateFormulas() {
        Fixture f=new Fixture();f.master.setType(List.of(0,0,48,48));assertEquals(52,f.row().airPower());
        f.base.setActionKind(2);assertEquals(56,f.row().airPower());
    }
    @Test void sameInputsProduceEqualRefreshValues() {
        Fixture f=new Fixture();assertEquals(f.read(),f.read());Snapshot before=f.read();f.master.setTyku(11);
        assertNotEquals(before,f.read());assertEquals(40,before.bases().getFirst().airPower());assertEquals(44,f.row().airPower());
    }
    @Test void missingCountCannotInventAZeroTotal() {
        Fixture f=new Fixture();f.plane.setCount(null);assertNull(f.row().airPower());assertFalse(f.row().warnings().isEmpty());
        assertEquals("未記録",AirBaseStatusV1.metric(f.row().airPower()));
    }
    @Test void knownZeroCountIsZeroNotMissing() {Fixture f=new Fixture();f.plane.setCount(0);assertEquals(0,f.row().airPower());assertEquals("0",AirBaseStatusV1.metric(0));}
    @Test void missingMasterPreservesOwnedPlaneIdentity() {
        Fixture f=new Fixture();var b=AirBaseStatusService.snapshot(List.of(f.base),Map.of(6,f.area),Map.of(101,f.item),Map.of()).bases().getFirst();
        assertNull(b.airPower());assertEquals(101,b.planes().getFirst().slotId());assertTrue(b.planes().getFirst().name().startsWith("不明装備"));
    }
    @Test void missingAreaIsNotSilentlyDropped() {
        Fixture f=new Fixture();var b=AirBaseStatusService.snapshot(List.of(f.base),Map.of(),Map.of(101,f.item),Map.of(1001,f.master)).bases().getFirst();
        assertEquals(new Key(6,1),b.key());assertNull(b.areaType());assertTrue(b.areaName().contains("不明"));
    }
    @Test void nullAndInvalidBaseIdentitiesAreDiagnosed() {
        Fixture f=new Fixture();f.base.setRid(null);var s=AirBaseStatusService.snapshot(Arrays.asList(null,f.base),Map.of(),Map.of(),Map.of());
        assertTrue(s.bases().isEmpty());assertEquals(2,s.warnings().size());
    }
    @Test void duplicateBaseKeysCannotAliasOneAnother() {
        Fixture f=new Fixture();var s=AirBaseStatusService.snapshot(List.of(f.base,f.base),Map.of(6,f.area),Map.of(101,f.item),Map.of(1001,f.master));
        assertTrue(s.bases().isEmpty());assertTrue(s.warnings().getFirst().contains("重複"));
    }
    @Test void unknownActionDoesNotReuseAnEarlierPower() {
        Fixture f=new Fixture();f.base.setActionKind(99);assertNull(f.row().airPower());assertEquals("不明(99)",f.row().actionText());
        f.base.setActionKind(null);assertEquals("未記録",f.row().actionText());
    }
    @Test void missingOrOverflowingDistanceIsNotInvented() {
        Fixture f=new Fixture();f.base.setDistance(null);assertNull(f.row().distance());
        Distance d=new Distance();d.setBase(Integer.MAX_VALUE);d.setBonus(1);f.base.setDistance(d);assertNull(f.row().distance());
    }
    @Test void negativeCountsAndOutOfRangeProficiencyAreUnknown() {
        Fixture f=new Fixture();f.plane.setCount(-1);assertNull(f.row().airPower());
        f.plane.setCount(16);f.item.setAlv(8);assertNull(f.row().airPower());
        f.item.setAlv(0);f.item.setLevel(-1);assertNull(f.row().airPower());
    }
    @Test void nullPlanesCannotHideAWholeMissingSquadron() {
        Fixture f=new Fixture();f.base.setPlaneInfo(Arrays.asList(f.plane,null));assertNull(f.row().airPower());assertEquals(1,f.row().planes().size());
        f.base.setPlaneInfo(null);assertNull(f.row().airPower());assertTrue(f.row().planes().isEmpty());
    }
    @Test void emptyRecordedSquadronsHaveAKnownZero() {Fixture f=new Fixture();f.base.setPlaneInfo(List.of());assertEquals(0,f.row().airPower());}
    @Test void supplyFatigueAndRelocationUseCapturedStates() {
        Fixture f=new Fixture();f.plane.setCond(2);BaseView before=f.row();assertTrue(before.lowSupply());assertTrue(before.fatigued());assertFalse(before.relocating());
        f.plane.setState(2);f.plane.setCond(1);assertTrue(f.row().relocating());assertFalse(f.row().fatigued());assertTrue(before.fatigued());
    }
    @Test void absentSupplyCapacityDoesNotClaimAShortage() {Fixture f=new Fixture();f.plane.setMaxCount(null);assertFalse(f.row().lowSupply());}
    @Test void legacyCalculationSignaturesRemainAvailable() throws Exception {
        assertNotNull(AirBases.class.getMethod("airSuperiority",AirBase.class));
        assertNotNull(AirBases.class.getMethod("airSuperiority",SlotItem.class,int.class,boolean.class));
    }
    @Test void directRecordConstructorsAlsoCopyLists() {
        var ps=new ArrayList<PlaneView>();var notes=new ArrayList<String>();
        BaseView b=new BaseView(new Key(1,1),"a",0,"b",0,1,0,1,0,ps,notes);
        ps.add(new PlaneView(1,0,0,0,0,1,null,"none",null,null));notes.add("changed");
        assertTrue(b.planes().isEmpty());assertTrue(b.warnings().isEmpty());
    }
}
