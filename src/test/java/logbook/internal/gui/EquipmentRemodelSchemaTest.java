package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jakarta.json.JsonException;
import org.junit.jupiter.api.Test;

class EquipmentRemodelSchemaTest {
    private static ByteArrayInputStream bytes(String source) {
        return new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8));
    }
    private static Map<String, Collection<String>> read(String source) {
        Map<String, Collection<String>> result = new HashMap<>();
        ItemItemController.readRemodelDays(bytes(source), result);
        return result;
    }
    @Test void originalSlotItemFieldRetainsAllAvailableDays() {
        assertEquals(Map.of("fixture", List.of("月", "金")), read(
  "[{\"SlotItem\":\"fixture\",\"AllAvailableDays\":[\"月\",\"金\"]}]"));
    }
    @Test void javaTypeNamesAreNotExternalDataFieldNames() {
        assertTrue(read("[{\"EquipmentDataV1.Owned\":\"fixture\",\"AllAvailableDays\":[\"月\"]}]").isEmpty());
    }
    @Test void laterDuplicateKeepsTheExistingLastEntryPolicy() {
        assertEquals(Map.of("fixture", List.of("木")), read(
  "[{\"SlotItem\":\"fixture\",\"AllAvailableDays\":[\"月\"]},"
  + "{\"SlotItem\":\"fixture\",\"AllAvailableDays\":[\"木\"]}]"));
    }
    @Test void missingNameOrDaysDoesNotInventAvailability() {
        assertTrue(read("[{\"SlotItem\":\"fixture\"},{\"AllAvailableDays\":[\"月\"]},{}]").isEmpty());
    }
    @Test void resourceStreamIsClosed() {
        boolean[] closed = {false};
        var input = new ByteArrayInputStream("[]".getBytes(StandardCharsets.UTF_8)) {
  @Override public void close() { closed[0] = true; }
        };
        ItemItemController.readRemodelDays(input, new HashMap<>());
        assertTrue(closed[0]);
    }
    @Test void malformedJsonIsNotReportedAsAnEmptySuccessfulRead() {
        assertThrows(JsonException.class, () -> read("["));
    }
    @Test void entriesReadBeforeABadRowRemainAvailableToTheCaller() {
        Map<String, Collection<String>> result = new HashMap<>();
        assertThrows(ClassCastException.class, () -> ItemItemController.readRemodelDays(
  bytes("[{\"SlotItem\":\"fixture\",\"AllAvailableDays\":[\"火\"]},17]"),result));
        assertEquals(Map.of("fixture",List.of("火")),result);
    }
}
