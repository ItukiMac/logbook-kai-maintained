package logbook.internal.gui;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Patch explicit form edits into the original tree; never serialize away hidden conditions. */
final class QuestEditorDocument {
    private QuestEditorDocument() { }

    static ObjectMapper mapper() { return logbook.core.QuestDocumentValidation.mapper(); }
    static JsonNode parse(byte[] input) throws IOException { return logbook.core.QuestDocumentValidation.parse(input); }
    static JsonNode parse(String input) throws IOException { return logbook.core.QuestDocumentValidation.parse(input); }

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

    static void validate(JsonNode document) throws IOException { logbook.core.QuestDocumentValidation.validate(document); }

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
            before.propertyNames().iterator().forEachRemaining(keys::add);
            after.propertyNames().iterator().forEachRemaining(keys::add);
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
