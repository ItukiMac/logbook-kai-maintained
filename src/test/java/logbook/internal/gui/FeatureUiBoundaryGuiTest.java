package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import logbook.bean.AppSeaAreaExp;
import logbook.bean.AppSeaAreaExpCollection;
import logbook.plugin.api.FeatureUiV1;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Exercises the JVM runtime-package boundary, not just Java source visibility. */
@EnabledIfSystemProperty(named = "logbook.gui.tests", matches = "true")
@Timeout(20)
class FeatureUiBoundaryGuiTest {
    @BeforeAll
    static void start() throws Exception {
        FxTestSupport.start();
    }

    @Test
    void repairWindowCanUseHostTableServicesAcrossClassLoaderBoundary() throws Exception {
        openInSeparateLoader("require_ndock", "RequireNdockController", "RequireNdock");
    }

    @Test
    void useitemWindowCanUseHostTableServicesAcrossClassLoaderBoundary() throws Exception {
        openInSeparateLoader("useitem", "UseitemController", "UseitemItem");
    }

    @Test
    void airbaseWindowCanUseHostTableServicesAcrossClassLoaderBoundary() throws Exception {
        openInSeparateLoader("airbase", "AirBaseController");
    }

    @Test
    void publicDialogContractKeepsApplyAndWindowHiddenLifecycle() throws Exception {
        FxTestSupport.fx(() -> {
            var old = AppSeaAreaExpCollection.get().getList();
            AppSeaAreaExpCollection.get().setList(java.util.List.of(new AppSeaAreaExp("synthetic area", 123)));
            AtomicInteger applied = new AtomicInteger();
            AtomicReference<Stage> opened = new AtomicReference<>();
            try {
                FeatureUiV1.showWindow("logbook/gui/calc_exp_area.fxml", null, "synthetic editor",
                        controller -> ((CalcExpSeaAreaEditorController) controller).setApply(applied::incrementAndGet),
                        opened::set);
                assertTrue(opened.get().isShowing());
                opened.get().close();
                assertEquals(1, applied.get());
                assertEquals("synthetic area", AppSeaAreaExpCollection.get().getList().getFirst().getName());
                assertEquals(123, AppSeaAreaExpCollection.get().getList().getFirst().getExp());
            } finally {
                if (opened.get() != null)
                    opened.get().close();
                AppSeaAreaExpCollection.get().setList(old);
            }
            return null;
        });
    }

    private void openInSeparateLoader(String resource, String... classes) throws Exception {
        var names = java.util.Arrays.stream(classes).map(n -> "logbook.internal.gui." + n)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        ClassLoader parent = getClass().getClassLoader();
        ClassLoader featureLoader = new ClassLoader(parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                boolean feature = names.stream().anyMatch(n -> name.equals(n) || name.startsWith(n + "$"));
                if (!feature)
                    return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        try (InputStream in = parent.getResourceAsStream(name.replace('.', '/') + ".class")) {
                            if (in == null)
                                throw new ClassNotFoundException(name);
                            byte[] bytes = in.readAllBytes();
                            loaded = defineClass(name, bytes, 0, bytes.length);
                        } catch (IOException ex) {
                            throw new ClassNotFoundException(name, ex);
                        }
                    }
                    if (resolve)
                        resolveClass(loaded);
                    return loaded;
                }
            }
        };
        FxTestSupport.fx(() -> {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/logbook/gui/" + resource + ".fxml"));
            loader.setClassLoader(featureLoader);
            Stage window = new Stage();
            WindowController controller = null;
            try {
                Parent root = loader.load();
                controller = loader.getController();
                assertSame(featureLoader, controller.getClass().getClassLoader());
                assertNotSame(featureLoader, WindowController.class.getClassLoader());
                controller.initWindow(window);
                window.setScene(new Scene(root));
                window.show();
                root.applyCss();
                root.layout();
                assertFalse(loader.getNamespace().isEmpty());
                if (loader.getNamespace().get("table") instanceof TableView<?> table) {
                    assertFalse(table.getColumns().isEmpty());
                }
            } finally {
                window.close();
                if (controller != null)
                    controller.setWindow(null);
            }
            return null;
        });
    }
}
