package logbook.internal.gui;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Patch explicit form edits into the original tree; never serialize away hidden conditions. */
final class QuestEditorDocument {
    private QuestEditorDocument() { }

    static ObjectMapper mapper() {
        return new ObjectMapper().enable(JsonParser.Feature.ALLOW_COMMENTS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    static Set<Integer> deckIds(String text) {
        if (text == null || text.isBlank()) return null;
        Set<Integer> ids = new LinkedHashSet<>();
        for (String item : text.split(",", -1)) {
            String value = item.trim();
            if (!value.matches("[1-4]")) throw new IllegalArgumentException("艦隊番号は1～4をカンマで区切ってください。");
            if (!ids.add(Integer.valueOf(value))) throw new IllegalArgumentException("艦隊番号が重複しています。");
        }
        return ids;
    }

    static void validate(JsonNode document) throws IOException {
        if (document == null || !document.isObject()) throw new IOException("任務設定はJSONオブジェクトが必要です。");
        for (JsonNode condition : document.path("conditions")) {
            JsonNode arrival = condition.path("arrival");
            if (arrival.isMissingNode()) continue;
            if (!arrival.isBoolean()) throw new IOException("arrivalはtrueまたはfalseが必要です。");
            if (!arrival.booleanValue()) continue;
            JsonNode area = condition.path("area");
            if (!area.isArray() || area.size() != 1 || !area.get(0).isTextual()
                    || !"1-6".equals(area.get(0).textValue())
                    || condition.path("boss").asBoolean() || condition.path("start").asBoolean()
                    || !condition.path("count").isIntegralNumber() || !condition.path("count").canConvertToInt()
                    || condition.path("count").intValue() <= 0)
                throw new IOException("港到達条件は1-6と正の回数が必要です。戦闘条件とは併用できません。");
            for (String key : new String[] {"rank", "cell", "cells", "stype", "missions"})
                if (condition.hasNonNull(key)) throw new IOException("港到達条件へ戦闘条件を追加できません: " + key);
        }
        JsonNode filter = document.path("filter");
        if (!filter.isMissingNode() && !filter.isNull() && !filter.isObject())
            throw new IOException("filterはオブジェクトが必要です。");
        JsonNode ids = filter.path("deckIds");
        if (!ids.isMissingNode() && !ids.isNull()) {
            if (!ids.isArray() || ids.isEmpty()) throw new IOException("deckIdsは空でない艦隊番号の配列が必要です。");
            Set<Integer> seen = new LinkedHashSet<>();
            for (JsonNode id : ids) {
                if (!id.isIntegralNumber() || !id.canConvertToInt() || id.intValue() < 1 || id.intValue() > 4
                        || !seen.add(id.intValue())) throw new IOException("deckIdsは重複のない1～4が必要です。");
            }
        }
        JsonNode noRetreat = filter.path("noRetreat");
        if (!noRetreat.isMissingNode() && !noRetreat.isNull() && !noRetreat.isBoolean())
            throw new IOException("noRetreatはtrueまたはfalseが必要です。");
    }

    static JsonNode merge(JsonNode original, JsonNode before, JsonNode after) throws IOException {
        if (original == null || before == null || after == null)
            throw new IOException("読込時の編集状態がありません。再読込してください。");
        return patch(original, before, after, "");
    }

    private static JsonNode patch(JsonNode original, JsonNode before, JsonNode after, String path) throws IOException {
        if (before.equals(after)) return original.deepCopy();
        if (original.equals(before)) return after.deepCopy();
        if (path.equals("/filter/deckIds") || path.equals("/filter/noRetreat")) return after.deepCopy();
        if (before.isObject() && after.isObject() && (original.isObject() || original.isMissingNode())) {
            ObjectNode output = original.isObject() ? ((ObjectNode) original).deepCopy() : JsonNodeFactory.instance.objectNode();
            Set<String> keys = new LinkedHashSet<>();
            before.fieldNames().forEachRemaining(keys::add);
            after.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                JsonNode result = patch(original.path(key), before.path(key), after.path(key), path + "/" + key);
                if (result.isMissingNode()) output.remove(key); else output.set(key, result);
            }
            return output;
        }
        // Preserve per-row hidden attributes when row counts/positions did not change.
        if (original.isArray() && before.isArray() && after.isArray()
                && original.size() == before.size() && before.size() == after.size()) {
            ArrayNode output = JsonNodeFactory.instance.arrayNode();
            for (int i = 0; i < before.size(); i++)
                output.add(patch(original.path(i), before.path(i), after.path(i), path + "/" + i));
            return output;
        }
        // Explicitly changing a form default that was absent from a sparse source is allowed.
        if (original.isMissingNode()) return after.deepCopy();
        if (after.equals(original)) return original.deepCopy();
        throw new IOException("画面で表現できない既存条件と変更が衝突しています: " + path
                + "。元ファイルは変更しません。");
    }
}
