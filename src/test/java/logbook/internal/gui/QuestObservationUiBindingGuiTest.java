package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import logbook.plugin.api.GameStateSnapshot;
import logbook.plugin.api.GameStateView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Real FX window lifecycle with a fake state view; no configuration or game data. */
@EnabledIfSystemProperty(named = "logbook.gui.tests", matches = "true")
@Timeout(20)
class QuestObservationUiBindingGuiTest {
    private static final String QUEST_API = "/kcsapi/api_get_member/questlist";

    @BeforeAll static void start() throws Exception { FxTestSupport.start(); }

    private static GameStateSnapshot snapshot(long sequence, String api, boolean success) {
        return new GameStateSnapshot(sequence, Instant.ofEpochSecond(sequence), api, "synthetic", success,
                List.of(), List.of(), List.of(), Map.of());
    }

    private static final class FakeStateView implements GameStateView {
        private volatile GameStateSnapshot current = QuestObservationUiBindingGuiTest.snapshot(
                0, "/kcsapi/api_start2/getData", true);
        private final CopyOnWriteArrayList<Consumer<GameStateSnapshot>> listeners = new CopyOnWriteArrayList<>();
        @Override public GameStateSnapshot snapshot() { return current; }
        @Override public AutoCloseable subscribe(Consumer<GameStateSnapshot> listener) {
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }
        void emit(GameStateSnapshot value) {
            current = value;
            for (var listener : listeners) listener.accept(value);
        }
        int listenerCount() { return listeners.size(); }
    }

    private static final class Fixture implements AutoCloseable {
        final FakeStateView view = new FakeStateView();
        final CopyOnWriteArrayList<GameStateSnapshot> delivered = new CopyOnWriteArrayList<>();
        final AtomicBoolean allOnFxThread = new AtomicBoolean(true);
        final Stage stage;
        final AutoCloseable binding;
        Fixture() throws Exception {
            stage = FxTestSupport.fx(() -> {
                Stage result = new Stage();
                result.setScene(new Scene(new StackPane(), 240, 80));
                return result;
            });
            try {
                binding = FxTestSupport.fx(() -> {
                    // Also compiles before the new binding exists, preserving the failing regression.
                    Class<?> type = Class.forName("logbook.internal.gui.QuestObservationUiBinding");
                    Consumer<GameStateSnapshot> callback = value -> {
                        if (!Platform.isFxApplicationThread()) allOnFxThread.set(false);
                        delivered.add(value);
                    };
                    return (AutoCloseable) type.getDeclaredConstructor(Window.class, GameStateView.class, Consumer.class)
                            .newInstance(stage, view, callback);
                });
            } catch (Exception failure) {
                FxTestSupport.fx(() -> { stage.close(); return null; });
                throw failure;
            }
        }
        void show() throws Exception { FxTestSupport.fx(() -> { stage.show(); return null; }); flush(); }
        void flush() throws Exception { FxTestSupport.fx(() -> null); }
        @Override public void close() throws Exception {
            FxTestSupport.fx(() -> { binding.close(); stage.close(); return null; });
            flush();
        }
    }

    @Test void subscribesOnlyWhileVisibleAndDeliversQuestUpdatesOnFxThread() throws Exception {
        try (var f = new Fixture()) {
            assertEquals(0, f.view.listenerCount());
            f.show();
            assertEquals(1, f.view.listenerCount());
            assertTrue(f.delivered.isEmpty());
            var actual = snapshot(1, QUEST_API, true);
            f.view.emit(actual); // JUnit thread, not the FX thread.
            f.flush();
            assertEquals(List.of(actual), f.delivered);
            assertTrue(f.allOnFxThread.get());
            FxTestSupport.fx(() -> { f.stage.hide(); return null; });
            assertEquals(0, f.view.listenerCount());
        }
    }

    @Test void otherApiSnapshotsNeverManufactureAQuestObservation() throws Exception {
        try (var f = new Fixture()) {
            f.show();
            f.view.emit(snapshot(1, "/kcsapi/api_port/port", true));
            f.view.emit(snapshot(2, "/kcsapi/api_req_map/next", false));
            f.flush();
            assertTrue(f.delivered.isEmpty());
            assertEquals(2, f.view.snapshot().sequence(), "Other API traffic still updates the fake state view");
        }
    }

    @Test void failedQuestUpdateIsDeliveredForControllerInvalidation() throws Exception {
        try (var f = new Fixture()) {
            f.show();
            var failed = snapshot(1, QUEST_API, false);
            f.view.emit(failed);
            f.flush();
            assertEquals(List.of(failed), f.delivered);
            assertFalse(f.delivered.getFirst().updateSucceeded());
        }
    }

    @Test void queuedQuestUpdatesCoalesceToNewestSequenceIncludingFailure() throws Exception {
        try (var f = new Fixture()) {
            f.show();
            var newest = snapshot(3, QUEST_API, false);
            FxTestSupport.fx(() -> {
                f.view.emit(snapshot(1, QUEST_API, true));
                f.view.emit(newest);
                f.view.emit(snapshot(2, QUEST_API, true)); // A late older notification cannot replace the failure.
                f.view.emit(snapshot(4, "/kcsapi/api_port/port", true));
                return null;
            });
            f.flush();
            assertEquals(List.of(newest), f.delivered);
        }
    }

    @Test void hideInvalidatesQueuedCallbackAndReshowUsesTheLatestQuestSnapshot() throws Exception {
        try (var f = new Fixture()) {
            f.show();
            var reopened = snapshot(2, QUEST_API, true);
            FxTestSupport.fx(() -> {
                f.view.emit(snapshot(1, QUEST_API, true));
                f.stage.hide();
                assertEquals(0, f.view.listenerCount());
                f.view.emit(reopened);
                f.stage.show();
                return null;
            });
            f.flush();
            assertEquals(List.of(reopened), f.delivered, "The old queued callback must not revive an earlier observation");
            assertEquals(1, f.view.listenerCount());
        }
    }

    @Test void reshowDoesNotReclassifyLatestNonQuestStateAsFreshQuestData() throws Exception {
        try (var f = new Fixture()) {
            f.show();
            FxTestSupport.fx(() -> { f.stage.hide(); return null; });
            f.view.emit(snapshot(1, QUEST_API, true));
            f.view.emit(snapshot(2, "/kcsapi/api_port/port", true));
            f.show();
            assertTrue(f.delivered.isEmpty());
            var observed = snapshot(3, QUEST_API, true);
            f.view.emit(observed);
            f.flush();
            assertEquals(List.of(observed), f.delivered);
        }
    }

    @Test void closeIsIdempotentAndQueuedWorkCannotResubscribeOrDeliver() throws Exception {
        try (var f = new Fixture()) {
            f.show();
            FxTestSupport.fx(() -> {
                f.view.emit(snapshot(1, QUEST_API, true));
                f.binding.close();
                f.binding.close();
                f.stage.hide();
                f.stage.show();
                return null;
            });
            f.flush();
            assertEquals(0, f.view.listenerCount());
            assertTrue(f.delivered.isEmpty());
        }
    }
}
