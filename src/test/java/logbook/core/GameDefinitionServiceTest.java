package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import logbook.plugin.api.GameDataView;
import logbook.plugin.api.GameDefinitionSnapshot;

class GameDefinitionServiceTest {
    private static final String NAME = "logbook/quest/1.json";
    private static GameDefinitionSnapshot snapshot(String id, String text) {
        return new GameDefinitionSnapshot(id, Map.of(NAME, text.getBytes(StandardCharsets.UTF_8)));
    }
    private static String read(GameDefinitionSnapshot snapshot) throws Exception {
        try (var stream = snapshot.openResource(NAME).orElseThrow()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    @Test void inputAndOutputCannotMutateSnapshot() throws Exception {
        byte[] bytes = "old".getBytes(StandardCharsets.UTF_8);
        var snapshot = new GameDefinitionSnapshot("one", Map.of(NAME, bytes));
        bytes[0] = 0;
        assertEquals("old", read(snapshot));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.resourceNames().clear());
        try (var first = snapshot.openResource(NAME).orElseThrow()) { first.read(); }
        assertEquals("old", read(snapshot));
    }
    @Test void heldSnapshotStaysOnOneGeneration() throws Exception {
        var service = new GameDefinitionService(e -> fail(e));
        service.publish(snapshot("one", "old"));
        var held = service.snapshot();
        service.publish(snapshot("two", "new"));
        assertEquals("old", read(held));
        assertEquals("new", read(service.snapshot()));
    }
    @Test void eventsAreOrderedAndAlreadyPublished() {
        var service = new GameDefinitionService(e -> fail(e));
        List<Long> sequence = new ArrayList<>();
        service.subscribe(event -> { assertSame(event.current(), service.snapshot()); sequence.add(event.sequence()); });
        service.publish(snapshot("one", "a")); service.publish(snapshot("two", "b"));
        assertEquals(List.of(1L, 2L), sequence);
    }
    @Test void failedSubscriberAndErrorSinkDoNotSuppressHealthySubscriber() {
        var service = new GameDefinitionService(e -> { throw new IllegalStateException("error sink"); });
        AtomicInteger delivered = new AtomicInteger();
        service.subscribe(e -> { throw new IllegalStateException("subscriber"); });
        service.subscribe(e -> delivered.incrementAndGet());
        service.publish(snapshot("one", "a"));
        assertEquals(1, delivered.get());
    }
    @Test void unsubscribeIsIdempotentAndDuplicateSubscriptionsIndependent() throws Exception {
        var service = new GameDefinitionService(e -> fail(e));
        AtomicInteger delivered = new AtomicInteger();
        Consumer<GameDataView.Change> callback = e -> delivered.incrementAndGet();
        var first = service.subscribe(callback);
        service.subscribe(callback);
        first.close(); first.close();
        service.publish(snapshot("one", "a"));
        assertEquals(1, delivered.get());
    }
    @Test void identicalGenerationIsNoOpButChangedGenerationIdIsRejected() {
        var service = new GameDefinitionService(e -> fail(e));
        var first = service.publish(snapshot("one", "a"));
        assertSame(first, service.publish(snapshot("one", "a")));
        assertThrows(IllegalArgumentException.class, () -> service.publish(snapshot("one", "b")));
        assertSame(first, service.snapshot());
    }
    @Test void reentrantPublicationIsRejectedWithoutChangingCommittedSnapshot() {
        AtomicInteger errors = new AtomicInteger();
        var service = new GameDefinitionService(e -> errors.incrementAndGet());
        service.subscribe(e -> service.publish(snapshot("nested", "bad")));
        service.publish(snapshot("one", "a"));
        assertEquals("one", service.snapshot().generation());
        assertEquals(1, errors.get());
    }
    @Test void userFilesAndTraversalAreNeverPartOfContract() {
        for (String name : List.of("config/private.json", "logbook/customquest/1.json", "logbook/quest/../x.json", "logbook//quest/1.json")) {
            assertThrows(IllegalArgumentException.class, () -> new GameDefinitionSnapshot("one", Map.of(name, new byte[0])));
        }
    }
    @Test void resourceOrderDoesNotAffectFingerprint() {
        assertEquals(snapshot("one", "a").fingerprint(), snapshot("two", "a").fingerprint());
    }
}
