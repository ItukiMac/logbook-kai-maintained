package logbook.core;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.*;
import logbook.bean.*;
import logbook.internal.logger.LoggerHolder;
import logbook.plugin.api.*;

/** Serializes host API mutation and publishes a deep immutable result after all synchronous handlers. */
public final class GameStateService implements GameStateView {
    private static final GameStateService SHARED = new GameStateService();

    public static GameStateService shared() {
        return SHARED;
    }

    private volatile GameStateSnapshot current = new GameStateSnapshot(0, Instant.EPOCH, "", "runtime-not-started",
            false, List.of(), List.of(), List.of(), Map.of());
    private final CopyOnWriteArrayList<Subscription> listeners = new CopyOnWriteArrayList<>();
    private volatile GameStateSnapshot latestQuestReceipt;
    private boolean updating;

    @Override
    public GameStateSnapshot snapshot() {
        return current;
    }

    @Override
    public GameStateSnapshot latestQuestReceipt() {
        return latestQuestReceipt;
    }

    @Override
    public AutoCloseable subscribe(Consumer<GameStateSnapshot> callback) {
        Subscription s = new Subscription(Objects.requireNonNull(callback));
        listeners.add(s);
        return s;
    }

    /** Use for legacy read adapters; never hand mutable beans to asynchronous work from the supplier. */
    public synchronized <T> T read(Supplier<T> query) {
        return Objects.requireNonNull(query).get();
    }

    public synchronized void applyApiUpdate(String uri, BooleanSupplier handlers) {
        Objects.requireNonNull(uri);
        Objects.requireNonNull(handlers);
        transact(uri, handlers::getAsBoolean, ignored -> true, Boolean::booleanValue);
    }

    /** Host-local commands use the same writer boundary, never a forged game receipt. */
    synchronized <T> T applyLocalUpdate(String operation, Supplier<T> command, Predicate<T> changed) {
        if (operation == null || !operation.matches("[a-z][a-z0-9-]{0,63}"))
            throw new IllegalArgumentException("A local operation identifier is required");
        return transact("host/local/" + operation, Objects.requireNonNull(command),
                Objects.requireNonNull(changed), ignored -> true);
    }

    private <T> T transact(String source, Supplier<T> handler, Predicate<T> changed, Predicate<T> succeeded) {
        if (updating)
            throw new IllegalStateException("Reentrant state mutation is not allowed");
        updating = true;
        try (var scope = LogBookCoreServices.pinDefinitions()) {
            T result;
            try {
                result = handler.get();
            } catch (RuntimeException | Error failure) {
                // A partially applied handler must not leave a successful old snapshot current.
                try {
                    publish(source, scope.snapshot().generation(), false);
                } catch (RuntimeException | Error captureFailure) {
                    failure.addSuppressed(captureFailure);
                }
                throw failure;
            }
            if (changed.test(result))
                publish(source, scope.snapshot().generation(), succeeded.test(result));
            return result;
        } finally {
            updating = false;
        }
    }

    private void publish(String source, String generation, boolean success) {
        GameStateSnapshot next = capture(Math.incrementExact(current.sequence()), source, generation, success);
        current = next;
        if (source.endsWith("/api_get_member/questlist"))
            latestQuestReceipt = next;
        for (Subscription s : listeners)
            if (s.active)
                try {
                    s.callback.accept(next);
                } catch (RuntimeException e) {
                    LoggerHolder.get().warn("ゲーム状態の通知先で例外", e);
                }
    }

    private static <T> List<T> copy(List<T> value) {
        return value == null ? List.of() : List.copyOf(value);
    }

    static GameStateSnapshot capture(long sequence, String api, String generation, boolean success) {
        List<GameStateSnapshot.ShipState> ships = new ArrayList<>();
        for (var ship : new ArrayList<>(ShipCollection.get().getShipMap().values()))
            if (ship != null)
                ships.add(new GameStateSnapshot.ShipState(
                        ship.getId(), ship.getShipId(), ship.getLv(), ship.getNowhp(), ship.getMaxhp(), ship.getCond(),
                        copy(ship.getSlot())));
        List<GameStateSnapshot.FleetState> fleets = new ArrayList<>();
        for (var fleet : new ArrayList<>(DeckPortCollection.get().getDeckPortMap().values()))
            if (fleet != null)
                fleets.add(new GameStateSnapshot.FleetState(fleet.getId(), copy(fleet.getShip()),
                        copy(fleet.getMission())));
        List<GameStateSnapshot.QuestState> quests = new ArrayList<>();
        for (var app : AppQuestCollection.get().getQuest().values())
            if (app != null && app.getQuest() != null) {
                var q = app.getQuest();
                quests.add(new GameStateSnapshot.QuestState(q.getNo(), q.getTitle(), q.getDetail(), q.getCategory(),
                        q.getType(), q.getState(), q.getProgressFlag(), copy(q.getGetMaterial()), q.getBonusFlag(),
                        q.getInvalidFlag(), app.getExpire()));
            }
        Map<Integer, Integer> materials = new LinkedHashMap<>();
        var data = AppCondition.get().getMaterial();
        if (data != null)
            data.forEach((id, m) -> {
                if (id != null && m != null && m.getValue() != null)
                    materials.put(id, m.getValue());
            });
        return new GameStateSnapshot(sequence, Instant.now(), api, generation, success, ships, fleets, quests,
                materials);
    }

    private final class Subscription implements AutoCloseable {
        final Consumer<GameStateSnapshot> callback;
        volatile boolean active = true;

        Subscription(Consumer<GameStateSnapshot> callback) {
            this.callback = callback;
        }

        public void close() {
            active = false;
            listeners.remove(this);
        }
    }
}
