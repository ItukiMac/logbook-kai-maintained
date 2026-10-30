package logbook.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;

/** Committed literal source facts; a separate two-source policy may update only these numeric values. */
final class ReviewedNumericFacts {
    private ReviewedNumericFacts() { }
    static JsonNode read() throws Exception {
        var root=PreparedGameDefinitions.JSON.readTree(Files.readAllBytes(Path.of("docs/maintenance/routine-numeric-facts.json")));
        assertEquals("logbook-reviewed-numeric-facts-v1",root.path("format").asText());
        assertFalse(root.path("whole_rule_verified").asBoolean(true));return root.path("definitions");
    }
    static int value(String id,String field) throws Exception {
        var node=read().path("src/main/resources/logbook/mission/"+id+".json").path("values").path(field);
        assertTrue(node.isIntegralNumber(),id+" "+field);return node.asInt();
    }
    static Map<String,Integer> values(String field) throws Exception {
        var result=new TreeMap<String,Integer>();
        for(var entry:read().properties()) {
            var number=entry.getValue().path("values").path(field);
            if(!number.isMissingNode()) {assertTrue(number.isIntegralNumber());result.put(entry.getKey(),number.asInt());}
        }
        return result;
    }
}
