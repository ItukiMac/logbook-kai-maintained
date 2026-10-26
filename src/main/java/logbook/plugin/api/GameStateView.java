package logbook.plugin.api;

import java.util.function.Consumer;

/** Version 1. Future-only callbacks run synchronously after one complete API-handler group. */
public interface GameStateView {
    int API_VERSION=1;
    GameStateSnapshot snapshot();
    AutoCloseable subscribe(Consumer<GameStateSnapshot> listener);
}
