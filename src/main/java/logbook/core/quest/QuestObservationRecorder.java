package logbook.core.quest;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.UUID;
import logbook.bean.*;
import logbook.core.quest.QuestLedger.*;
public final class QuestObservationRecorder {
    private QuestObservationRecorder() { }
    public static void record(QuestList list) {
        if(list.getList()==null)return;
        var ledger=QuestManagement.ledger();
        for(var q:list.getList()) {
            if(q==null||q.getNo()==null||q.getState()==null)continue;
            State state=switch(q.getState()){case 1->State.AVAILABLE;case 2->State.ACCEPTED;case 3->State.ACHIEVED_UNCLAIMED;default->State.UNKNOWN;};
            var key=QuestIdentity.resolve(q.getNo(),AppQuestCollection.get().getQuest().get(q.getNo()));
            try {
                var snapshot=ledger.snapshot();
                if(snapshot.latest(key,Source.GAME).map(e->e.state()==state).orElse(false))continue;
                ledger.append(snapshot.revision(),UUID.randomUUID().toString(),key,state,Source.GAME,
                    q.getObservedAt()==null?Instant.now():q.getObservedAt(),"任務一覧: "+q.getTitle());
            } catch(java.io.IOException failure){throw new UncheckedIOException("任務観測履歴を保存できません",failure);}
        }
    }
}
