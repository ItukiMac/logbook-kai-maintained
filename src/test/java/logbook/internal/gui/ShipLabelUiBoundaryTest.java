package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ShipLabelUiBoundaryTest {
    @Test
    void shipTableDelegatesSharedLabelWritesToThePublicCommandContract() throws Exception {
        try (var input = ShipTablePane.class.getResourceAsStream("ShipTablePane.class")) {
            assertNotNull(input);
            String constants = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertFalse(constants.contains("logbook/bean/ShipLabelCollection"));
            assertTrue(constants.contains("logbook/plugin/api/ShipLabelsV1"));
        }
    }
}
