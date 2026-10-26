package logbook.internal.gui;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;

final class FxTestSupport {
    private FxTestSupport() { }
    static void start() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        Runnable initialized = () -> { Platform.setImplicitExit(false); ready.countDown(); };
        try { Platform.startup(initialized); }
        catch (IllegalStateException alreadyStarted) { Platform.runLater(initialized); }
        if (!ready.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("FX startup timed out");
    }
    static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }
}
