package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import logbook.plugin.api.GameDefinitionSnapshot;

@Timeout(20)
class GameDefinitionConcurrencyTest {
    private static GameDefinitionSnapshot snapshot(int number) {
        byte[] payload = Integer.toString(number).getBytes(StandardCharsets.UTF_8);
        return new GameDefinitionSnapshot("generation-" + number,
                Map.of("logbook/quest/1.json", payload, "logbook/map/mapping.json", payload));
    }

    private static String read(GameDefinitionSnapshot view, String name) throws Exception {
        try (var stream = view.openResource(name).orElseThrow()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void fourReadersNeverMixTwoResourcesFromDifferentGenerations() throws Exception {
        var service = new GameDefinitionService(error -> fail(error));
        service.publish(snapshot(0));
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(5);
        try {
            List<java.util.concurrent.Future<?>> workers = new ArrayList<>();
            for (int reader = 0; reader < 4; reader++) {
                workers.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    for (int check = 0; check < 2000; check++) {
                        var view = service.snapshot();
                        String first = read(view, "logbook/quest/1.json");
                        Thread.yield();
                        String second = read(view, "logbook/map/mapping.json");
                        assertEquals(first, second);
                        assertEquals("generation-" + first, view.generation());
                    }
                    return null;
                }));
            }
            workers.add(executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                for (int generation = 1; generation <= 500; generation++)
                    service.publish(snapshot(generation));
                return null;
            }));
            start.countDown();
            for (var worker : workers)
                worker.get(15, TimeUnit.SECONDS);
            assertEquals("generation-500", service.snapshot().generation());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentPublishersProduceUniqueOrderedChangeSequences() throws Exception {
        var service = new GameDefinitionService(error -> fail(error));
        List<Long> sequences = new ArrayList<>();
        service.subscribe(event -> {
            assertSame(event.current(), service.snapshot());
            sequences.add(event.sequence());
        });
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                for (int n = 0; n < 100; n++)
                    service.publish(snapshot(n));
                return null;
            });
            var second = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                for (int n = 100; n < 200; n++)
                    service.publish(snapshot(n));
                return null;
            });
            start.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
            assertEquals(200, sequences.size());
            for (int index = 0; index < sequences.size(); index++)
                assertEquals(index + 1L, sequences.get(index));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
