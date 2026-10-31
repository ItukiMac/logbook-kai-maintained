package logbook.internal.logger;

import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import logbook.bean.AppConfig;
import logbook.core.LogBookCoreServices;
import logbook.internal.gui.WindowHolder;
import logbook.internal.updatecheck.ManagedBundleMode;
import logbook.plugin.api.FeatureModule;
import logbook.plugin.lifecycle.StartUp;

/** Test plugin only, never distributed: observes a REAL Launcher/main window/proxy. */
public final class ManagedLauncherSmokeHook implements StartUp {
    @Override public void run() {
        try {
            if (!ManagedBundleMode.enabled()) throw new AssertionError("Managed property absent");
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            boolean ready = false;
            while (System.nanoTime() < until) {
                FutureTask<Boolean> check = new FutureTask<>(() -> {
                    var stage = WindowHolder.getInstance().getMainWindow();
                    return stage != null && stage.isShowing() && stage.getScene().getRoot() != null;
                });
                Platform.runLater(check);
                if (check.get(2, TimeUnit.SECONDS)) {
                    try (Socket ignored = new Socket("127.0.0.1", AppConfig.get().getListenPort())) {
                        ready = true; break;
                    } catch (java.io.IOException notStartedYet) { }
                }
                Thread.sleep(50);
            }
            if (!ready) throw new AssertionError("Main window/proxy did not become ready");
            var features = LogBookCoreServices.getServiceProviders(FeatureModule.class)
                    .flatMap(module -> module.features().stream()).toList();
            if (features.size() != 7) throw new AssertionError("Feature count: " + features.size());
            for (var feature : features) {
                String source = LogBookCoreServices.getClassLoader().getResource(feature.fxml()).toString();
                if (!source.contains("/features/logbook-kai2-feature-")) throw new AssertionError(source);
            }
            Path expected = Path.of("expected-generation.txt");
            if (Files.exists(expected)) {
                String label = Files.readString(expected);
                String[] jars = System.getProperty("java.class.path").split(java.io.File.pathSeparator);
                if (jars.length != 9) throw new AssertionError("Expected exactly nine bundle JARs");
                for (String jarPath : jars) {
                    try (var jar = new java.util.zip.ZipFile(jarPath)) {
                        var entry = jar.getEntry("META-INF/managed-generation-test.txt");
                        if (entry == null) throw new AssertionError("Missing fixture generation marker");
                        String actual = new String(jar.getInputStream(entry).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        if (!label.equals(actual)) throw new AssertionError("Mixed bundle generation");
                    }
                }
            }
            // Calling the real startup updater must return without network/UI.
            logbook.internal.updatecheck.CheckUpdate.run(true);
            Files.writeString(Path.of("managed-smoke-ready.txt"), "MAIN_WINDOW_PROXY_AND_7_FEATURES_OK\n");
            System.out.println("MANAGED_REAL_LAUNCHER_OK");
            Platform.runLater(Platform::exit);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(23);
        }
    }
}
