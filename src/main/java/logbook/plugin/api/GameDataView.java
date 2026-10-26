package logbook.plugin.api;

import java.util.Objects;
import java.util.function.Consumer;

/** Additive version-1 plugin contract. Acquire ONE snapshot for a whole operation. */
public interface GameDataView {
    int API_VERSION = 1;
    GameDefinitionSnapshot snapshot();
    /** Future changes only. Synchronous ordered callbacks on the publisher thread; marshal GUI work yourself. */
    AutoCloseable subscribe(Consumer<Change> listener);
    record Change(long sequence, GameDefinitionSnapshot previous, GameDefinitionSnapshot current) {
        public Change {
            if (sequence <= 0) throw new IllegalArgumentException("Positive sequence required");
            Objects.requireNonNull(previous);
            Objects.requireNonNull(current);
        }
    }
}
