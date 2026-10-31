package logbook.core;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import logbook.bean.AppQuestCondition;
import logbook.bean.ArsenalQuestSetting;
import logbook.internal.json.JsonMappers;
import logbook.internal.util.QuestDocumentStore;
import logbook.plugin.api.QuestLocalFilesV1.*;

/** Local read adapter only: reuse bounded/link-refusing read, never Store.save/remove or Config.store. */
public final class QuestLocalFilesService {
    private QuestLocalFilesService() {
    }

    public static Snapshot snapshot(List<Integer> ids) {
        return capture(Path.of("customquest"), Path.of("arsenalquest"), ids);
    }

    public static Note note(int id) {
        if (id <= 0)
            throw new IllegalArgumentException("Positive quest identity required");
        return note(Path.of("arsenalquest").resolve(id + ".json"), id);
    }

    static Snapshot capture(Path custom, Path arsenal, List<Integer> ids) {
        Objects.requireNonNull(custom);
        Objects.requireNonNull(arsenal);
        Objects.requireNonNull(ids);
        // Validate the whole request before reading any source.
        var unique = new TreeSet<Integer>();
        for (Integer id : ids) {
            if (id == null || id <= 0)
                throw new IllegalArgumentException("Positive quest identity required");
            unique.add(id);
        }
        var rows = new ArrayList<Custom>();
        for (int id = 10001; id <= 10005; id++)
            rows.add(custom(custom.resolve(id + ".json"), id));
        var notes = new LinkedHashMap<Integer, Note>();
        for (int id : unique)
            notes.put(id, note(arsenal.resolve(id + ".json"), id));
        return new Snapshot(rows, notes);
    }

    private static FileInfo info(Status status, byte[] raw, String diagnostic) {
        String sha = null;
        if (raw != null)
            try {
                sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
            } catch (NoSuchAlgorithmException impossible) {
                throw new AssertionError(impossible);
            }
        return new FileInfo(status, sha, diagnostic);
    }

    private static Custom custom(Path path, int id) {
        String fallback = "カスタム任務" + (id - 10000);
        byte[] raw;
        try {
            raw = QuestDocumentStore.read(path);
        } catch (IOException | SecurityException refused) {
            return new Custom(id, fallback,
                    info(Status.UNREADABLE, null, "任務定義を読み取れません（リンク・形式・サイズ・権限を確認）。元ファイルは保持しています。"));
        }
        if (raw == null)
            return new Custom(id, fallback, info(Status.MISSING, null, ""));
        try {
            // Preserve the former GUI's typed reader and comment support, including unknown-field refusal.
            var mapper = JsonMappers.builder().enable(tools.jackson.core.json.JsonReadFeature.ALLOW_JAVA_COMMENTS)
                    .build();
            var condition = mapper.readValue(raw, AppQuestCondition.class);
            if (condition == null)
                throw new IllegalArgumentException("Null definition");
            String name = condition.getCustomName();
            return new Custom(id, name == null || name.trim().isEmpty() ? fallback : name, info(Status.VALID, raw, ""));
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException invalid) {
            return new Custom(id, fallback, info(Status.INVALID, raw, "任務定義を表示用に読み取れません。元ファイルは保持しています。"));
        }
    }

    private static Note note(Path path, int id) {
        byte[] raw;
        try {
            raw = QuestDocumentStore.read(path);
        } catch (IOException | SecurityException refused) {
            return new Note(id, "", false,
                    info(Status.UNREADABLE, null, "工廠設定を読み取れません（リンク・形式・サイズ・権限を確認）。元ファイルは保持しています。"));
        }
        if (raw == null)
            return new Note(id, "", false, info(Status.MISSING, null, ""));
        try {
            var setting = JsonMappers.builder().build().readValue(raw, ArsenalQuestSetting.class);
            if (setting == null)
                throw new IllegalArgumentException("Null setting");
            return new Note(id, Objects.toString(setting.getComment(), ""), setting.isDiscard(),
                    info(Status.VALID, raw, ""));
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException invalid) {
            return new Note(id, "", false, info(Status.INVALID, raw, "工廠設定を表示用に読み取れません。元ファイルは保持しています。"));
        }
    }
}
