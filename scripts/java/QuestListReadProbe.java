package logbook.internal.gui;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Arc;
import javafx.stage.Stage;
import logbook.bean.AppConfig;
import logbook.bean.AppQuest;
import logbook.bean.AppQuestCollection;
import logbook.core.GameDefinitionRuntime;
import logbook.core.LogBookCoreServices;
import logbook.internal.Config;

/** Actual renderer, no main initialize/browser/proxy/delete menu invocation. New synthetic runtime only. */
public final class QuestListReadProbe {
    private static final Instant OBSERVED = Instant.parse("2026-10-28T01:00:00Z");

    private static void check(boolean okay, String message) {
        if (!okay)
            throw new AssertionError(message);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(object);
    }

    private static void field(Object object, String name, Object value) throws Exception {
        Field f = object.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(object, value);
    }

    private static AppQuest quest(int id, String title, boolean active) {
        var q = new logbook.bean.QuestList.Quest();
        q.setNo(id);
        q.setTitle(title);
        q.setDetail("one<br>two");
        q.setCategory(2);
        q.setType(4);
        q.setState(active ? 2 : 1);
        q.setProgressFlag(1);
        q.setBonusFlag(0);
        q.setInvalidFlag(0);
        q.setGetMaterial(new ArrayList<>(List.of(1, 2, 3, 4)));
        q.setObservedAt(OBSERVED);
        var result = new AppQuest();
        result.setNo(id);
        result.setQuest(q);
        result.setActive(active);
        result.setExpire("9999-12-31 00:00:00");
        return result;
    }

    private static Map<String, byte[]> originalFiles() throws Exception {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (String directory : List.of("config", "customquest"))
            try (var paths = Files.walk(Path.of(directory))) {
                for (Path p : paths.filter(Files::isRegularFile).sorted().toList())
                    result.put(p.toString(), Files.readAllBytes(p));
            }
        return result;
    }

    private static boolean sameFiles(Map<String, byte[]> before) throws Exception {
        Map<String, byte[]> after = originalFiles();
        return before.keySet().equals(after.keySet())
                && before.entrySet().stream().allMatch(e -> Arrays.equals(e.getValue(), after.get(e.getKey())));
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 1 && "synthetic-quest-list-only".equals(args[0]), "Explicit fixture mode required");
        check(!Files.exists(Path.of("config")) && !Files.exists(Path.of("customquest")),
                "Refusing an existing runtime");
        Files.createDirectory(Path.of("customquest"));
        Files.writeString(Path.of("customquest/10001.json"),
                "{\n // retained fixture comment\n \"customName\":\"custom fixture\",\"conditions\":[]\n}\n",
                StandardOpenOption.CREATE_NEW);
        LogBookCoreServices.init(List.of());
        GameDefinitionRuntime.initialize();
        var active = quest(900002, "Aa", true);
        var inactive = quest(900001, "inactive", false);
        var cached = quest(10002, "cached custom", true);
        var map = AppQuestCollection.get().getQuest();
        map.put(active.getNo(), active);
        map.put(inactive.getNo(), inactive);
        map.put(cached.getNo(), cached);
        AppConfig.get().setShowQuest(true);
        Config.getDefault().store();
        var original = originalFiles();
        var failures = new CopyOnWriteArrayList<Throwable>();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            failures.add(error);
            error.printStackTrace();
        });
        var ready = new CompletableFuture<Void>();
        Platform.startup(() -> ready.complete(null));
        ready.get(20, TimeUnit.SECONDS);
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
                stage.setScene(new Scene(panel, 600, 350));
                stage.show();
                try {
                    render.invoke(main);
                    panel.applyCss();
                    panel.layout();
                    boolean cacheRetained = map.get(10002) == cached;
                    boolean bytesRetained = sameFiles(original);
                    check(cacheRetained && bytesRetained, "QUEST_RENDER_MUTATED_SOURCE cacheRetained=" + cacheRetained
                            + " persistedBytesUnchanged=" + bytesRetained);
                    check(titles(box).equals(List.of("Aa", "custom fixture", "inactive")),
                            "Active/custom/inactive ordering changed: " + titles(box));
                    QuestPane first = (QuestPane) box.getChildren().getFirst();
                    check(((Label) field(first, "detail")).getText().equals("onetwo"), "Detail rendering changed");
                    check(((Arc) field(first, "progress")).getLength() == 180, "50 percent progress changed");
                    ((Hyperlink) field(first, "name")).fire();
                    check(((VBox) field(first, "detailView")).isVisible(), "Expand action stopped working");
                    check(!((Hyperlink) field(box.getChildren().get(1), "searchLink")).isVisible(),
                            "Custom search link must remain hidden");
                    var beforeNodes = List.copyOf(box.getChildren());
                    render.invoke(main);
                    check(beforeNodes.equals(List.copyOf(box.getChildren())),
                            "Unchanged snapshot unnecessarily rebuilt controls");
                    active.getQuest().setTitle("BB"); // Aa and BB have the same Java String hash.
                    render.invoke(main);
                    check(titles(box).getFirst().equals("BB"), "Hash collision hid an actual quest title change");
                    check(((Hyperlink) field(first, "name")).getText().equals("Aa"),
                            "Old rendered row retained mutable source state");
                    Object copied = field(box.getChildren().getFirst(), "display");
                    check(copied.getClass().isRecord(), "Display is not an immutable value");
                    check(OBSERVED.equals(copied.getClass().getMethod("observedAt").invoke(copied)),
                            "Observation time was fabricated or lost");
                    active.getQuest().setProgressFlag(2);
                    render.invoke(main);
                    check(((Arc) field(box.getChildren().getFirst(), "progress")).getLength() == 288,
                            "80 percent update not rendered");
                    active.setActive(false);
                    inactive.setActive(true);
                    render.invoke(main);
                    check(titles(box).equals(List.of("inactive", "custom fixture", "BB")),
                            "Acceptance ordering did not refresh");
                    AppConfig.get().setShowQuest(false);
                    render.invoke(main);
                    check(!panel.isVisible() && !panel.isManaged(), "Visibility change was missed");
                    var legacy = quest(900003, null, true);
                    legacy.getQuest().setDetail(null);
                    legacy.getQuest().setCategory(null);
                    legacy.getQuest().setProgressFlag(null);
                    var compatible = new QuestPane(legacy);
                    check(((Hyperlink) field(compatible, "name")).getText().equals("任務 900003"),
                            "Missing title was not presented safely");
                    check(((Label) field(compatible, "detail")).getText().isEmpty(),
                            "Missing detail was not presented safely");
                    check(legacy.getQuest().getTitle() == null && legacy.getQuest().getCategory() == null,
                            "Presentation defaults leaked into saved state");
                    check(map.get(10002) == cached && map.size() == 3, "Rendering mutated source membership");
                    check(sameFiles(original),
                            "Repeated rendering persisted settings or changed original custom files");
                } finally {
                    stage.close();
                }
            });
            runFx(() -> {
            });
            check(failures.isEmpty(), "Uncaught GUI failure: " + failures);
            check(sameFiles(original), "Lifecycle callback wrote original settings");
            System.out.println(
                    "QUEST_LIST_READ_GUI_OK cached_custom_preserved=true persisted_files_unchanged=true hash_collision_refresh=true active_custom_inactive_order=true observed_at_preserved=true explicit_remove_not_executed=true production_accessed=false");
        } finally {
            Platform.exit();
        }
    }

    private static List<String> titles(VBox box) throws Exception {
        List<String> result = new ArrayList<>();
        for (var row : box.getChildren())
            result.add(((Hyperlink) field(row, "name")).getText());
        return result;
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    private static void runFx(Action action) throws Exception {
        var future = new CompletableFuture<Void>();
        Platform.runLater(() -> {
            try {
                action.run();
                future.complete(null);
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        });
        future.get(45, TimeUnit.SECONDS);
    }
}
