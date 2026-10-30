package logbook.plugin.api;

import java.util.function.Consumer;

/** Version 1. Future-only callbacks run synchronously after one complete API-handler group. */
public interface GameStateView {
    int API_VERSION=1;
    GameStateSnapshot snapshot();
    /** Last real quest-list receipt, including failure; unrelated/local updates cannot refresh it.
     * Legacy providers retain their prior snapshot-only behavior through this default. */
    default GameStateSnapshot latestQuestReceipt() {
        var value = snapshot();
        return value != null && value.sourceApi().endsWith("/api_get_member/questlist") ? value : null;
    }
    AutoCloseable subscribe(Consumer<GameStateSnapshot> listener);
}
