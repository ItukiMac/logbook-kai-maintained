package logbook.internal.proxy;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class CaptureCapacityTest {
    @Test
    void aggregateOverflowInvalidatesAllPreviousChunksAndCannotRestart() {
        var budget = new CaptureBudget(100, 100);
        try (var capture = new CapturedHttpRequestResponse(budget, 5, 10)) {
            capture.putOriginRequest(new byte[] { 1, 2, 3 });
            capture.putOriginResponse(new byte[] { 4, 5, 6 });
            assertFalse(capture.isComplete());
            assertFalse(capture.seal());
            assertEquals("exchange-limit", capture.rejectionReason());
            assertEquals(0, budget.usedBytes());
            capture.putOriginResponse(new byte[] { 7 });
            assertThrows(IllegalStateException.class, capture::getOriginRequest);
            assertThrows(IllegalStateException.class, capture::getOriginResponse);
        }
    }

    @Test
    void reservationsRemainUntilAllIndependentStreamsClose() throws Exception {
        var budget = new CaptureBudget(8, 10);
        var capture = new CapturedHttpRequestResponse(budget, 8, 10);
        capture.putOriginRequest(new byte[] { 1, 2, 3, 4 });
        var first = capture.getOriginRequest();
        var second = capture.getOriginRequest();
        capture.clear();
        capture.clear();
        assertEquals(4, budget.usedBytes());
        assertArrayEquals(new byte[] { 1, 2, 3, 4 }, first.readAllBytes());
        first.close();
        assertEquals(4, budget.usedBytes());
        assertArrayEquals(new byte[] { 1, 2, 3, 4 }, second.readAllBytes());
        second.close();
        second.close();
        assertEquals(0, budget.usedBytes());
        assertEquals(0, budget.usedChunks());
        assertThrows(IOException.class, second::read);
    }

    @Test
    void independentExchangesShareTheBudgetAndRecoverAfterCleanup() {
        var budget = new CaptureBudget(6, 10);
        try (var first = new CapturedHttpRequestResponse(budget, 10, 10);
                var second = new CapturedHttpRequestResponse(budget, 10, 10)) {
            first.putOriginRequest(new byte[4]);
            second.putOriginResponse(new byte[3]);
            assertTrue(first.isComplete());
            assertFalse(second.isComplete());
            assertEquals("shared-budget", second.rejectionReason());
            assertEquals(4, budget.usedBytes());
            first.clear();
            try (var third = new CapturedHttpRequestResponse(budget, 10, 10)) {
                third.putOriginRequest(new byte[6]);
                assertTrue(third.seal());
            }
            assertEquals(0, budget.usedBytes());
        }
    }

    @Test
    void segmentCountIsBoundedEvenForOneByteChunks() {
        var budget = new CaptureBudget(100, 2);
        try (var capture = new CapturedHttpRequestResponse(budget, 100, 10)) {
            capture.putOriginRequest(new byte[] { 1 });
            capture.putOriginRequest(new byte[] { 2 });
            capture.putOriginRequest(new byte[] { 3 });
            assertFalse(capture.isComplete());
            assertEquals(0, budget.usedChunks());
        }
    }

    @Test
    void perExchangeSegmentCountIsAlsoBounded() {
        var budget = new CaptureBudget(100, 100);
        try (var capture = new CapturedHttpRequestResponse(budget, 100, 1)) {
            capture.putOriginRequest(new byte[] { 1 });
            capture.putOriginResponse(new byte[] { 2 });
            assertFalse(capture.isComplete());
            assertEquals(0, budget.usedBytes());
        }
    }

    @Test
    void callerMutationAndBufferPositionCannotChangeCapturedBytes() throws Exception {
        var budget = new CaptureBudget(100, 100);
        try (var capture = new CapturedHttpRequestResponse(budget, 100, 10)) {
            byte[] source = { 9, 1, 2, 8 };
            ByteBuffer buffer = ByteBuffer.wrap(source).position(1).limit(3);
            capture.putOriginRequest(buffer);
            source[1] = 77;
            assertEquals(1, buffer.position());
            try (var stream = capture.getOriginRequest()) {
                assertArrayEquals(new byte[] { 1, 2 }, stream.readAllBytes());
            }
        }
        assertEquals(0, budget.usedBytes());
    }

    @Test
    void sealingPreventsLateCallbacksFromChangingPublishedPayload() throws Exception {
        var budget = new CaptureBudget(100, 100);
        try (var capture = new CapturedHttpRequestResponse(budget, 100, 10)) {
            capture.putOriginResponse(new byte[] { 1 });
            assertTrue(capture.seal());
            capture.putOriginResponse(new byte[] { 2 });
            try (var stream = capture.getOriginResponse()) {
                assertArrayEquals(new byte[] { 1 }, stream.readAllBytes());
            }
        }
    }

    @Test
    void fullRequestLimitAppliesAcrossChunksNotOnlyToEachChunk() {
        var budget = new CaptureBudget(32L * 1024 * 1024, 100);
        try (var capture = new CapturedHttpRequestResponse(budget, 32L * 1024 * 1024, 100)) {
            capture.putOriginRequest(new byte[7 * 1024 * 1024]);
            capture.putOriginRequest(new byte[6 * 1024 * 1024]);
            assertFalse(capture.isComplete());
            assertEquals(0, budget.usedBytes());
        }
    }

    @Test
    void emptyChunksDoNotExhaustSegmentBudget() {
        var budget = new CaptureBudget(1, 1);
        try (var capture = new CapturedHttpRequestResponse(budget, 1, 1)) {
            for (int n = 0; n < 10000; n++)
                capture.putOriginRequest(new byte[0]);
            capture.putOriginRequest(new byte[1]);
            assertTrue(capture.isComplete());
            assertEquals(1, budget.usedChunks());
        }
    }
}
