package logbook.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import logbook.internal.logger.LoggerHolder;
import logbook.plugin.api.GameDataView;
import logbook.plugin.api.GameDefinitionSnapshot;

/** Explicit host-side service; never installs, changes active.json, or auto-enables legacy cache reload. */
public final class GameDefinitionService implements GameDataView {
    record Publication(GameDefinitionSnapshot snapshot, Object prepared) {}
    private final AtomicReference<Publication> current = new AtomicReference<>(
            new Publication(new GameDefinitionSnapshot("bundled-unloaded", Map.of()), null));
    private final java.util.function.Function<GameDefinitionSnapshot, Object> prepare;
    private final CopyOnWriteArrayList<Subscription> listeners = new CopyOnWriteArrayList<>();
    /** Fingerprints, not old payloads, are retained for this service instance's lifetime. */
    private final Map<String, String> publishedFingerprints = new HashMap<>();
    private final Consumer<RuntimeException> errors;
    private long sequence;
    private boolean notifying;

    public GameDefinitionService() {
        this(error -> LoggerHolder.get().warn("ゲーム定義の更新通知で例外が発生", error));
    }
    public GameDefinitionService(Consumer<RuntimeException> errors) {
        this(errors, snapshot -> null);
    }
    GameDefinitionService(Consumer<RuntimeException> errors, java.util.function.Function<GameDefinitionSnapshot, Object> prepare) {
        this.errors = Objects.requireNonNull(errors);
        this.prepare = Objects.requireNonNull(prepare);
        publishedFingerprints.put(snapshot().generation(), snapshot().fingerprint());
    }
    @Override public GameDefinitionSnapshot snapshot() { return current.get().snapshot(); }
    Publication publication() { return current.get(); }
    @Override public AutoCloseable subscribe(Consumer<Change> listener) {
        Subscription subscription = new Subscription(Objects.requireNonNull(listener));
        listeners.add(subscription);
        return subscription;
    }
    public synchronized GameDefinitionSnapshot reload(Path root) throws IOException {
        if (notifying) throw new IllegalStateException("Reload from a change callback is not supported");
        return publish(GameDefinitionLoader.load(root));
    }
    synchronized GameDefinitionSnapshot publish(GameDefinitionSnapshot candidate) {
        Objects.requireNonNull(candidate);
        if (notifying) throw new IllegalStateException("Reentrant publication is not supported");
        String known = publishedFingerprints.get(candidate.generation());
        if (known != null && !known.equals(candidate.fingerprint())) {
            throw new IllegalArgumentException("An immutable generation changed contents");
        }
        GameDefinitionSnapshot old = snapshot();
        if (old.generation().equals(candidate.generation())) return old;
        Object prepared = prepare.apply(candidate); // Fail before changing sequence, identity history or any visible state.
        Change change = new Change(++sequence, old, candidate);
        publishedFingerprints.put(candidate.generation(), candidate.fingerprint());
        current.set(new Publication(candidate, prepared));
        notifying = true;
        try {
            for (Subscription listener : listeners) {
                if (!listener.active) continue;
                try { listener.callback.accept(change); }
                catch (RuntimeException error) {
                    try { errors.accept(error); }
                    catch (RuntimeException ignored) { /* A failed error sink cannot suppress later subscribers. */ }
                }
            }
        } finally { notifying = false; }
        return candidate;
    }
    private final class Subscription implements AutoCloseable {
        final Consumer<Change> callback;
        volatile boolean active = true;
        Subscription(Consumer<Change> callback) { this.callback = callback; }
        @Override public void close() { active = false; listeners.remove(this); }
    }
}
