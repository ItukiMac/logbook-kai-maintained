package logbook.core.quest;

import java.io.IOException;
import java.time.Instant;
import jakarta.json.JsonObject;
import logbook.core.quest.QuestLedger.*;

/** Records claim evidence without guessing a seasonal edition or a recurrence. */
public final class QuestCompletionRecorder {
    private final QuestLedger ledger;
    public QuestCompletionRecorder(QuestLedger ledger) { this.ledger = ledger; }
    public boolean record(JsonObject response, int questId, String receiptId, Instant receivedAt) throws IOException {
        if (response == null || !(response.get("api_result") instanceof jakarta.json.JsonNumber result)
                || result.bigDecimalValue().compareTo(java.math.BigDecimal.ONE) != 0) return false;
        // Receipt identity is intentionally unresolved until a catalog binding is verified.
        // Retaining the raw success is preferable to assigning it to the wrong reused ID.
        Key key = new Key(questId,"unresolved","unresolved");
        synchronized (ledger) {
            ledger.append(ledger.snapshot().revision(), receiptId, key, State.CLAIMED,
                    Source.GAME, receivedAt, "Successful api_req_quest/clearitemget; edition/period unresolved");
        }
        return true;
    }
}
