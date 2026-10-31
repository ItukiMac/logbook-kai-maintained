package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeatureUiBoundaryTest {
    @Test
    void featureBytecodeDoesNotLinkPackagePrivateHostGuiServices() throws Exception {
        for (String controller : List.of("CalcExpController", "CalcExpSeaAreaEditorController", "MissionCheck",
                "ProbCalcController", "LastDanceCalcController", "ResourceChartController", "ExpChartController",
                "RequireNdockController", "AirBaseController", "UseitemController")) {
            try (var in = getClass().getResourceAsStream(controller + ".class")) {
                assertNotNull(in, controller);
                // Class constants are UTF-8; these ASCII symbol names survive byte-for-byte.
                String symbols = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                for (String unsafe : List.of("logbook/internal/gui/TableTool",
                        "logbook/internal/gui/InternalFXMLLoader")) {
                    assertFalse(symbols.contains(unsafe), controller + " links host-private service " + unsafe);
                }
            }
        }
    }
}
