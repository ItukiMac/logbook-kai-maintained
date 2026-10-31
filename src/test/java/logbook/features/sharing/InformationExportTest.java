package logbook.features.sharing;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class InformationExportTest {
    @Test void csvEscapesNestedPathsQuotesAndSpreadsheetFormulas() {
        String value=InformationExport.csv(Map.of("a/b",List.of("=1+1","a,\"b\"")));
        assertTrue(value.contains("/a~1b/0"));assertTrue(value.contains("'=1+1"));assertTrue(value.contains("a,\"\"b\"\""));
    }
    @Test void jsonPreservesUnknownAndUnicode() {
        Map<String,Object> input=new LinkedHashMap<>();input.put("任務",null);
        String value=InformationExport.json(input);assertTrue(value.contains("任務"));assertTrue(value.contains("null"));
    }
}
