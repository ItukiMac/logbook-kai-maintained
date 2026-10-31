package logbook.internal.gui;

import java.lang.reflect.Field;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import logbook.bean.*;
import logbook.core.GameDefinitionRuntime;
import logbook.core.LogBookCoreServices;
import logbook.internal.Config;

/** New synthetic runtime only. Does not start browser/proxy or execute editing/removal commands. */
public final class QuestLocalFilesProbe {
    private static void check(boolean okay, String message) {
        if (!okay)
            throw new AssertionError(message);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(o);
    }

    private static void field(Object o, String name, Object value) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, value);
    }

    private static AppQuest quest(int id, int category, String title) {
        var q = new QuestList.Quest();
        q.setNo(id);
        q.setCategory(category);
        q.setTitle(title);
        q.setDetail("detail");
        q.setState(2);
        q.setType(4);
        q.setProgressFlag(0);
        q.setBonusFlag(0);
        q.setInvalidFlag(0);
        q.setGetMaterial(List.of());
        q.setObservedAt(Instant.parse("2026-10-29T00:00:00Z"));
        var a = new AppQuest();
        a.setNo(id);
        a.setQuest(q);
        a.setActive(true);
        a.setExpire("retained expiry");
        return a;
    }

    private static Map<String, byte[]> tree() throws Exception {
        var values = new TreeMap<String, byte[]>();
        for (String directory : List.of("config", "customquest", "arsenalquest"))
            try (var files = Files.walk(Path.of(directory))) {
                for (Path p : files.filter(Files::isRegularFile).toList())
                    values.put(p.toString(), Files.readAllBytes(p));
            }
        return values;
    }

    private static void unchanged(Map<String, byte[]> expected) throws Exception {
        var actual = tree();
        check(expected.keySet().equals(actual.keySet()), "Rendering changed filenames");
        for (var e : expected.entrySet())
            check(Arrays.equals(e.getValue(), actual.get(e.getKey())), "Rendering changed bytes: " + e.getKey());
    }

    private static QuestPane row(VBox box, int id) throws Exception {
        for (var node : box.getChildren())
            if (node instanceof QuestPane p) {
                Object d = field(p, "display");
                if (((Number) d.getClass().getMethod("no").invoke(d)).intValue() == id)
                    return p;
            }
        return null;
    }

    private static String title(QuestPane pane) throws Exception {
        return ((Hyperlink) field(pane, "name")).getText();
    }

    private static List<String> notes(QuestPane pane) throws Exception {
        VBox info = field(pane, "infomation");
        return info.getChildren().stream().filter(Label.class::isInstance).map(Label.class::cast).map(Label::getText)
                .toList();
    }

    private static void replaceSameMetadata(Path file, String content) throws Exception {
        FileTime time = Files.getLastModifiedTime(file);
        long size = Files.size(file);
        Files.writeString(file, content);
        Files.setLastModifiedTime(file, time);
        check(Files.size(file) == size && Files.getLastModifiedTime(file).equals(time),
                "Fixture failed same-size/time requirement");
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 1 && args[0].equals("synthetic-local-files-only"), "Explicit synthetic mode required");
        for (String folder : List.of("config", "customquest", "arsenalquest"))
            check(!Files.exists(Path.of(folder)), "Existing runtime refused");
        Files.createDirectory(Path.of("customquest"));
        Files.createDirectory(Path.of("arsenalquest"));
        Path custom = Path.of("customquest/10001.json"), note = Path.of("arsenalquest/900001.json");
        Files.writeString(custom, "{\"customName\":\"Aa\",\"conditions\":[]}", StandardOpenOption.CREATE_NEW);
        Files.writeString(note, "{\"comment\":\"before\",\"discard\":true}", StandardOpenOption.CREATE_NEW);
        LogBookCoreServices.init(List.of());
        GameDefinitionRuntime.initialize();
        var original = quest(900001, 6, "arsenal");
        var cached = quest(10002, 2, "retained cache");
        var map = AppQuestCollection.get().getQuest();
        map.put(original.getNo(), original);
        map.put(cached.getNo(), cached);
        AppConfig.get().setShowQuest(true);
        Config.getDefault().store();
        var failures = new CopyOnWriteArrayList<Throwable>();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            failures.add(e);
            e.printStackTrace();
        });
        var ready = new CompletableFuture<Void>();
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            ready.complete(null);
        });
        ready.get(20, TimeUnit.SECONDS);
        try {
            var finished = new CompletableFuture<Void>();
            Platform.runLater(() -> {
                try {
                    var main = new MainController();
                    var box = new VBox();
                    var panel = new TitledPane("quests", box);
                    field(main, "questbox", box);
                    field(main, "questPane", panel);
                    var render = MainController.class.getDeclaredMethod("quest");
                    render.setAccessible(true);
                    Stage stage = new Stage();
                    stage.setScene(new Scene(panel, 700, 450));
                    stage.show();
                    try {
                        var expected = tree();
                        render.invoke(main);
                        panel.applyCss();
                        panel.layout();
                        unchanged(expected);
                        check(title(row(box, 10001)).equals("Aa"), "Initial custom row missing");
                        check(notes(row(box, 900001)).contains("before"), "Original arsenal note missing");
                        check(((Hyperlink) field(row(box, 900001), "name")).getOpacity() == 0.5,
                                "Discard style missing");
                        QuestPane oldCustom = row(box, 10001), oldArsenal = row(box, 900001);
                        replaceSameMetadata(custom, "{\"customName\":\"BB\",\"conditions\":[]}");
                        replaceSameMetadata(note, "{\"comment\":\"AFTER!\",\"discard\":true}");
                        expected = tree();
                        render.invoke(main);
                        unchanged(expected);
                        check(title(row(box, 10001)).equals("BB"), "LOCAL_CONTENT_CHANGE_NOT_RENDERED");
                        check(notes(row(box, 900001)).contains("AFTER!"), "Same-metadata arsenal note stale");
                        check(title(oldCustom).equals("Aa") && notes(oldArsenal).contains("before"),
                                "Captured controls mutated in place");
                        var stable = List.copyOf(box.getChildren());
                        render.invoke(main);
                        check(stable.equals(List.copyOf(box.getChildren())), "Unchanged files rebuilt controls");
                        Files.writeString(note, "{\"comment\":\"clear\",\"discard\":false}");
                        expected = tree();
                        render.invoke(main);
                        unchanged(expected);
                        check(((Hyperlink) field(row(box, 900001), "name")).getOpacity() == 1.0,
                                "Stale discard style retained");
                        Files.writeString(custom, "{broken original");
                        Files.writeString(note, "{bad original");
                        expected = tree();
                        render.invoke(main);
                        unchanged(expected);
                        check(title(row(box, 10001)).equals("カスタム任務1"), "Invalid custom lost safe fallback");
                        check(!notes(row(box, 10001)).isEmpty() && !notes(row(box, 900001)).isEmpty(),
                                "Read failures not visible");
                        check(!notes(row(box, 900001)).contains("AFTER!"),
                                "Stale valid note survived malformed replacement");
                        Files.move(custom, Path.of("customquest/original-retained.json"));
                        expected = tree();
                        render.invoke(main);
                        unchanged(expected);
                        check(row(box, 10001) == null, "Retired active name still shown");
                        Files.writeString(custom, "{\"customName\":\"returned\"}", StandardOpenOption.CREATE_NEW);
                        expected = tree();
                        render.invoke(main);
                        unchanged(expected);
                        check(title(row(box, 10001)).equals("returned"), "New active name not observed");
                        Object displayed = field(row(box, 10001), "display");
                        check(displayed.getClass().getMethod("observedAt").invoke(displayed) == null,
                                "Local file fabricated game observation");
                        check(displayed.getClass().getMethod("expire").invoke(displayed) == null,
                                "Local read fabricated expiry");
                        Object snapshot = field(main, "displayedLocalQuestFiles");
                        Map<?, ?> capturedNotes = (Map<?, ?>) snapshot.getClass().getMethod("notes").invoke(snapshot);
                        Object captured = capturedNotes.get(900001);
                        Files.writeString(note, "{\"comment\":\"newer\"}");
                        expected = tree();
                        var constructor = QuestPane.class.getConstructor(logbook.plugin.api.QuestListV1.Quest.class,
                                captured.getClass());
                        var detached = (QuestPane) constructor
                                .newInstance(logbook.core.QuestListReadService.copy(original), captured);
                        check(!notes(detached).contains("newer"),
                                "Constructor reopened the local file instead of using capture");
                        unchanged(expected);
                        int resetCases = 0;
                        for (String reset : List.of("単発", "デイリー", "ウィークリー", "マンスリー", "クォータリー", "イヤリー")) {
                            Files.writeString(custom, "{\"customName\":\"reset fixture\",\"resetType\":\"" + reset
                                    + "\",\"yearlyResetMonth\":6,\"conditions\":[]}");
                            expected = tree();
                            render.invoke(main);
                            var capturedRow = (logbook.plugin.api.QuestListV1.Quest) field(row(box, 10001), "display");
                            var legacy = logbook.core.QuestListReadService.toLegacy(capturedRow);
                            var expectedProgress = AppQuest.toAppQuest(legacy.getQuest());
                            var actual = logbook.core.QuestListReadService.toProgressInput(capturedRow);
                            check(Objects.equals(expectedProgress.getExpire(), actual.getExpire())
                                    && actual.getExpire() != null, "Custom reset deadline changed: " + reset);
                            check(actual.getQuest().getObservedAt() == null && capturedRow.expire() == null
                                    && capturedRow.observedAt() == null,
                                    "Derived expiry contaminated local observation");
                            unchanged(expected);
                            resetCases++;
                        }
                        check(resetCases == 6, "Missing custom reset compatibility cases");
                        check(map.size() == 2 && map.get(10002) == cached && map.get(900001) == original,
                                "Local read mutated acceptance/cache");
                        check(original.isActive()
                                && original.getQuest().getObservedAt().equals(Instant.parse("2026-10-29T00:00:00Z")),
                                "Observed game state changed");
                        check(failures.isEmpty(), "Uncaught GUI errors: " + failures);
                    } finally {
                        stage.close();
                    }
                    finished.complete(null);
                } catch (Throwable error) {
                    finished.completeExceptionally(error);
                }
            });
            finished.get(50, TimeUnit.SECONDS);
            System.out.println(
                    "QUEST_LOCAL_FILES_GUI_OK custom_reset_compatibility=6 same_size_mtime_refresh=true custom_and_arsenal=true unreadable_visible=true external_bytes_preserved=true immutable_capture=true cached_quests_preserved=true production_accessed=false");
        } finally {
            Platform.exit();
        }
    }
}
