package logbook.internal.kancolle;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import logbook.bean.AppQuest;
import logbook.bean.AppQuestDuration;
import logbook.internal.Config;

/** Fresh synthetic state only. Does NOT start Launcher, plugins, network or config saving. */
public final class RuntimeSnapshotProbe {
    /** Test-only adapter: run the same fixture on retained Jackson2 and kai2 hosts. */
    private static byte[] jsonBytes(Object value) throws Exception {
        Object mapper;
        try {
            Class<?> type = Class.forName("tools.jackson.databind.json.JsonMapper");
            Object builder = type.getMethod("builderWithJackson2Defaults").invoke(null);
            mapper = builder.getClass().getMethod("build").invoke(builder);
        } catch (ClassNotFoundException legacy) {
            mapper = Class.forName("com.fasterxml.jackson.databind.ObjectMapper").getConstructor().newInstance();
        }
        return (byte[]) mapper.getClass().getMethod("writeValueAsBytes", Object.class).invoke(mapper, value);
    }

    private static final String EXPIRY = "2025-06-01 05:00:00";

    private static long time(String value) {
        return LocalDateTime.parse(value, logbook.internal.util.DateUtil.DATE_FORMAT)
                .atZone(ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli();
    }

    private static PortArrival arrival(String id, String start, String end) {
        return new PortArrival(PortArrival.FORMAT, id, time(start), time(end), "1-6", 3, List.of(101, 102), true);
    }

    private static List<PortArrival> expected() {
        return List.of(
                arrival("11111111-1111-4111-8111-111111111111", "2025-05-10 09:01:00", "2025-05-10 09:10:00"),
                arrival("22222222-2222-4222-8222-222222222222", "2025-05-10 10:01:00", "2025-05-10 10:10:00"),
                arrival("33333333-3333-4333-8333-333333333333", "2025-05-10 08:01:00", "2025-05-10 09:05:00"));
    }

    private static void seed(Path root) throws Exception {
        Files.createDirectory(root); // refuse existing source, even an empty directory
        Files.createDirectory(root.resolve("config"));
        Files.createDirectory(root.resolve("customquest"));
        Files.createDirectory(root.resolve("battlelog"));
        Files.createDirectory(root.resolve("plugins"));
        Files.createDirectory(root.resolve("retained-backup"));
        Files.writeString(root.resolve("config/logbook.bean.AppConfig.json"),
                "{\"reportPath\":\".\",\"usePlugin\":false,\"checkUpdate\":false}", StandardOpenOption.CREATE_NEW);
        Files.writeString(root.resolve("customquest/10001.json"),
                "// retained synthetic comment\r\n{\"startDate\":\"2025-05-10 09:00:00\"}\r\n",
                StandardOpenOption.CREATE_NEW);
        Files.write(root.resolve("battlelog/legacy.bin"), new byte[] { 0, 1, 2, (byte) 255 },
                StandardOpenOption.CREATE_NEW);
        Files.writeString(root.resolve("plugins/retained.jar"), "opaque sentinel; NOT loaded",
                StandardOpenOption.CREATE_NEW);
        Files.writeString(root.resolve("retained-backup/old.jar"), "old opaque sentinel",
                StandardOpenOption.CREATE_NEW);
        var periods = new AppQuestDuration();
        var duration = new AppQuestDuration.Duration();
        duration.setFrom("2025-05-10 09:00:00");
        duration.setTo("2025-05-11 00:00:00");
        periods.getMap().put(EXPIRY, Map.of(905, List.of(duration)));
        Files.write(root.resolve("config/logbook.bean.AppQuestDuration.json"), jsonBytes(periods),
                java.nio.file.StandardOpenOption.CREATE_NEW);
        var store = new PortArrivalStore(root.resolve("sortie-arrivals-v1"));
        for (var arrival : expected())
            store.append(arrival);
        System.out.println("RUNTIME_FIXTURE_SEEDED records=3 accepted=2 pending_retained=true");
    }

    private static void verify(Path root) throws Exception {
        if (!root.toRealPath().equals(Path.of("").toRealPath()))
            throw new IllegalArgumentException("Probe cwd must be isolated runtime root");
        var records = new PortArrivalStore(root.resolve("sortie-arrivals-v1")).readRange(time("2025-05-01 00:00:00"),
                time("2025-06-01 00:00:00"));
        if (!new HashSet<>(records).equals(new HashSet<>(expected())) || records.size() != 3)
            throw new AssertionError("Arrival bytes/model changed");
        var periods = new Config(root.resolve("config")).get(AppQuestDuration.class, AppQuestDuration::new);
        var quest = new AppQuest();
        quest.setNo(905);
        quest.setExpire(EXPIRY);
        var accepted = periods.getArrivalCondition(quest); // REAL Config, duration, runtime and journal; no mocks
        if (accepted.size() != 2
                || !accepted.stream().map(PortArrival::sortieId).collect(java.util.stream.Collectors.toSet())
                        .equals(Set.of(expected().get(0).sortieId(), expected().get(1).sortieId())))
            throw new AssertionError("Acceptance/arrival join changed");
        try (var files = Files.list(root.resolve("sortie-arrivals-v1/2025-05"))) {
            if (files.filter(p -> p.toString().endsWith(".pending")).count() != 3)
                throw new AssertionError("Pending evidence was lost");
        }
        if (!Files.readString(root.resolve("customquest/10001.json")).startsWith("// retained"))
            throw new AssertionError("Custom text changed");
        System.out.println("RUNTIME_COPY_JAVA_OK records=3 accepted=2 originals_preserved=true");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2)
            throw new IllegalArgumentException("seed|verify plus NEW fixture root required");
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        if (args[0].equals("seed"))
            seed(root);
        else if (args[0].equals("verify"))
            verify(root);
        else
            throw new IllegalArgumentException("Unknown mode");
    }
}
