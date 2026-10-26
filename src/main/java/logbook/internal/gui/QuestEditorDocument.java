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
