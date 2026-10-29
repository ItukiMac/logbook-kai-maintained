package logbook.core;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Existing editor JSON rules, shared without a dependency on JavaFX or GUI controllers. */
public final class QuestDocumentValidation {
    private QuestDocumentValidation() { }
    public static ObjectMapper mapper() {
        return logbook.internal.json.JsonMappers.builder().enable(tools.jackson.core.json.JsonReadFeature.ALLOW_JAVA_COMMENTS).enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    }

/** Stable editor parsing boundary independent of the JSON engine exception hierarchy. */
public static JsonNode parse(byte[] input) throws IOException {
    try { return mapper().readTree(input); }
    catch (tools.jackson.core.JacksonException error) {
        throw new IOException("任務設定のJSONを読み込めません。元ファイルは変更しません。", error);
    }
}

public static JsonNode parse(String input) throws IOException {
    return parse(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
}

    public static void validate(JsonNode document) throws IOException {
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

}
