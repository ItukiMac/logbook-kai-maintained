package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import logbook.bean.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Host timers and notification bookkeeping only; no toast, audio, speech or native desktop. */
@EnabledIfSystemProperty(named = "logbook.gui.tests", matches = "true")
@Timeout(20)
class HostTimerNotificationGuiTest {
    @BeforeAll
    static void start() throws Exception {
        FxTestSupport.start();
    }

    private static Object call(MainController controller, String name, Class<?>[] types, Object... values)
            throws Exception {
        var method = MainController.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(controller, values);
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Long> stamps(MainController controller, String name) throws Exception {
        var field = MainController.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<Integer, Long>) field.get(controller);
    }

    @Test
    void missionCountdownClearsOldWarningWhenFleetBecomesIdle() throws Exception {
        FxTestSupport.fx(() -> {
            var port = new DeckPort();
            port.setId(1);
            port.setName("synthetic fleet");
            port.setShip(List.of());
            port.setMission(new ArrayList<>(List.of(0L, 0L, 0L)));
            var pane = new MissionPane(port);
            var time = (Label) pane.lookup("#time");
            var progress = (ProgressBar) pane.lookup("#progress");
            assertEquals("<未出撃>", ((Label) pane.lookup("#name")).getText());
            assertEquals("", time.getText());
            assertFalse(progress.isVisible());
            port.setMission(new ArrayList<>(List.of(1L, 999999L, System.currentTimeMillis() - 1000)));
            pane.update();
            assertTrue(pane.getStyleClass().contains("stage3"));
            assertEquals("まもなく帰還します", time.getText());
            port.setMission(new ArrayList<>(List.of(0L, 0L, 0L)));
            pane.update();
            assertEquals("", time.getText());
            assertFalse(pane.getStyleClass().contains("stage3"));
            assertTrue(pane.getStyleClass().contains("empty"));
            return null;
        });
    }

    @Test
    void repairCountdownReplacesWarningStylesAndShowsCompletion() throws Exception {
        FxTestSupport.fx(() -> {
            var dock = new Ndock();
            dock.setId(999999);
            dock.setShipId(999999);
            dock.setCompleteTime(System.currentTimeMillis() + 30 * 60_000);
            var pane = new NdockPane(dock);
            assertEquals("", ((Label) pane.lookup("#name")).getText());
            assertTrue(pane.getStyleClass().contains("stage1"));
            dock.setCompleteTime(System.currentTimeMillis() + 15 * 60_000);
            pane.update();
            assertTrue(pane.getStyleClass().contains("stage2"));
            assertFalse(pane.getStyleClass().contains("stage1"));
            dock.setCompleteTime(System.currentTimeMillis() - 1000);
            pane.update();
            assertTrue(pane.getStyleClass().contains("stage3"));
            assertFalse(pane.getStyleClass().contains("stage2"));
            assertEquals("修復完了", ((Label) pane.lookup("#time")).getText());
            return null;
        });
    }

    @Test
    void anchorageTimerClearsElapsedTextWhenStopped() throws Exception {
        FxTestSupport.fx(() -> {
            long old = AppCondition.get().getAkashiTimer();
            try {
                AppCondition.get().setAkashiTimer(System.currentTimeMillis() - 5_000);
                var pane = new AkashiTimerPane();
                assertFalse(((Label) pane.lookup("#time")).getText().isEmpty());
                AppCondition.get().setAkashiTimer(0);
                pane.update();
                assertEquals("", ((Label) pane.lookup("#time")).getText());
            } finally {
                AppCondition.get().setAkashiTimer(old);
            }
            return null;
        });
    }

    @Test
    void notificationThresholdAndRepeatPolicyUseHostBookkeeping() throws Exception {
        FxTestSupport.fx(() -> {
            int old = AppConfig.get().getRemind();
            AppConfig.get().setRemind(60);
            try {
                var controller = new MainController();
                var types = new Class<?>[] { Duration.class, long.class, boolean.class };
                assertEquals(false, call(controller, "requireNotify", types, Duration.ofSeconds(61), 0L, true));
                assertEquals(true, call(controller, "requireNotify", types, Duration.ofSeconds(60), 0L, false));
                assertEquals(false,
                        call(controller, "requireNotify", types, Duration.ZERO, System.currentTimeMillis(), true));
                assertEquals(false, call(controller, "requireNotify", types, Duration.ZERO,
                        System.currentTimeMillis() - 600_000, false));
                assertEquals(true, call(controller, "requireNotify", types, Duration.ZERO,
                        System.currentTimeMillis() - 600_000, true));
            } finally {
                AppConfig.get().setRemind(old);
            }
            return null;
        });
    }

    @Test
    void dueMissionAndRepairNotifyOnceThenResetWithAllExternalChannelsDisabled() throws Exception {
        FxTestSupport.fx(() -> {
            var config = AppConfig.get();
            boolean toast = config.isUseToast(), sound = config.isUseSound(), remind = config.isUseRemind(),
                    speech = AppBouyomiConfig.get().isEnable();
            var ports = DeckPortCollection.get().getDeckPortMap();
            var docks = NdockCollection.get().getNdockMap();
            var oldPorts = new HashMap<>(ports);
            var oldDocks = new HashMap<>(docks);
            try {
                config.setUseToast(false);
                config.setUseSound(false);
                config.setUseRemind(false);
                AppBouyomiConfig.get().setEnable(false);
                ports.clear();
                docks.clear();
                var port = new DeckPort();
                port.setId(1);
                port.setName("synthetic");
                port.setMission(new ArrayList<>(List.of(1L, 1L, System.currentTimeMillis() - 1000)));
                ports.put(1, port);
                var dock = new Ndock();
                dock.setId(1);
                dock.setShipId(999999);
                dock.setCompleteTime(System.currentTimeMillis() - 1000);
                docks.put(1, dock);
                var controller = new MainController();
                var noArgs = new Class<?>[0];
                call(controller, "checkNotifyMission", noArgs);
                call(controller, "checkNotifyNdock", noArgs);
                var mission = stamps(controller, "timeStampMission");
                var repair = stamps(controller, "timeStampNdock");
                assertTrue(mission.get(1) > 0);
                assertTrue(repair.get(1) > 0);
                mission.put(1, 123L);
                repair.put(1, 123L);
                call(controller, "checkNotifyMission", noArgs);
                call(controller, "checkNotifyNdock", noArgs);
                assertEquals(123L, mission.get(1));
                assertEquals(123L, repair.get(1));
                port.setMission(new ArrayList<>(List.of(0L, 0L, 0L)));
                dock.setCompleteTime(0L);
                call(controller, "checkNotifyMission", noArgs);
                call(controller, "checkNotifyNdock", noArgs);
                assertEquals(0L, mission.get(1));
                assertEquals(0L, repair.get(1));
                var clip = MainController.class.getDeclaredField("clip");
                clip.setAccessible(true);
                assertNull(clip.get(controller));
            } finally {
                ports.clear();
                ports.putAll(oldPorts);
                docks.clear();
                docks.putAll(oldDocks);
                config.setUseToast(toast);
                config.setUseSound(sound);
                config.setUseRemind(remind);
                AppBouyomiConfig.get().setEnable(speech);
            }
            return null;
        });
    }
}
