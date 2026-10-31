package logbook.features.sharing;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import tools.jackson.databind.*;
import logbook.bean.AppQuest;
import logbook.bean.QuestList;
import logbook.core.LogBookCoreServices;

/** Compiles on legacy hosts; actively proves pinning when the new contract exists. */
public final class QuestGenerationTest {
    private static final ObjectMapper JSON = tools.jackson.databind.json.JsonMapper.builderWithJackson2Defaults()
            .build();

    private static void check(boolean ok, String text) {
        if (!ok)
            throw new AssertionError(text);
    }

    private static Path stage(Path temp, String id, int count) throws Exception {
        Path root = temp.resolve(id), dir = root.resolve("generations").resolve(id.repeat(64));
        Path file = dir.resolve("definitions/quest/1045.json");
        Files.createDirectories(file.getParent());
        byte[] bytes = ("{\"resetType\":\"単発\",\"conditions\":[{\"count\":" + count + "}]}")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(file, bytes);
        var item = Map.of("path", "definitions/quest/1045.json", "bytes", bytes.length, "sha256",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        JSON.writeValue(dir.resolve("manifest.json").toFile(),
                Map.of("format", "logbook-data-pack-v1", "files", List.of(item)));
        JSON.writeValue(root.resolve("active.json").toFile(),
                Map.of("schema", "logbook-active-data-v1", "generation", id.repeat(64)));
        return root;
    }

    private static AppQuest quest() {
        QuestList.Quest q = new QuestList.Quest();
        q.setNo(1045);
        q.setState(1);
        q.setType(1);
        q.setTitle("isolated generation fixture");
        q.setDetail("");
        AppQuest app = new AppQuest();
        app.setNo(1045);
        app.setQuest(q);
        return app;
    }

    private static JsonNode export(Collection<AppQuest> values) throws Exception {
        return JSON.readTree(QuestExporter.encode(QuestExporter.build(new QuestStore(), values, true)));
    }

    private static void coherent(JsonNode data) {
        var metadata = data.path("definition_snapshot");
        check(metadata.path("supported").asBoolean() && metadata.path("initialized").asBoolean(),
                "new contract initialized");
        String generation = metadata.path("generation").asText();
        int count = generation.equals("a".repeat(64)) ? 1 : generation.equals("b".repeat(64)) ? 2 : -1;
        check(count > 0, "known generation");
        for (JsonNode q : data.path("quests"))
            check(q.path("local_quest_condition").path("objectives").get(0).path("required_count").asInt() == count,
                    "each condition matches the one exported generation");
    }

    public static void main(String[] args) throws Exception {
        Class<?> runtime;
        try {
            runtime = Class.forName("logbook.core.GameDefinitionRuntime");
        } catch (ClassNotFoundException legacy) {
            JsonNode data = export(List.of(quest()));
            check(!data.path("definition_snapshot").path("supported").asBoolean(),
                    "legacy host explicitly marked unsupported");
            check(!JSON.readTree(QuestExporter.json(new QuestStore())).path("host_state_observation").path("supported")
                    .asBoolean(), "legacy state fallback");
            System.out.println("QuestGeneration legacy host compatibility PASS (no generation guarantee claimed)");
            return;
        }
        String property = "logbook.data.root";
        // Resolve the production constant rather than assuming a property spelling.
        var rootProperty = Class.forName("logbook.core.ExternalQuestDefinitions").getDeclaredField("ROOT_PROPERTY");
        rootProperty.setAccessible(true);
        property = (String) rootProperty.get(null);
        String previous = System.getProperty(property);
        System.clearProperty(property);
        Path temp = Files.createTempDirectory("kqs-generation-fixture-");
        try {
            runtime.getMethod("initialize").invoke(null);
            var reload = runtime.getMethod("reload", Path.class);
            Path a = stage(temp, "a", 1), b = stage(temp, "b", 2);
            reload.invoke(null, a);
            try (AutoCloseable pin = (AutoCloseable) LogBookCoreServices.class.getMethod("pinDefinitions")
                    .invoke(null)) {
                reload.invoke(null, b);
                var held = export(List.of(quest()));
                coherent(held);
                check(held.path("definition_snapshot").path("generation").asText().equals("a".repeat(64)),
                        "outer pin retained A after B publication");
            }
            coherent(export(List.of(quest())));
            var many = new ArrayList<AppQuest>();
            for (int i = 0; i < 100; i++)
                many.add(quest());
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var writer = executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < 30; i++)
                        reload.invoke(null, i % 2 == 0 ? a : b);
                    return null;
                });
                start.countDown();
                for (int n = 0; n < 20; n++)
                    coherent(export(many));
                writer.get(20, TimeUnit.SECONDS);
            }
            JsonNode observed = JSON.readTree(QuestExporter.json(new QuestStore()));
            check(observed.path("host_state_observation").path("supported").asBoolean(), "new state read adapter");
            check(!observed.toString().contains("api_token"), "no authentication data");
            System.out.println(
                    "QuestGeneration tests PASS: nested pin, 20 whole exports/2000 conditions during 30 reloads, state adapter");
        } finally {
            var stop = runtime.getDeclaredMethod("stopForTest");
            stop.setAccessible(true);
            stop.invoke(null);
            if (previous == null)
                System.clearProperty(property);
            else
                System.setProperty(property, previous);
            // Only the temporary fixture created above is cleaned; source and installations are untouched.
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
    }
}
