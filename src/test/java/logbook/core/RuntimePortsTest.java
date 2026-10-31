package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class RuntimePortsTest {
    @Test
    void validatesPortsBeforeOpeningSockets() {
        assertEquals(8892, RuntimePorts.parse("8892"));
        for (String invalid : new String[] { "0", "1023", "65536", "-1", "abc", "8892 ", "" }) {
            assertThrows(IllegalArgumentException.class, () -> RuntimePorts.parse(invalid));
        }
        assertDoesNotThrow(() -> RuntimePorts.validatePair(8892, 8889));
        assertThrows(IllegalArgumentException.class, () -> RuntimePorts.validatePair(8892, 8892));
    }
}
