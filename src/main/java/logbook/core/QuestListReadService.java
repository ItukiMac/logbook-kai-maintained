package logbook.core;

import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import logbook.bean.AppQuest;
import logbook.bean.AppQuestCollection;
import logbook.plugin.api.QuestListV1;
import logbook.plugin.api.QuestListV1.Listing;
import logbook.plugin.api.QuestListV1.Quest;

/**
 * Host adapter only. Snapshot/copy/toLegacy never recompute timing. Progress input
 * may derive a local custom deadline through the existing resolver, without
 * changing observations, collection membership or saved configuration.
 */
public final class QuestListReadService {
    private QuestListReadService() {
    }

    public static Listing snapshot() {
        return LogBookCoreServices.readGameState(() -> capture(AppQuestCollection.get().getQuest()));
    }

    public static void refreshExpiredQuests() {
        GameStateService.shared().applyLocalUpdate("quest-expiry", () -> {
            var collection = AppQuestCollection.get();
            var before = new TreeMap<>(collection.getQuest());
            collection.update();
            return !before.equals(collection.getQuest());
        }, Boolean::booleanValue);
    }

    static Listing capture(Map<Integer, AppQuest> source) {
        Objects.requireNonNull(source, "quest collection");
        var rows = new ArrayList<Quest>();
        var problems = new ArrayList<String>();
        // Normal sources are ConcurrentSkipListMaps. This copy avoids later membership reads.
        for (var entry : new TreeMap<>(source).entrySet()) {
            int key = entry.getKey();
            if (QuestListV1.isCustomId(key))
                continue; // Display projection, NOT state cleanup.
            AppQuest value = entry.getValue();
            try {
                Quest row = copy(value);
                if (row.no() != key)
                    throw new IllegalArgumentException("Map key differs from quest identity");
                rows.add(row);
            } catch (IllegalArgumentException | NullPointerException incomplete) {
                problems.add("Incomplete or inconsistent quest identity: " + key);
            }
        }
        return new Listing(rows, problems);
    }

    /** Compatibility input adapter: identity must be present and consistent. */
    public static Quest copy(AppQuest value) {
        Objects.requireNonNull(value, "quest");
        var raw = Objects.requireNonNull(value.getQuest(), "observed quest");
        Integer id = value.getNo();
        if (id == null || id <= 0 || !id.equals(raw.getNo()))
            throw new IllegalArgumentException("Inconsistent quest identity");
        return new Quest(id, raw.getCategory(), raw.getType(), raw.getState(),
                raw.getTitle(), raw.getDetail(), raw.getGetMaterial(), raw.getBonusFlag(),
                raw.getProgressFlag(), raw.getInvalidFlag(), raw.getObservedAt(),
                value.getExpire(), value.isActive());
    }

    /** Fresh private input for the legacy progress renderer; never creates a live observation. */
    public static AppQuest toProgressInput(Quest row) {
        AppQuest copy = toLegacy(row);
        // Local custom presentation has no saved expiry. At progress opening only,
        // reuse the existing definition-aware reset calculation; never fabricate
        // observedAt, change the immutable row, or save this derived input.
        if (QuestListV1.isCustomId(row.no()) && row.expire() == null && row.observedAt() == null) {
            copy.setExpire(AppQuest.toAppQuest(copy.getQuest()).getExpire());
        }
        return copy;
    }

    /** Pure detached compatibility copy; null timing remains null. */
    public static AppQuest toLegacy(Quest row) {
        Objects.requireNonNull(row, "quest snapshot");
        var raw = new logbook.bean.QuestList.Quest();
        raw.setNo(row.no());
        raw.setCategory(row.category());
        raw.setType(row.type());
        raw.setState(row.state());
        raw.setTitle(row.title());
        raw.setDetail(row.detail());
        raw.setGetMaterial(row.materials() == null ? null : new ArrayList<>(row.materials()));
        raw.setBonusFlag(row.bonusFlag());
        raw.setProgressFlag(row.progressFlag());
        raw.setInvalidFlag(row.invalidFlag());
        raw.setObservedAt(row.observedAt());
        var result = new AppQuest();
        result.setNo(row.no());
        result.setQuest(raw);
        result.setExpire(row.expire());
        result.setActive(row.active());
        return result;
    }
}
