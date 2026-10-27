package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import tools.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.stage.Stage;
import logbook.bean.*;
import logbook.core.SeasonalQuestRuleService;
import logbook.internal.kancolle.QuestCollect;
import logbook.plugin.api.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Actual progress FXML, worker, success callback, expiry timer and observation subscription. */
@EnabledIfSystemProperty(named="logbook.gui.tests", matches="true")
@Timeout(30)
class SeasonalQuestProgressGuiTest {
    static final ObjectMapper JSON = logbook.internal.json.JsonMappers.builder().build();
    static final byte[] RULE = "{\"type\":\"出撃\",\"resetType\":\"ウィークリー\",\"conditions\":[{\"rank\":[\"S\"],\"count\":1}]}".getBytes(StandardCharsets.UTF_8);
    @BeforeAll static void startFx() throws Exception {
        var ready = new CountDownLatch(1);
        try { Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); }); }
        catch (IllegalStateException running) { Platform.runLater(ready::countDown); }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
    }
    static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); Platform.runLater(task); return task.get(10, TimeUnit.SECONDS);
    }
    static void await(Callable<Boolean> condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) {
            if (fx(condition)) return;
            Thread.sleep(20);
        }
        assertTrue(fx(condition), "Expected GUI state was not rendered");
    }
    static final class View implements GameStateView {
        final List<Consumer<GameStateSnapshot>> listeners = new CopyOnWriteArrayList<>();
        GameStateSnapshot current = snapshot(0, "", true);
        static GameStateSnapshot snapshot(long sequence, String api, boolean success) {
            return new GameStateSnapshot(sequence, Instant.now(), api, "fixture", success, List.of(), List.of(), List.of(), Map.of());
        }
        @Override public GameStateSnapshot snapshot() { return current; }
        @Override public AutoCloseable subscribe(Consumer<GameStateSnapshot> listener) {
            listeners.add(listener); return () -> listeners.remove(listener);
        }
        void emit(boolean success) {
            current = snapshot(current.sequence() + 1, "/kcsapi/api_get_member/questlist", success);
            listeners.forEach(listener -> listener.accept(current));
        }
    }
    static final class Fixture {
        final View view = new View();
        final AtomicInteger collections = new AtomicInteger();
        final AtomicReference<AppQuest> latest = new AtomicReference<>();
        final SeasonalQuestRuleService rules;
        Fixture(long lifetimeMillis) throws Exception {
            Instant now = Instant.now();
            var row = JSON.createObjectNode().put("editionId", "gui-fixture").put("questId", 840)
                .put("editionYear", now.atZone(ZoneId.of("Asia/Tokyo")).getYear()).put("origin", "eventquest")
                .put("title", "fixture title").put("detail", "fixture detail").put("category", 2).put("type", 2)
                .put("validFrom", now.minusSeconds(60).toString()).put("validUntil", now.plusMillis(lifetimeMillis).toString())
                .put("baseDefinitionSha256", SeasonalQuestRuleService.sha256(RULE))
                .put("definitionResource", "logbook/seasonal/editions/gui-fixture.json")
                .put("definitionSha256", SeasonalQuestRuleService.sha256(RULE))
                .put("identityVerified", true).put("windowVerified", true).put("modeledConditionsVerified", true);
            row.putArray("selectionBlockers");
            for (String url : List.of("https://wikiwiki.jp/kancolle/fixture", "https://zekamashi.net/fixture"))
                row.withArray("sources").addObject().put("url", url).put("accessedOn", "2026-10-27").put("manuallyReviewed", true);
            var catalog = JSON.createObjectNode().put("format", SeasonalQuestRuleService.FORMAT);
            catalog.putArray("managedQuestIds").add(840); catalog.putArray("editions").add(row);
            rules = new SeasonalQuestRuleService(JSON.writeValueAsBytes(catalog), path -> RULE, Clock.systemUTC());
            latest.set(quest("fixture detail", now));
        }
        static AppQuest quest(String detail, Instant observedAt) {
            var q = new QuestList.Quest(); q.setNo(840); q.setTitle("fixture title"); q.setDetail(detail);
            q.setCategory(2); q.setType(2); q.setState(2); q.setObservedAt(observedAt);
            var app = new AppQuest(); app.setNo(840); app.setQuest(q); app.setActive(true); app.setExpire("synthetic");
            return app;
        }
        AppQuestCondition load(AppQuest quest) {
            try {
                var resolution = rules.resolve(840, "eventquest", RULE, SeasonalQuestRuleService.Observation.from(quest.getQuest()));
                var condition = JSON.readValue(RULE, AppQuestCondition.class);
                condition.setSeasonalSelection(resolution.selection()); return condition;
            } catch (Exception error) { throw new IllegalStateException(error); }
        }
    }
    static final class IsolatedController extends QuestProgress {
        final Fixture fixture;
        IsolatedController(Fixture fixture) { this.fixture = fixture; }
        @Override GameStateView gameStateView() { return fixture.view; }
        @Override AppQuest latestObservedQuest(int id) { return fixture.latest.get(); }
        @Override AppQuestCondition loadCondition(AppQuest quest) { return fixture.load(quest); }
        @Override QuestCollect collectCondition(AppQuest quest, AppQuestCondition condition) {
            fixture.collections.incrementAndGet();
            var collection = new QuestCollect(); collection.getTotal().getAll().setS(1); return collection;
        }
    }
    record Window(Stage stage, Parent pane, IsolatedController controller) {
        @SuppressWarnings("unchecked") TreeView<String> tree() { return (TreeView<String>) pane.lookup("#condition"); }
        String text() { return tree().getRoot() == null ? "" : tree().getRoot().getValue(); }
        String info() { return ((Label) pane.lookup("#info")).getText(); }
        String title() { return ((Label) pane.lookup("#name")).getText(); }
        boolean selected() { return !tree().isShowRoot() && tree().getRoot().getChildren().stream().anyMatch(row -> "達成条件".equals(row.getValue())); }
        void close() { stage.close(); }
    }
    static Window open(Fixture fixture) throws Exception {
        return fx(() -> {
            var loader = new FXMLLoader(SeasonalQuestProgressGuiTest.class.getResource("/logbook/gui/quest_progress.fxml"));
            var controller = new IsolatedController(fixture); loader.setControllerFactory(type -> controller);
            Parent pane = loader.load(); var stage = new Stage(); stage.setScene(new Scene(pane));
            controller.initWindow(stage); stage.show(); controller.setQuest(fixture.latest.get());
            return new Window(stage, pane, controller);
        });
    }
    @Test void unconfirmedReasonIsActuallyVisibleAndDoesNotReadProgressStores() throws Exception {
        var fixture = new Fixture(30_000); fixture.latest.get().getQuest().setObservedAt(null);
        var window = open(fixture);
        try {
            await(() -> window.text().contains("未確定"));
            assertTrue(fx(() -> window.tree().isShowRoot())); assertEquals(0, fixture.collections.get());
        } finally { fx(() -> { window.close(); return null; }); }
    }
    @Test void expiryRemovesSuccessWhileTheWindowStaysOpen() throws Exception {
        var fixture = new Fixture(8_000); var window = open(fixture);
        try {
            await(window::selected); assertEquals(1, fixture.collections.get());
            await(() -> window.text().contains("有効期間外"));
            assertTrue(fx(() -> window.tree().isShowRoot()));
            assertFalse(fx(() -> window.info().contains("季節版確認済み")));
            assertEquals(1, fixture.collections.get());
        } finally { fx(() -> { window.close(); return null; }); }
    }
    @Test void freshDifferentQuestMetadataInvalidatesTheOpenWindowImmediately() throws Exception {
        var fixture = new Fixture(30_000); var window = open(fixture);
        try {
            await(window::selected);
            fixture.latest.set(Fixture.quest("a changed roster", Instant.now())); fixture.view.emit(true);
            await(() -> window.text().contains("未確定") && window.info().contains("a changed roster"));
            assertEquals(1, fixture.collections.get()); assertFalse(fx(window::selected));
        } finally { fx(() -> { window.close(); return null; }); }
        assertEquals(0, fixture.view.listeners.size());
    }
    @Test void failedQuestListRefreshCannotLeaveTheOldSuccessVisible() throws Exception {
        var fixture = new Fixture(30_000); var window = open(fixture);
        try {
            await(window::selected); fixture.view.emit(false);
            await(() -> window.text().contains("任務一覧の更新を確認できません"));
            assertEquals(1, fixture.collections.get());
        } finally { fx(() -> { window.close(); return null; }); }
    }
    @Test void disappearedQuestDoesNotKeepAnOldEditionOrReadStoresAgain() throws Exception {
        var fixture = new Fixture(30_000); var window = open(fixture);
        try {
            await(window::selected); fixture.latest.set(null); fixture.view.emit(true);
            await(() -> window.text().contains("この任務を確認できません"));
            assertEquals(1, fixture.collections.get());
        } finally { fx(() -> { window.close(); return null; }); }
    }
    @Test void failedObservationRemainsUnconfirmedAcrossDefinitionReloadUntilNewObservation() throws Exception {
        var fixture = new Fixture(30_000); var window = open(fixture);
        try {
            await(window::selected); fixture.view.emit(false);
            String reason = "未確定：任務一覧の更新を確認できません";
            await(() -> reason.equals(window.text()));
            fx(() -> {
                window.controller().onDefinitionsChanged();
                assertEquals(reason, window.text(), "A definition reload must preserve the failed observation");
                assertTrue(window.tree().isShowRoot());
                return null;
            });
            assertEquals(1, fixture.collections.get());
            fixture.latest.set(Fixture.quest("fixture detail", Instant.now())); fixture.view.emit(true);
            await(window::selected);
            assertEquals(2, fixture.collections.get());
            assertFalse(fx(() -> window.info().contains(reason)));
        } finally { fx(() -> { window.close(); return null; }); }
    }
    @Test void disappearedQuestRemainsUnconfirmedAcrossDefinitionReloadUntilNewObservation() throws Exception {
        var fixture = new Fixture(30_000); var window = open(fixture);
        try {
            await(window::selected); fixture.latest.set(null); fixture.view.emit(true);
            String reason = "未確定：更新された任務一覧でこの任務を確認できません";
            await(() -> reason.equals(window.text()));
            fx(() -> {
                window.controller().onDefinitionsChanged();
                assertEquals(reason, window.text(), "A definition reload must not revive a disappeared quest");
                assertTrue(window.tree().isShowRoot());
                return null;
            });
            assertEquals(1, fixture.collections.get());
            fixture.latest.set(Fixture.quest("fixture detail", Instant.now())); fixture.view.emit(true);
            await(window::selected);
            assertEquals(2, fixture.collections.get());
            assertFalse(fx(() -> window.info().contains(reason)));
        } finally { fx(() -> { window.close(); return null; }); }
    }
    @Test void missingDetailInNewObservationReplacesOldSuccessWithUnconfirmed() throws Exception {
        var fixture = new Fixture(30_000); var window = open(fixture);
        try {
            await(window::selected);
            fixture.latest.set(Fixture.quest(null, Instant.now())); fixture.view.emit(true);
            await(() -> window.text().contains("任務の再観測が必要です"));
            assertTrue(fx(() -> window.tree().isShowRoot())); assertFalse(fx(window::selected));
            assertFalse(fx(() -> window.info().contains("季節版確認済み")));
            assertFalse(fx(() -> window.info().contains("null")));
            assertEquals(1, fixture.collections.get());
        } finally { fx(() -> { window.close(); return null; }); }
    }
    @Test void missingTitleInNewObservationUsesFallbackAndReplacesOldSuccessWithUnconfirmed() throws Exception {
        var fixture = new Fixture(30_000); var window = open(fixture);
        try {
            await(window::selected);
            var observed = Fixture.quest("fixture detail", Instant.now()); observed.getQuest().setTitle(null);
            fixture.latest.set(observed); fixture.view.emit(true);
            await(() -> window.text().contains("任務の再観測が必要です"));
            assertEquals("任務 840 期限:synthetic", fx(window::title));
            assertTrue(fx(() -> window.tree().isShowRoot())); assertFalse(fx(window::selected));
            assertFalse(fx(() -> window.info().contains("季節版確認済み")));
            assertEquals(1, fixture.collections.get());
        } finally { fx(() -> { window.close(); return null; }); }
    }
}
