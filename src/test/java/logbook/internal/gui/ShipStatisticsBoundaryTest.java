package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ShipStatisticsBoundaryTest {
    @Test
    void statisticsRendererHasNoSharedModelOrPrivateGuiDependencies() throws Exception {
        try (var input = StatisticsPane.class.getResourceAsStream("StatisticsPane.class")) {
            assertNotNull(input);
            String symbols = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
            for (String forbidden : new String[] { "logbook/bean/", "logbook/constants/StatisticsShipTypeGroup",
                    "logbook/internal/gui/InternalFXMLLoader", "logbook/internal/gui/Tools",
                    "logbook/internal/kancolle/" })
                assertFalse(symbols.contains(forbidden), forbidden);
            assertTrue(symbols.contains("logbook/plugin/api/ShipStatisticsV1"));
            assertTrue(symbols.contains("logbook/plugin/api/FeatureUiV1"));
        }
    }
}
