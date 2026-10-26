package logbook.internal.proxy;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class CaptureDispatchQueueTest {
    private static CapturedHttpRequestResponse one(CaptureBudget budget) {
        var capture = new CapturedHttpRequestResponse(budget, 10, 10);
        capture.putOriginResponse(new byte[1]); capture.seal(); return capture;
    }
    @Test void slowListenerCannotCreateUnboundedQueueOrRunOnCaller() throws Exception {
        var budget = new CaptureBudget(10, 10);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var done = new CountDownLatch(1);
        AtomicBoolean rejectedRan = new AtomicBoolean();
        try (var queue = new CaptureDispatchQueue(1, 1)) {
            assertTrue(queue.submit(() -> {
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            }, one(budget)));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTrue(queue.submit(done::countDown, one(budget)));
            assertFalse(queue.submit(() -> rejectedRan.set(true), one(budget)));
            assertEquals(1, queue.rejectedCount()); assertFalse(rejectedRan.get());
            assertEquals(2, budget.usedBytes());
            release.countDown(); assertTrue(done.await(3, TimeUnit.SECONDS));
        }
        awaitEmpty(budget);
    }
    @Test void stoppingCancelsQueuedCapturesAndRejectsNewWorkWithoutLeaks() throws Exception {
        var budget = new CaptureBudget(10, 10);
        var entered = new CountDownLatch(1);
        var queue = new CaptureDispatchQueue(1, 1);
        queue.submit(() -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        }, one(budget));
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        queue.submit(() -> fail("Cancelled work must not run"), one(budget));
        queue.close(); queue.close();
        assertFalse(queue.submit(() -> fail("Closed queue must not run"), one(budget)));
        awaitEmpty(budget);
    }
    private static void awaitEmpty(CaptureBudget budget) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (budget.usedBytes() != 0 && System.nanoTime() < end) Thread.sleep(5);
        assertEquals(0, budget.usedBytes()); assertEquals(0, budget.usedChunks());
    }
}
