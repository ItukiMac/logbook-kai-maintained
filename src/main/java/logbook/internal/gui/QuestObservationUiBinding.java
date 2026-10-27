package logbook.internal.gui;

import java.util.Objects;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import logbook.internal.logger.LoggerHolder;
import logbook.plugin.api.GameStateSnapshot;
import logbook.plugin.api.GameStateView;

/**
 * Visible-window subscription to quest-list receipts, including failed updates.
 * Construct and close on the FX thread; state notifications may use any thread.
 * A general state snapshot's timestamp is never promoted to quest freshness.
 */
final class QuestObservationUiBinding implements AutoCloseable {
    private final Window window;
    private final GameStateView state;
    private final Consumer<GameStateSnapshot> callback;
    private volatile Token current;
    private volatile boolean closed;
    private final EventHandler<WindowEvent> shown = event -> subscribe();
    private final EventHandler<WindowEvent> hidden = event -> unsubscribe();

    QuestObservationUiBinding(Window window, GameStateView state, Consumer<GameStateSnapshot> callback) {
        this.window = Objects.requireNonNull(window);
        this.state = Objects.requireNonNull(state);
        this.callback = Objects.requireNonNull(callback);
        window.addEventHandler(WindowEvent.WINDOW_SHOWN, shown);
        window.addEventHandler(WindowEvent.WINDOW_HIDDEN, hidden);
        if (window.isShowing()) subscribe();
    }

    private void subscribe() {
        if (closed || current != null || !window.isShowing()) return;
        Token token = new Token();
        current = token;
        try {
            token.subscription = Objects.requireNonNull(state.subscribe(value -> offer(token, value)));
            // Reopening may have missed a real quest receipt. Other API snapshots
            // cannot refresh the quest, even when they contain a cached quest list.
            offer(token, state.snapshot());
        } catch (RuntimeException failure) {
            unsubscribe();
            throw failure;
        }
    }

    private void offer(Token token, GameStateSnapshot value) {
        if (value == null || !value.sourceApi().endsWith("/api_get_member/questlist")) return;
        synchronized (token) {
            if (closed || current != token || !token.active || value.sequence() <= token.lastSequence) return;
            token.lastSequence = value.sequence();
            token.pending = value;
            if (token.queued) return;
            token.queued = true;
        }
        Platform.runLater(() -> deliver(token));
    }

    private void deliver(Token token) {
        GameStateSnapshot value;
        synchronized (token) {
            token.queued = false;
            value = token.pending;
            token.pending = null;
            if (!token.active) return;
        }
        if (closed || current != token || !window.isShowing() || value == null) return;
        try {
            callback.accept(value);
        } catch (RuntimeException failure) {
            LoggerHolder.get().warn("任務観測の画面通知で例外", failure);
        }
    }

    private void unsubscribe() {
        Token token = current;
        current = null;
        if (token == null) return;
        AutoCloseable subscription;
        synchronized (token) {
            token.active = false;
            token.pending = null;
            subscription = token.subscription;
            token.subscription = null;
        }
        if (subscription != null) try {
            subscription.close();
        } catch (Exception failure) {
            LoggerHolder.get().warn("任務観測の購読を解除できません", failure);
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        unsubscribe();
        window.removeEventHandler(WindowEvent.WINDOW_SHOWN, shown);
        window.removeEventHandler(WindowEvent.WINDOW_HIDDEN, hidden);
    }

    /** A separate token prevents queued work from a prior visibility interval being reused. */
    private static final class Token {
        boolean active = true;
        boolean queued;
        long lastSequence = -1;
        GameStateSnapshot pending;
        AutoCloseable subscription;
    }
}
