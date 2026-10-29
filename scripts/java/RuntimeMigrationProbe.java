package logbook.internal.kancolle;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import logbook.bean.AppConfig;
import logbook.bean.AppQuest;
import logbook.bean.AppQuestDuration;
import logbook.internal.Config;
import logbook.internal.Launcher;
import logbook.internal.gui.WindowHolder;
import logbook.internal.updatecheck.ManagedBundleMode;

/**
 * Disposable synthetic fixtures only. This class is not packaged in the app.
 * Old/new fixture generations share the current v1 journal and acceptance schema.
 * Mutations are permitted only after all twelve configured paths resolve inside
 * the explicitly selected copied cwd. The Python migration gate owns approval,
 * original-tree write monitoring, timeouts and immutable source verification.
 */
public final class RuntimeMigrationProbe {
    private static final String EXPIRY = "2025-06-01 05:00:00";
    private static final String CUSTOM = "// retained synthetic comment\r\n{\"startDate\":\"2025-05-10 09:00:00\"}\r\n";
    private static final String PLUGIN = "opaque fixture bytes; not loaded by any probe\n";
    private static final List<String> FIELDS = List.of("reportPath", "performanceDir", "pluginsDir",
            "resourcesDir", "battleLogDir", "captureDir", "storeApiStart2Dir", "missionSoundDir",
            "ndockSoundDir", "alertSoundDir", "defaultNotifySound", "ffmpegPath");

    private static long time(String value) {
        return LocalDateTime.parse(value, logbook.internal.util.DateUtil.DATE_FORMAT)
                .atZone(ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli();
    }

    private static PortArrival arrival(String id, String departure, String arrival) {
        return new PortArrival(PortArrival.FORMAT, id, time(departure), time(arrival),
                "1-6", 3, List.of(101, 102), true);
    }

    private static List<PortArrival> originals() {
        return List.of(
                arrival("11111111-1111-4111-8111-111111111111", "2025-05-10 09:01:00", "2025-05-10 09:10:00"),
                arrival("22222222-2222-4222-8222-222222222222", "2025-05-10 10:01:00", "2025-05-10 10:10:00"),
                arrival("33333333-3333-4333-8333-333333333333", "2025-05-10 08:01:00", "2025-05-10 09:05:00"));
    }

    private static PortArrival added() {
        return arrival("44444444-4444-4444-8444-444444444444", "2025-05-10 11:01:00", "2025-05-10 11:10:00");
    }

    private static void fresh(Path path, String text) throws Exception {
        Files.writeString(path, text, StandardOpenOption.CREATE_NEW);
    }

    private static void seed(Path root, Path external, boolean backupOnly) throws Exception {
        Files.createDirectory(root);
        Files.createDirectory(external);
        for (String name : List.of("config", "customquest", "plugins", "retained-old-application")) {
            Files.createDirectory(root.resolve(name));
        }
        Map<String, Object> settings = new LinkedHashMap<>();
        for (String field : FIELDS) {
            if (field.equals("pluginsDir")) {
                settings.put(field, "plugins");
            } else if (field.equals("defaultNotifySound") || field.equals("ffmpegPath")) {
                Path file = external.resolve(field + ".bin");
                fresh(file, "inert external " + field + " fixture\n");
                settings.put(field, file.toString());
            } else {
                Path directory = external.resolve(field);
                Files.createDirectory(directory);
                fresh(directory.resolve("retained.bin"), "retained external " + field + "\n");
                settings.put(field, directory.toString());
            }
        }
        settings.put("listenPort", 0); // No socket is opened by a headless fixture/reader.
        settings.put("allowOnlyFromLocalhost", true);
        settings.put("useProxy", false);
        settings.put("usePlugin", false);
        settings.put("checkUpdate", false);
        settings.put("checkDoit", false);
        settings.put("useSound", false);
        settings.put("useToast", false);
        settings.put("useNotification", false);
        settings.put("soundLevel", 85);
        byte[] config = new ObjectMapper().writeValueAsBytes(settings);
        String configName = "config/logbook.bean.AppConfig.json";
        if (!backupOnly) Files.write(root.resolve(configName), config, StandardOpenOption.CREATE_NEW);
        Files.write(root.resolve(configName + ".backup"), config, StandardOpenOption.CREATE_NEW);
        fresh(root.resolve("customquest/10001.json"), CUSTOM);
        fresh(root.resolve("plugins/retained.jar"), PLUGIN);
        fresh(root.resolve("retained-old-application/generation-A.txt"), "synthetic-A/current-schema\n");
        Files.write(root.resolve("retained-old-application/image.bin"), new byte[] {0, 1, 2, (byte) 255},
                StandardOpenOption.CREATE_NEW);
        AppQuestDuration periods = new AppQuestDuration();
        AppQuestDuration.Duration duration = new AppQuestDuration.Duration();
        duration.setFrom("2025-05-10 09:00:00");
        duration.setTo("2025-05-11 00:00:00");
        periods.getMap().put(EXPIRY, Map.of(905, List.of(duration)));
        new ObjectMapper().writeValue(root.resolve("config/logbook.bean.AppQuestDuration.json").toFile(), periods);
        PortArrivalStore journal = new PortArrivalStore(root.resolve("sortie-arrivals-v1"));
        for (PortArrival record : originals()) journal.append(record);
        System.out.println("MIGRATION_FIXTURE_SEEDED records=3 accepted=2 backup_only=" + backupOnly);
    }

    private static Map<String, String> paths(AppConfig config) {
        Map<String, String> paths = new LinkedHashMap<>();
        paths.put("reportPath", config.getReportPath());
        paths.put("performanceDir", config.getPerformanceDir());
        paths.put("pluginsDir", config.getPluginsDir());
        paths.put("resourcesDir", config.getResourcesDir());
        paths.put("battleLogDir", config.getBattleLogDir());
        paths.put("captureDir", config.getCaptureDir());
        paths.put("storeApiStart2Dir", config.getStoreApiStart2Dir());
        paths.put("missionSoundDir", config.getMissionSoundDir());
        paths.put("ndockSoundDir", config.getNdockSoundDir());
        paths.put("alertSoundDir", config.getAlertSoundDir());
        paths.put("defaultNotifySound", config.getDefaultNotifySound());
        paths.put("ffmpegPath", config.getFfmpegPath());
        return paths;
    }

    private static void verifyPaths(Path root) throws Exception {
        if (!root.toRealPath().equals(Path.of("").toRealPath())) {
            throw new IllegalArgumentException("Probe cwd must be the isolated runtime root");
        }
        for (var reference : paths(AppConfig.get()).entrySet()) {
            String value = reference.getValue();
            if (value == null || value.isBlank()) throw new AssertionError("Missing explicit path: " + reference.getKey());
            Path raw = Path.of(value);
            Path actual = root.resolve(raw).normalize();
            if (raw.isAbsolute() || !actual.startsWith(root)) {
                throw new IllegalArgumentException("Path escapes the copied runtime: " + reference.getKey());
            }
            if (!actual.toRealPath().startsWith(root.toRealPath())) {
                throw new IllegalArgumentException("Symbolic path escapes the copied runtime");
            }
        }
        if (AppConfig.get().isUsePlugin()) throw new AssertionError("Opaque fixture plugin must stay disabled");
        if (AppConfig.get().isCheckUpdate()) throw new AssertionError("Fixture updater must stay disabled");
    }

    private static void verify(Path root, boolean changed) throws Exception {
        verifyPaths(root);
        List<PortArrival> records = new PortArrivalStore(root.resolve("sortie-arrivals-v1"))
                .readRange(time("2025-05-01 00:00:00"), time("2025-06-01 00:00:00"));
        List<PortArrival> expected = new ArrayList<>(originals());
        if (changed) expected.add(added());
        if (records.size() != expected.size() || !Set.copyOf(records).equals(Set.copyOf(expected))) {
            throw new AssertionError("Arrival records lost or changed");
        }
        AppQuest quest = new AppQuest();
        quest.setNo(905);
        quest.setExpire(EXPIRY);
        List<PortArrival> accepted = AppQuestDuration.get().getArrivalCondition(quest);
        Set<String> expectedAccepted = changed ? Set.of(added().sortieId())
                : Set.of(originals().get(0).sortieId(), originals().get(1).sortieId());
        Set<String> actualAccepted = accepted.stream().map(PortArrival::sortieId).collect(java.util.stream.Collectors.toSet());
        if (!expectedAccepted.equals(actualAccepted) || accepted.size() != expectedAccepted.size()) {
            throw new AssertionError("Actual acceptance/arrival join changed");
        }
        if (!Files.readString(root.resolve("customquest/10001.json")).equals(CUSTOM)) throw new AssertionError("Custom quest bytes lost");
        if (!Files.readString(root.resolve("plugins/retained.jar")).equals(PLUGIN)) throw new AssertionError("Plugin bytes lost");
        if (!Files.readString(root.resolve("retained-old-application/generation-A.txt")).equals("synthetic-A/current-schema\n")) {
            throw new AssertionError("Old application marker lost");
        }
        try (var entries = Files.list(root.resolve("sortie-arrivals-v1/2025-05"))) {
            if (entries.filter(path -> path.toString().endsWith(".pending")).count() != records.size()) {
                throw new AssertionError("Pending arrival evidence lost");
            }
        }
        if (changed) {
            if (AppConfig.get().getSoundLevel() != 42) throw new AssertionError("Real Config.store write lost");
            if (!Files.isRegularFile(root.resolve("generation-B-added.txt"))) throw new AssertionError("New generation marker lost");
        } else if (AppConfig.get().getSoundLevel() != 85) {
            throw new AssertionError("Old setting was not restored");
        }
        System.out.println("MIGRATION_REAL_JAVA_OK records=" + records.size() + " accepted=" + accepted.size()
                + " config_paths=12 pending_retained=true");
    }

    private static void mutate(Path root) throws Exception {
        verify(root, false);
        new PortArrivalStore(root.resolve("sortie-arrivals-v1")).append(added());
        AppQuestDuration.get().getMap().get(EXPIRY).get(905).get(0).setFrom("2025-05-10 10:30:00");
        AppConfig.get().setSoundLevel(42);
        Config.getDefault().store(); // Actual production writer, only in the disposable copied cwd.
        fresh(root.resolve(AppConfig.get().getReportPath()).resolve("new-report.csv"), "synthetic migrated report\n");
        fresh(root.resolve("generation-B-added.txt"), "synthetic-B/current-schema\n");
        verify(root, true);
        System.out.println("MIGRATION_REAL_JAVA_CHANGED records=4 accepted=1");
    }

    /** A timed-out observation is not launcher failure before the existing overall deadline.
     * Cancel queued stale observations without interrupting the FX application thread.
     * Execution/interrupt/cancellation errors are not swallowed or reported as ready.
     */
    static boolean pollWindow(java.util.concurrent.Future<Boolean> window, long deadline)
            throws InterruptedException, java.util.concurrent.ExecutionException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            window.cancel(false);
            return false;
        }
        try {
            return Boolean.TRUE.equals(window.get(Math.min(TimeUnit.SECONDS.toNanos(2), remaining),
                    TimeUnit.NANOSECONDS));
        } catch (java.util.concurrent.TimeoutException pendingObservation) {
            window.cancel(false);
            return false;
        }
    }

    private static void launcher(Path root) throws Exception {
        verify(root, false);
        if (!ManagedBundleMode.enabled()) throw new AssertionError("Managed updater guard is required");
        // Local socket use begins only inside the explicitly approved Launcher command.
        try (ServerSocket port = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            AppConfig.get().setListenPort(port.getLocalPort());
        }
        Thread observer = new Thread(() -> {
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
                boolean ready = false;
                while (System.nanoTime() < deadline) {
                    FutureTask<Boolean> window = new FutureTask<>(() -> {
                        var stage = WindowHolder.getInstance().getMainWindow();
                        return stage != null && stage.isShowing() && stage.getScene().getRoot() != null;
                    });
                    try {
                        Platform.runLater(window);
                        if (pollWindow(window, deadline)) {
                            try (Socket ignored = new Socket("127.0.0.1", AppConfig.get().getListenPort())) {
                                ready = true;
                                break;
                            } catch (java.io.IOException notYetListening) { }
                        }
                    } catch (IllegalStateException notYetInitialized) { }
                    Thread.sleep(50);
                }
                if (!ready) throw new AssertionError("Actual Launcher window/local proxy did not become ready");
                mutate(root);
                System.out.println("MIGRATION_REAL_LAUNCHER_OK main_window=true localhost_proxy=true");
                Platform.runLater(Platform::exit);
            } catch (Throwable failure) {
                failure.printStackTrace();
                System.exit(23);
            }
        }, "synthetic-migration-launcher-observer");
        observer.setDaemon(true);
        observer.start();
        Launcher.main(new String[0]);
        observer.join(30_000);
        if (observer.isAlive()) throw new AssertionError("Launcher observer did not finish");
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("mode and NEW synthetic runtime required");
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        switch (args[0]) {
            case "seed", "seed-backup" -> {
                if (args.length != 3) throw new IllegalArgumentException("New external fixture path required");
                seed(root, Path.of(args[2]).toAbsolutePath().normalize(), args[0].equals("seed-backup"));
            }
            case "verify" -> verify(root, false);
            case "verify-changed" -> verify(root, true);
            case "mutate" -> mutate(root);
            case "launcher" -> launcher(root);
            default -> throw new IllegalArgumentException("Unknown synthetic probe mode");
        }
    }
}
