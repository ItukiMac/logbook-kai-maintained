package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.scene.web.WebView;
import javafx.concurrent.Worker;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Loads fixed JAR resources; no CDN or network script is used. */
@EnabledIfSystemProperty(named = "logbook.gui.tests", matches = "true")
@Timeout(25)
class OfflineTextEditorGuiTest {
    @BeforeAll
    static void start() throws Exception {
        FxTestSupport.start();
    }

    private void roundtrip(String language, String text, boolean readOnly) throws Exception {
        var pane = FxTestSupport.fx(TextEditorPane::new);
        var stage = FxTestSupport.fx(() -> {
            var s = new Stage();
            s.setScene(new Scene(pane, 320, 180));
            s.show();
            pane.start(language);
            pane.set(text);
            pane.setReadOnly(readOnly);
            return s;
        });
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            boolean ready = false;
            while (System.nanoTime() < deadline) {
                ready = FxTestSupport.fx(() -> {
                    var view = (WebView) pane.lookup("WebView");
                    return view != null && view.getEngine().getLoadWorker().getState() == Worker.State.SUCCEEDED;
                });
                if (ready)
                    break;
                Thread.sleep(25);
            }
            assertTrue(ready, "Local editor must finish loading");
            assertEquals(text, FxTestSupport.fx(pane::get));
            FxTestSupport.fx(() -> {
                var engine = ((WebView) pane.lookup("WebView")).getEngine();
                assertEquals("1.45.2", engine.executeScript("ace.version"));
                assertEquals("ace/mode/" + language, engine.executeScript("window.editor.session.getMode().$id"));
                assertEquals(readOnly, engine.executeScript("window.editor.getReadOnly()"));
                assertFalse(engine.getLocation().startsWith("http"));
                return null;
            });
        } finally {
            FxTestSupport.fx(() -> {
                stage.close();
                return null;
            });
        }
    }

    @Test
    void jsonRoundtripsJapaneseQuotesAndNewlines() throws Exception {
        roundtrip("json", "{\"艦娘\":\"'quote'\"}\n", false);
    }

    @Test
    void javascriptModeIsAvailableWithoutDynamicNetworkLoad() throws Exception {
        roundtrip("javascript", "const value = '艦娘';\n", false);
    }

    @Test
    void textAndReadOnlyStateSurviveDeferredInitialization() throws Exception {
        roundtrip("text", "first\n艦娘 \\\" second", true);
    }
}
