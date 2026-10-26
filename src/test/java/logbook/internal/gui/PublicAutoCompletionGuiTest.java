package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.input.InputMethodEvent;
import javafx.scene.input.InputMethodHighlight;
import javafx.scene.input.InputMethodTextRun;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "logbook.gui.tests", matches = "true")
@Timeout(20)
class PublicAutoCompletionGuiTest {
    @BeforeAll static void startFx() throws Exception {
        FxTestSupport.start();
    }
    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }
    private static final class Fixture implements AutoCloseable {
        final TextField field = new TextField();
        final Stage stage = new Stage();
        final PublicAutoCompletion binding;
        Fixture() {
            binding = PublicAutoCompletion.bindAutoCompletion(field,
                    new SuggestSupport("alpha", "alpine", "beta"));
            stage.setScene(new Scene(new StackPane(field), 300, 100));
            stage.show();
            stage.requestFocus();
            field.requestFocus();
        }
        public void close() { binding.close(); stage.close(); }
    }
    private static Fixture fixture() throws Exception {
        Fixture f = fx(Fixture::new);
        fx(() -> { f.stage.requestFocus(); f.field.requestFocus(); return null; });
        return f;
    }
    @Test void prefixAndKeyboardSelection() throws Exception {
        Fixture f = fixture();
        try {
            fx(() -> { f.field.setText("al"); return null; });
            fx(() -> {
                assertEquals(List.of("alpha", "alpine"), f.binding.suggestions());
                assertTrue(f.binding.isShowing());
                f.field.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DOWN, false, false, false, false));
                f.field.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
                assertEquals("alpine", f.field.getText());
                assertFalse(f.binding.isShowing());
                return null;
            });
        } finally { fx(() -> { f.close(); return null; }); }
    }
    @Test void rebindDisposesOldPopupAndCloseIsIdempotent() throws Exception {
        Fixture f = fixture();
        try {
            fx(() -> { f.field.setText("al"); return null; });
            fx(() -> {
                assertTrue(f.binding.isShowing());
                var replacement = PublicAutoCompletion.bindAutoCompletion(f.field, new SuggestSupport("other"));
                assertFalse(f.binding.isShowing());
                assertTrue(f.binding.suggestions().isEmpty());
                replacement.close(); replacement.close();
                return null;
            });
        } finally { fx(() -> { f.close(); return null; }); }
    }
    @Test void compositionHidesPopupAndDoesNotReplaceInput() throws Exception {
        Fixture f = fixture();
        try {
            fx(() -> { f.field.setText("al"); return null; });
            fx(() -> {
                f.field.fireEvent(new InputMethodEvent(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                    List.of(new InputMethodTextRun("あ", InputMethodHighlight.UNSELECTED_RAW)), "", 0));
                assertFalse(f.binding.isShowing());
                return null;
            });
            fx(() -> { assertFalse(f.binding.isShowing()); return null; });
        } finally { fx(() -> { f.close(); return null; }); }
    }
    @Test void noMatchHidesPopup() throws Exception {
        Fixture f = fixture();
        try {
            fx(() -> { f.field.setText("al"); return null; });
            fx(() -> { assertTrue(f.binding.isShowing()); f.field.setText("zzz"); return null; });
            fx(() -> { assertFalse(f.binding.isShowing()); assertTrue(f.binding.suggestions().isEmpty()); return null; });
        } finally { fx(() -> { f.close(); return null; }); }
    }
}
