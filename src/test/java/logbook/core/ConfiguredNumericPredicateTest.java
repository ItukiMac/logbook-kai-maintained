package logbook.core;
import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import logbook.bean.MissionCondition;
import logbook.bean.Ship;
import org.junit.jupiter.api.Test;

/** Actual consumer boundary checks for every configured pure flagship/fleet-level clause. */
class ConfiguredNumericPredicateTest {
    private List<MissionCondition> nodes(MissionCondition root){var result=new ArrayList<MissionCondition>();result.add(root);if(root.getConditions()!=null)for(var c:root.getConditions())result.addAll(nodes(c));return result;}
    private List<Ship> ships(int level){var s=new Ship();s.setLv(level);return new ArrayList<>(List.of(s));}
    private MissionCondition load(String path)throws Exception {
        String resource=path.substring("src/main/resources/".length());
        try(var in=getClass().getClassLoader().getResourceAsStream(resource)){assertNotNull(in,path);return PreparedGameDefinitions.JSON.readValue(in,MissionCondition.class);}
    }
    @Test void everyConfiguredFlagshipThresholdRejectsBelowAndAcceptsAtBoundary()throws Exception {
        var facts=ReviewedNumericFacts.values("flagship_min_level");assertTrue(facts.size()>=40);
        for(var entry:facts.entrySet()){
            var matches=nodes(load(entry.getKey())).stream().filter(c->Objects.equals(c.getOrder(),1)&&c.getLevel()!=null).toList();
            assertEquals(1,matches.size(),entry.getKey());var clause=matches.getFirst();assertNull(clause.getShipType());
            assertEquals(entry.getValue(),clause.getLevel());assertFalse(clause.test(ships(entry.getValue()-1)),entry.getKey());assertTrue(clause.test(ships(entry.getValue())),entry.getKey());
        }
    }
    @Test void everyConfiguredFleetLevelThresholdUsesTheActualSumPredicate()throws Exception {
        var facts=ReviewedNumericFacts.values("fleet:レベル");assertFalse(facts.isEmpty());
        for(var entry:facts.entrySet()){
            var matches=nodes(load(entry.getKey())).stream().filter(c->"レベル".equals(c.getCountType())).toList();assertEquals(1,matches.size());var clause=matches.getFirst();
            assertEquals(entry.getValue(),clause.getValue());assertFalse(clause.test(ships(entry.getValue()-1)));assertTrue(clause.test(ships(entry.getValue())));
        }
    }
}
