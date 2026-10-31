package logbook.internal.gui;

import java.lang.reflect.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.*;
import javafx.util.Duration;
import logbook.bean.*;
import logbook.core.GameDefinitionRuntime;
import logbook.core.LogBookCoreServices;
import logbook.internal.Config;

/** Runs actual UI handlers only inside a newly-created synthetic runtime. No browser/game traffic. */
public final class QuestVisibilityProbe {
    private static int confirmations;
    private static final List<Throwable> errors = new CopyOnWriteArrayList<>();

    private static void check(boolean okay, String text) {
        if (!okay)
            throw new AssertionError(text);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object owner, String name) throws Exception {
        var f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(owner);
    }

    private static void field(Object owner, String name, Object value) throws Exception {
        var f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(owner, value);
    }

    private static AppQuest quest(int id, String title, boolean active) {
        var q = new logbook.bean.QuestList.Quest();
        q.setNo(id);
        q.setTitle(title);
        q.setDetail("retained original");
        q.setCategory(2);
        q.setType(4);
        q.setState(active ? 2 : 1);
        q.setProgressFlag(0);
        q.setBonusFlag(0);
        q.setInvalidFlag(0);
        q.setGetMaterial(new ArrayList<>(List.of(1, 2, 3, 4)));
        q.setObservedAt(Instant.parse("2026-10-29T01:00:00Z"));
        var a = new AppQuest();
        a.setNo(id);
        a.setQuest(q);
        a.setActive(active);
        a.setExpire("9999-12-31 00:00:00");
        return a;
    }

    private static Map<String, byte[]> files() throws Exception {
        var r = new TreeMap<String, byte[]>();
        for (String dir : List.of("config", "customquest"))
            try (var paths = Files.walk(Path.of(dir))) {
                for (Path p : paths.filter(Files::isRegularFile).toList())
                    r.put(p.toString(), Files.readAllBytes(p));
            }
        return r;
    }

    private static boolean unchanged(Map<String, byte[]> original) throws Exception {
        var current = files();
        return original.keySet().equals(current.keySet())
                && original.entrySet().stream().allMatch(e -> Arrays.equals(e.getValue(), current.get(e.getKey())));
    }

    private static List<QuestPane> rows(VBox box) {
        return box.getChildren().stream().filter(QuestPane.class::isInstance).map(QuestPane.class::cast).toList();
    }

    private static QuestPane custom(VBox box) throws Exception {
        for (var row : rows(box)) {
            Object value = field(row, "display");
            if (Integer.valueOf(10001).equals(value.getClass().getMethod("no").invoke(value)))
                return row;
        }
        throw new AssertionError("Original custom definition disappeared from presentation");
    }

    private static Hyperlink restore(VBox box) {
        var button = box.lookup("#quest-visibility-restore");
        check(button instanceof Hyperlink, "Restore must be reachable even when every row is hidden");
        return (Hyperlink) button;
    }

    private static Set<?> hidden() throws Exception {
        var api = Class.forName("logbook.plugin.api.QuestVisibilityV1");
        Object state = api.getMethod("snapshot").invoke(null);
        return (Set<?>) state.getClass().getMethod("hiddenIds").invoke(state);
    }

    private static void competingHide(int id) throws Exception {
        var api = Class.forName("logbook.plugin.api.QuestVisibilityV1");
        Object plan = api.getMethod("prepareHide", Collection.class).invoke(null, List.of(id));
        api.getMethod("apply", plan.getClass(), boolean.class).invoke(null, plan, true);
    }

    @FunctionalInterface
    interface Action {
        void run() throws Exception;
    }

    /** Drive actual modal buttons, including nested conflict notice; do not bypass confirmation code. */
    private static void dialogs(Action action, Action beforeFirstClick, Boolean... answers) throws Exception {
        var queue = new ArrayDeque<Boolean>(List.of(answers));
        boolean[] ran = { false };
        Timeline timer = new Timeline();
        timer.getKeyFrames().add(new KeyFrame(Duration.millis(25), event -> {
            if (queue.isEmpty())
                return;
            try {
                for (Window window : List.copyOf(Window.getWindows())) {
                    if (!window.isShowing() || window.getScene() == null)
                        continue;
                    var root = window.getScene().getRoot();
                    DialogPane pane = root instanceof DialogPane d ? d : (DialogPane) root.lookup(".dialog-pane");
                    if (pane == null)
                        continue;
                    if (!ran[0]) {
                        ran[0] = true;
                        if (beforeFirstClick != null)
                            beforeFirstClick.run();
                    }
                    boolean yes = queue.removeFirst();
                    var type = pane.getButtonTypes().stream()
                            .filter(t -> yes ? t.getButtonData() == ButtonBar.ButtonData.OK_DONE
                                    : t.getButtonData().isCancelButton())
                            .findFirst().orElseThrow();
                    confirmations++;
                    if (queue.isEmpty())
                        timer.stop();
                    ((ButtonBase) pane.lookupButton(type)).fire();
                    return;
                }
            } catch (Throwable error) {
                errors.add(error);
                timer.stop();
                Platform.exit();
            }
        }));
        timer.setCycleCount(Animation.INDEFINITE);
        timer.play();
        try {
            action.run();
        } finally {
            timer.stop();
        }
    }

    private static void runFx(Action action) throws Exception {
        var done = new CompletableFuture<Void>();
        Platform.runLater(() -> {
            try {
                action.run();
                done.complete(null);
            } catch (Throwable error) {
                done.completeExceptionally(error);
            }
        });
        done.get(60, TimeUnit.SECONDS);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("verify-new-process-session-only")) {
            check(hidden().isEmpty(), "Visibility must not persist across application processes");
            System.out.println("QUEST_VISIBILITY_RESTART_OK hidden_empty=true");
            return;
        }
        check(args.length == 1 && args[0].equals("new-synthetic-quest-visibility-only"),
                "Explicit fixture mode required");
        check(!Files.exists(Path.of("config")) && !Files.exists(Path.of("customquest")), "Existing runtime rejected");
        Files.createDirectory(Path.of("customquest"));
        Files.writeString(Path.of("customquest/10001.json"),
                "{\n// original comment retained\n\"customName\":\"retained custom\",\"conditions\":[],\"unknownField\":{\"mustRemain\":true}\n}\n",
                StandardOpenOption.CREATE_NEW);
        LogBookCoreServices.init(List.of());
        GameDefinitionRuntime.initialize();
        var regular = quest(900001, "regular", true);
        var inactive = quest(900002, "inactive", false);
        var cached = quest(10001, "cached custom", true);
        var map = AppQuestCollection.get().getQuest();
        map.put(900001, regular);
        map.put(900002, inactive);
        map.put(10001, cached);
        AppConfig.get().setShowQuest(true);
        Config.getDefault().store();
        var before = files();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            errors.add(error);
            error.printStackTrace();
        });
        var started = new CompletableFuture<Void>();
        Platform.startup(() -> started.complete(null));
        started.get(20, TimeUnit.SECONDS);
        Platform.setImplicitExit(false);
        try {
            runFx(() -> {
                var main = new MainController();
                var box = new VBox();
                var panel = new TitledPane("quests", box);
                field(main, "questbox", box);
                field(main, "questPane", panel);
                var render = MainController.class.getDeclaredMethod("quest");
                render.setAccessible(true);
                Stage stage = new Stage();
                stage.setScene(new Scene(panel, 820, 480));
                stage.show();
                try {
                    render.invoke(main);
                    panel.applyCss();
                    panel.layout();
                    check(rows(box).size() == 3, "Expected regular/custom/inactive rows");
                    // Same check on old source demonstrates irreversible custom JSON removal.
                    dialogs(() -> custom(box).remove(new ActionEvent()), null, true);
                    check(Files.exists(Path.of("customquest/10001.json")) && map.get(10001) == cached
                            && unchanged(before),
                            "QUEST_REMOVE_LOST_ORIGINAL custom_file_and_cached_quest_must_be_retained");
                    check(confirmations > 0, "Actual confirmation was not presented");
                    check(hidden().equals(Set.of(10001)) && rows(box).size() == 2,
                            "Confirmed custom hide not rendered");
                    dialogs(() -> restore(box).fire(), null, true);
                    check(rows(box).size() == 3 && hidden().isEmpty(), "Restore did not return all original rows");
                    dialogs(() -> rows(box).getFirst().remove(new ActionEvent()), null, false);
                    check(hidden().isEmpty() && rows(box).size() == 3, "Cancel applied a visibility command");
                    dialogs(() -> custom(box).removeAll(new ActionEvent()), null, false);
                    check(hidden().isEmpty(), "Cancel all changed state");
                    dialogs(() -> custom(box).removeAll(new ActionEvent()), null, true);
                    check(rows(box).isEmpty() && hidden().equals(Set.of(900001, 900002, 10001)),
                            "Hide all did not target exact displayed IDs");
                    check(box.lookup("#quest-visibility-banner") != null,
                            "All-hidden state lost its restoration entry");
                    dialogs(() -> restore(box).fire(), null, false);
                    check(rows(box).isEmpty(), "Cancelled restoration modified visibility");
                    dialogs(() -> restore(box).fire(), null, true);
                    check(rows(box).size() == 3 && hidden().isEmpty(), "Restore all did not recover source projection");
                    dialogs(() -> custom(box).remove(new ActionEvent()), () -> competingHide(900002), true, true);
                    check(hidden().equals(Set.of(900002)),
                            "Stale custom command must not apply after a concurrent visibility edit");
                    check(rows(box).size() == 2 && custom(box) != null,
                            "Conflict should refresh without silently retrying");
                    dialogs(() -> restore(box).fire(), null, true);
                    check(hidden().isEmpty() && rows(box).size() == 3, "Conflict recovery failed");
                    dialogs(() -> custom(box).remove(new ActionEvent()), null, true); // Leave one hidden to test a fresh JVM.
                    check(map.size() == 3 && map.get(900001) == regular && map.get(900002) == inactive
                            && map.get(10001) == cached, "Command mutated original cache membership");
                    check(regular.isActive() && !inactive.isActive() && cached.isActive(), "Acceptance state changed");
                    check(unchanged(before), "Visibility wrote original settings or custom JSON");
                } finally {
                    stage.close();
                }
            });
            runFx(() -> {
            });
            check(errors.isEmpty(), "Unexpected FX errors: " + errors);
            check(unchanged(before), "Lifecycle changed source bytes");
            check(confirmations >= 11, "Not all actual confirm/cancel/conflict paths executed");
            System.out.println(
                    "QUEST_VISIBILITY_GUI_OK confirm_cancel=true hide_restore_all=true stale_rejected=true originals_unchanged=true acceptance_unchanged=true custom_slot_retained=true production_accessed=false");
        } finally {
            Platform.exit();
        }
    }
}
