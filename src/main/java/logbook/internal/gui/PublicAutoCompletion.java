package logbook.internal.gui;

import java.util.List;
import java.util.Objects;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.InputMethodEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.stage.Popup;

/** Text completion using public JavaFX APIs only; all operations are on the FX thread. */
public final class PublicAutoCompletion implements AutoCloseable {
    private static final Object KEY = new Object();
    private final TextField field;
    private final SuggestSupport provider;
    private final Popup popup = new Popup();
    private final ListView<String> list = new ListView<>();
    private final ChangeListener<String> textListener = (o, a, b) -> schedule();
    private final ChangeListener<Boolean> focusListener = (o, a, focused) -> {
        if (!focused) popup.hide();
    };
    private final ChangeListener<Scene> sceneListener = (o, a, b) -> popup.hide();
    private final EventHandler<KeyEvent> keyHandler = this::keyPressed;
    private final EventHandler<InputMethodEvent> imeHandler = event -> {
        composing = !event.getComposed().isEmpty();
        popup.hide();
        schedule();
    };
    private boolean composing;
    private boolean choosing;
    private boolean scheduled;
    private boolean closed;

    public static PublicAutoCompletion bindAutoCompletion(TextField field, SuggestSupport provider) {
        requireFxThread();
        Objects.requireNonNull(field);
        Objects.requireNonNull(provider);
        Object previous = field.getProperties().get(KEY);
        if (previous instanceof PublicAutoCompletion binding) binding.close();
        PublicAutoCompletion binding = new PublicAutoCompletion(field, provider);
        field.getProperties().put(KEY, binding);
        return binding;
    }

    private PublicAutoCompletion(TextField field, SuggestSupport provider) {
        this.field = field;
        this.provider = provider;
        list.setFixedCellSize(26);
        list.setFocusTraversable(false);
        list.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY) choose();
        });
        popup.getContent().add(list);
        popup.setAutoHide(true);
        popup.setAutoFix(true);
        popup.setHideOnEscape(true);
        field.textProperty().addListener(textListener);
        field.focusedProperty().addListener(focusListener);
        field.sceneProperty().addListener(sceneListener);
        field.addEventFilter(KeyEvent.KEY_PRESSED, keyHandler);
        field.addEventFilter(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, imeHandler);
    }

    private void schedule() {
        if (closed || choosing || scheduled) return;
        scheduled = true;
        // Coalesce text/IME events. Never display intermediate composition text.
        Platform.runLater(() -> {
            scheduled = false;
            if (!closed) refresh();
        });
    }

    private void refresh() {
        if (composing || !field.isFocused() || field.getScene() == null
                || field.getScene().getWindow() == null
                || !field.getScene().getWindow().isShowing()) {
            popup.hide();
            return;
        }
        List<String> values = provider.suggest(field.getText()).stream()
                .filter(Objects::nonNull).distinct().limit(50).toList();
        list.getItems().setAll(values);
        if (values.isEmpty()) {
            popup.hide();
            return;
        }
        Bounds screen = field.localToScreen(field.getBoundsInLocal());
        if (screen == null) return;
        list.setPrefSize(Math.max(180, screen.getWidth()), Math.min(8, values.size()) * 26 + 4);
        list.getSelectionModel().selectFirst();
        if (popup.isShowing()) {
            popup.setX(screen.getMinX());
            popup.setY(screen.getMaxY());
        } else {
            popup.show(field, screen.getMinX(), screen.getMaxY());
        }
    }

    private void keyPressed(KeyEvent event) {
        if (closed || composing || !popup.isShowing()) return;
        KeyCode code = event.getCode();
        if (code == KeyCode.DOWN || code == KeyCode.UP) {
            int selected = list.getSelectionModel().getSelectedIndex();
            int next = Math.max(0, Math.min(list.getItems().size() - 1,
                    selected + (code == KeyCode.DOWN ? 1 : -1)));
            list.getSelectionModel().select(next);
            list.scrollTo(next);
            event.consume();
        } else if (code == KeyCode.ENTER) {
            choose();
            event.consume();
        } else if (code == KeyCode.ESCAPE) {
            popup.hide();
            event.consume();
        } else if (code == KeyCode.TAB) {
            popup.hide(); // Preserve normal traversal rather than consume Tab.
        }
    }

    private void choose() {
        String selected = list.getSelectionModel().getSelectedItem();
        if (closed || composing || selected == null) return;
        choosing = true;
        try {
            popup.hide();
            field.setText(selected);
            field.positionCaret(selected.length());
        } finally {
            choosing = false;
        }
    }

    List<String> suggestions() { return List.copyOf(list.getItems()); }
    boolean isShowing() { return popup.isShowing(); }

    @Override public void close() {
        requireFxThread();
        if (closed) return;
        closed = true;
        popup.hide();
        field.textProperty().removeListener(textListener);
        field.focusedProperty().removeListener(focusListener);
        field.sceneProperty().removeListener(sceneListener);
        field.removeEventFilter(KeyEvent.KEY_PRESSED, keyHandler);
        field.removeEventFilter(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, imeHandler);
        if (field.getProperties().get(KEY) == this) field.getProperties().remove(KEY);
        list.getItems().clear();
    }

    private static void requireFxThread() {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("JavaFX thread required");
    }
}
