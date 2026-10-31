package logbook.api;

import jakarta.json.JsonObject;
import logbook.bean.AppQuestCollection;
import logbook.bean.AppQuestDuration;
import logbook.net.RequestMetaData;
import logbook.net.ResponseMetaData;

/**
 * /kcsapi/api_req_quest/clearitemget
 *
 */
@API("/kcsapi/api_req_quest/clearitemget")
public class ApiReqQuestClearitemget implements APIListenerSpi {

    @Override
    public void accept(JsonObject json, RequestMetaData req, ResponseMetaData res) {
        if (json == null || !(json.get("api_result") instanceof jakarta.json.JsonNumber result)
                || result.bigDecimalValue().compareTo(java.math.BigDecimal.ONE) != 0) return;
        String id = req.getParameter("api_quest_id");
        if (id != null) {
            Integer key = Integer.valueOf(id);
            try {
                new logbook.core.quest.QuestCompletionRecorder(
                    new logbook.core.quest.QuestLedger(java.nio.file.Path.of("config", "quest-ledger")))
                    .record(json, logbook.core.quest.QuestIdentity.resolve(key, AppQuestCollection.get().getQuest().get(key)), java.util.UUID.randomUUID().toString(), java.time.Instant.now());
            } catch (java.io.IOException failure) {
                throw new java.io.UncheckedIOException("任務完了履歴を保存できません。既存の任務情報を保持します。", failure);
            }
            AppQuestCollection.get().getQuest().remove(key);
            AppQuestDuration.get().remove(key);
        }
    }

}
