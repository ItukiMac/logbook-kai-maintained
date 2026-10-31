package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AirBaseStatusBoundaryTest {
    @Test
    void controllerDoesNotReadLiveAreaMasterOrRecalculateSelection() throws Exception {
        try (var stream = AirBaseController.class.getResourceAsStream("AirBaseController.class")) {
            assertNotNull(stream);
            String constants = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertFalse(constants.contains("logbook/bean/MapareaCollection"));
            assertFalse(constants.contains("logbook/internal/kancolle/AirBases"));
        }
    }

    @Test
    void legacyPublicViewModelAccessorsRemainAvailable() throws Exception {
        assertNotNull(AirBaseController.AreaTable.class.getMethod("getAirBase"));
        assertNotNull(AirBaseController.AreaTable.class.getMethod("setAirBase", logbook.bean.Mapinfo.AirBase.class));
        assertNotNull(AirBaseController.Plane.class.getMethod("getPlaneInfo"));
        assertNotNull(AirBaseController.Plane.class.getMethod("setPlaneInfo", logbook.bean.Mapinfo.PlaneInfo.class));
    }
}
