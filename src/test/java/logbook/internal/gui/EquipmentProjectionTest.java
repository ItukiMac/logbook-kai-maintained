package logbook.internal.gui;

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
        assertEquals("synthetic owner",view.getShip().displayName());
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
