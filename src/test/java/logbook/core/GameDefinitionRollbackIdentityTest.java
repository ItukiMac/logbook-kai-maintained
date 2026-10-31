package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import logbook.plugin.api.GameDefinitionSnapshot;

class GameDefinitionRollbackIdentityTest {
    private static GameDefinitionSnapshot snapshot(String id, String value) {
        return new GameDefinitionSnapshot(id, Map.of("logbook/quest/1.json", value.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void changedPreviousGenerationIsRejectedWithoutEventOrSequenceGap() {
        var service = new GameDefinitionService(e -> fail(e));
        var events = new ArrayList<Long>();
        service.subscribe(event -> events.add(event.sequence()));
        service.publish(snapshot("A", "original"));
        var retained = service.snapshot();
        var second = service.publish(snapshot("B", "second"));
        assertThrows(IllegalArgumentException.class, () -> service.publish(snapshot("A", "tampered")));
        assertSame(second, service.snapshot());
        assertEquals(List.of(1L, 2L), events);
        service.publish(snapshot("C", "third"));
        assertEquals(List.of(1L, 2L, 3L), events);
        assertEquals(snapshot("A", "original").fingerprint(), retained.fingerprint());
    }

    @Test
    void genuineRollbackRetainsTheOriginalIdentity() {
        var service = new GameDefinitionService(e -> fail(e));
        var first = service.publish(snapshot("A", "original"));
        service.publish(snapshot("B", "second"));
        var rollback = service.publish(snapshot("A", "original"));
        assertEquals(first.generation(), rollback.generation());
        assertEquals(first.fingerprint(), rollback.fingerprint());
        assertSame(rollback, service.snapshot());
    }

    @Test
    void moreThanOneInterveningGenerationDoesNotForgetIdentity() {
        var service = new GameDefinitionService(e -> fail(e));
        service.publish(snapshot("A", "original"));
        for (int n = 0; n < 100; n++)
            service.publish(snapshot("next-" + n, "new-" + n));
        assertThrows(IllegalArgumentException.class, () -> service.publish(snapshot("A", "tampered")));
        assertEquals("next-99", service.snapshot().generation());
    }
}
