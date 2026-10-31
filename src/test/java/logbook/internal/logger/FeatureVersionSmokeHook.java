package logbook.internal.logger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.File;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;
import logbook.bean.AppConfig;
import logbook.core.FeatureRegistry;
import logbook.core.LogBookCoreServices;
import logbook.internal.gui.WindowHolder;
import logbook.internal.updatecheck.ManagedBundleMode;
import logbook.plugin.api.FeatureModule;
import logbook.plugin.lifecycle.StartUp;

/** Test-only plugin observing the real Launcher and opening its actual feature menu. */
public final class FeatureVersionSmokeHook implements StartUp {
    private static void check(boolean value, String reason) {
        if (!value)
            throw new AssertionError(reason);
    }

    @Override
    public void run() {
        try {
            check(ManagedBundleMode.enabled(), "Managed mode is required");
            waitForApplication();
            Properties request = pendingRequest();
            String run = request.getProperty("run");
            check(run != null && run.matches("[A-Za-z0-9_-]{1,80}"), "Invalid run identifier");
            FeatureRegistry registry = FeatureRegistry.discover();
            verifyModuleContracts(registry, request.getProperty("version"));

            FutureTask<Void> exercise = new FutureTask<>(() -> {
                Stage main = WindowHolder.getInstance().getMainWindow();
                var candidates = new ArrayList<MenuItem>();
                var bars = new ArrayList<MenuBar>();
                collectBars(main.getScene().getRoot(), bars);
                for (MenuBar bar : bars) {
                    for (Menu menu : bar.getMenus())
                        collect(menu, candidates);
                }
                var items = candidates.stream().filter(item -> "logbook.feature.probability".equals(item.getId()))
                        .toList();
                check(items.size() == 1, "Expected exactly one actual probability menu item");
                // Exercise the host's real action, FXMLLoader and window wiring.
                items.get(0).fire();
                var windows = Window.getWindows().stream()
                        .filter(window -> window != main && window instanceof Stage && window.isShowing())
                        .filter(window -> window.getScene() != null
                                && window.getScene().getRoot().lookup("#fixtureInput") != null)
                        .toList();
                check(windows.size() == 1, "Feature menu did not create its real fixture window");
                Stage window = (Stage) windows.get(0);
                try {
                    var root = window.getScene().getRoot();
                    root.applyCss();
                    root.layout();
                    ((TextField) root.lookup("#fixtureInput")).setText(request.getProperty("input"));
                    ((TextField) root.lookup("#fixtureRun")).setText(run);
                    ((Button) root.lookup("#fixtureCalculate")).fire();
                    String actual = ((Label) root.lookup("#fixtureResult")).getText();
                    check(request.getProperty("expected").equals(actual), "Wrong version's calculation: " + actual);
                    ((Button) root.lookup("#fixtureSave")).fire();
                    String saved = Files.readString(Path.of("feature-fixture-results", run + ".txt"));
                    check((request.getProperty("version") + "\n" + actual + "\n").equals(saved),
                            "Saved result differs from the selected version's screen");
                } finally {
                    window.close();
                }
                return null;
            });
            Platform.runLater(exercise);
            exercise.get(15, TimeUnit.SECONDS);
            logbook.internal.updatecheck.CheckUpdate.run(true);
            Path receipts = Path.of("feature-fixture-receipts");
            Files.createDirectories(receipts);
            Files.writeString(receipts.resolve(run + ".txt"), "MAIN_MENU_WINDOW_CALCULATION_SAVE_OK\n",
                    StandardOpenOption.CREATE_NEW);
            System.out.println("FEATURE_VERSION_REAL_LAUNCHER_OK run=" + run
                    + " version=" + request.getProperty("version") + " result=" + request.getProperty("expected"));
            Platform.runLater(Platform::exit);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(24);
        }
    }

    private static void waitForApplication() throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < until) {
            FutureTask<Boolean> checkWindow = new FutureTask<>(() -> {
                Stage stage = WindowHolder.getInstance().getMainWindow();
                return stage != null && stage.isShowing() && stage.getScene().getRoot() != null;
            });
            Platform.runLater(checkWindow);
            if (checkWindow.get(2, TimeUnit.SECONDS)) {
                try (Socket ignored = new Socket("127.0.0.1", AppConfig.get().getListenPort())) {
                    return;
                } catch (java.io.IOException notStartedYet) {
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Main window/localhost proxy did not become ready");
    }

    private static Properties pendingRequest() throws Exception {
        List<Path> pending;
        try (var files = Files.list(Path.of("feature-fixture-requests"))) {
            pending = files.filter(path -> path.getFileName().toString().endsWith(".properties"))
                    .filter(path -> !Files.exists(Path.of("feature-fixture-receipts",
                            path.getFileName().toString().replace(".properties", ".txt"))))
                    .sorted().toList();
        }
        check(pending.size() == 1, "Expected one new retained launch request");
        Properties request = new Properties();
        try (var input = Files.newBufferedReader(pending.get(0))) {
            request.load(input);
        }
        return request;
    }

    private static void collect(Menu menu, List<MenuItem> found) {
        for (MenuItem item : menu.getItems()) {
            found.add(item);
            if (item instanceof Menu nested)
                collect(nested, found);
        }
    }

    private static void collectBars(Parent parent, List<MenuBar> found) {
        if (parent instanceof MenuBar bar) {
            found.add(bar);
            return;
        }
        for (var node : parent.getChildrenUnmodifiable()) {
            if (node instanceof Parent child)
                collectBars(child, found);
        }
    }

    private static void verifyModuleContracts(FeatureRegistry registry, String expectedVersion) throws Exception {
        Map<String, FeatureModule.Descriptor> descriptors = registry.descriptors().stream()
                .collect(Collectors.toMap(FeatureModule.Descriptor::id, Function.identity()));
        check(descriptors.size() == 8, "Expected all eight declared modules");
        check(expectedVersion.equals(descriptors.get("calculators").version()), "Wrong calculator descriptor version");
        check(registry.group(FeatureModule.Group.CALCULATOR).size() == 4, "Original calculator feature IDs changed");
        int features = 0;
        for (FeatureModule.Group group : FeatureModule.Group.values())
            features += registry.group(group).size();
        check(features == 7, "Original seven menu features must remain available");
        String[] jars = System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator));
        check(jars.length == 9, "Launcher classpath must contain only the nine selected bundle JARs");
        var seen = new ArrayList<String>();
        ObjectMapper mapper = logbook.internal.json.JsonMappers.builder().build();
        for (String jarPath : jars) {
            Path path = Path.of(jarPath);
            check(Files.isRegularFile(path) && path.toString().contains("/versions/"),
                    "Unexpected classpath entry: " + path);
            check(!path.getFileName().toString().equals("logbook-kai2.jar"), "Original fat JAR leaked onto classpath");
            try (ZipFile jar = new ZipFile(path.toFile())) {
                var entry = jar.getEntry("META-INF/logbook/feature.json");
                if (entry == null) {
                    check(path.getFileName().toString().equals("logbook-kai2-core.jar"),
                            "Missing feature contract: " + path);
                    continue;
                }
                JsonNode document;
                try (var bytes = jar.getInputStream(entry)) {
                    document = mapper.readTree(bytes);
                }
                String id = document.path("id").asText();
                FeatureModule.Descriptor descriptor = descriptors.get(id);
                check(descriptor != null, "Undiscovered module in the selected classpath: " + id);
                check(descriptor.version().equals(document.path("version").asText()),
                        "JAR/Java version differs: " + id);
                check(descriptor.hostApi() == document.path("host_api").asInt(), "JAR/Java API differs: " + id);
                check(descriptor.dependencies().size() == document.path("dependencies").size(),
                        "JAR/Java dependency count differs: " + id);
                for (int index = 0; index < descriptor.dependencies().size(); index++) {
                    var dependency = descriptor.dependencies().get(index);
                    var declared = document.path("dependencies").get(index);
                    check(dependency.id().equals(declared.path("id").asText())
                            && dependency.minVersion().equals(declared.path("min_version").asText())
                            && dependency.maxVersionExclusive().equals(declared.path("max_version_exclusive").asText()),
                            "JAR/Java dependency differs: " + id);
                }
                seen.add(id);
            }
        }
        check(seen.size() == 8 && seen.containsAll(descriptors.keySet()),
                "Selected classpath misses a declared module");
        String fxml = LogBookCoreServices.getClassLoader().getResource(registry.get("probability").fxml()).toString();
        check(fxml.contains("/features/logbook-kai2-feature-calculators.jar!"),
                "FXML did not load from the selected feature JAR");
        Class<?> controller = Class.forName("logbook.internal.gui.FeatureVersionFixtureController", false,
                LogBookCoreServices.getClassLoader());
        check(controller.getProtectionDomain().getCodeSource().getLocation().toString()
                .endsWith("/features/logbook-kai2-feature-calculators.jar"),
                "Fixture controller did not load from the independently updated feature JAR");
    }
}
