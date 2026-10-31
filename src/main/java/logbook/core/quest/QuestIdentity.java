package logbook.core.quest;
import logbook.bean.*;
import logbook.core.*;
import logbook.core.quest.QuestLedger.Key;
public final class QuestIdentity {
    private QuestIdentity() { }
    public static Key resolve(int id,AppQuest app) {
        if(app==null||app.getQuest()==null)return new Key(id,"unresolved","unresolved");
        var condition=AppQuestCondition.loadFromResource(app.getQuest());
        String edition="unresolved";
        if(condition!=null && condition.isRuleUsableFor(app)) {
            var selected=condition.getSeasonalSelection();
            if(selected!=null && selected.status()==SeasonalQuestRuleService.Status.SELECTED)edition=selected.editionId();
            else if(selected==null && LogBookCoreServices.getQuestResource(id,true)!=null)edition="regular";
        }
        String expiry=app.getExpire();
        String period=expiry==null?"unresolved":expiry.startsWith("9999-")?"once":expiry;
        return new Key(id,edition,period);
    }
}
