package logbook.internal.proxy;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** Bounded notification work. Rejection NEVER runs plugin work on a Jetty forwarding thread. */
final class CaptureDispatchQueue implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final LongAdder rejected = new LongAdder();

    CaptureDispatchQueue() {
        this(4, 128);
    }

    CaptureDispatchQueue(int threads, int waiting) {
        AtomicInteger ids = new AtomicInteger();
        executor = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(waiting), work -> {
                    Thread thread = new Thread(work, "logbook-capture-" + ids.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
    }

    boolean submit(Runnable work, CapturedHttpRequestResponse capture) {
        Task task = new Task(Objects.requireNonNull(work), Objects.requireNonNull(capture));
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException full) {
            rejected.increment();
            task.cancel();
            return false;
        }
    }

    long rejectedCount() {
        return rejected.sum();
    }

    @Override
    public void close() {
        for (Runnable waiting : executor.shutdownNow())
            ((Task) waiting).cancel();
    }

    private static final class Task implements Runnable {
        private final Runnable work;
        private final CapturedHttpRequestResponse capture;
        private final AtomicBoolean claimed = new AtomicBoolean();

        Task(Runnable work, CapturedHttpRequestResponse capture) {
            this.work = work;
            this.capture = capture;
        }

        void cancel() {
            if (claimed.compareAndSet(false, true))
                capture.clear();
        }

        @Override
        public void run() {
            if (!claimed.compareAndSet(false, true))
                return;
            try {
                work.run();
            } finally {
                capture.clear();
            }
        }
    }
}
