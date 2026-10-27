package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.stream.Stream;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.ObjectMapper;
import logbook.bean.*;
import org.junit.jupiter.api.*;

/** Both named remodels are individually required; aggregate counts must not substitute duplicates. */
class NineteenthDestroyerQuestTest {
    private static AppQuestCondition.FleetCondition condition() throws Exception {
        try(var in=NineteenthDestroyerQuestTest.class.getResourceAsStream("/logbook/quest/904.json")) {
            return logbook.internal.json.JsonMappers.builder().enable(tools.jackson.core.json.JsonReadFeature.ALLOW_JAVA_COMMENTS).build().readValue(in,AppQuestCondition.class).getFilter().getFleet();
        }
    }
    private static ShipMst ship(String name) {
        var m=mock(ShipMst.class);var type=new Stype();type.setName("駆逐艦");
        when(m.getName()).thenReturn(name);when(m.asStype()).thenReturn(Optional.of(type));return m;
    }
    private static List<ShipMst> fleet(String a,String b,int size) {
        var ships=new ArrayList<ShipMst>();ships.add(ship(a));ships.add(ship(b));
        for(int i=2;i<size;i++)ships.add(ship("自由艦"+i));return ships;
    }
    @TestFactory Stream<DynamicTest> bothDistinctNamedShipsRequired() {
        return List.of("綾波改二","敷波改二").stream().map(name->DynamicTest.dynamicTest("duplicate "+name,()->
            assertFalse(condition().test(fleet(name,name,6)))));
    }
    @TestFactory Stream<DynamicTest> exactlySixShipsWithBothSpecifiedRemodels() {
        return Stream.of(2,3,4,5,6,7).map(size->DynamicTest.dynamicTest("size "+size,()->
            assertEquals(size==6,condition().test(fleet("綾波改二","敷波改二",size)))));
    }
    @Test void neitherFlagshipOrderNorFreeShipsAreRestricted() throws Exception {
        var ships=fleet("敷波改二","綾波改二",6);Collections.rotate(ships,2);
        var before=new ArrayList<>(ships);assertTrue(condition().test(ships));assertEquals(before,ships);
    }
    @Test void earlierRemodelDoesNotSubstitute() throws Exception {
        assertFalse(condition().test(fleet("綾波改","敷波改二",6)));
        assertFalse(condition().test(fleet("綾波改二","敷波改",6)));
    }
}
