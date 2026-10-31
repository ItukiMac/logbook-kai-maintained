package logbook.api;
import jakarta.json.JsonObject;
import logbook.bean.*;
import logbook.core.quest.*;
import logbook.net.*;
@API("/kcsapi/api_req_quest/start")
public final class ApiReqQuestStart implements APIListenerSpi {
    @Override public void accept(JsonObject json,RequestMetaData request,ResponseMetaData response) {
        String parameter=request.getParameter("api_quest_id");if(parameter==null)return;
        int id=Integer.parseInt(parameter);
        if(!QuestObservationRecorder.transition(json,id,QuestLedger.State.ACCEPTED))return;
        var quest=AppQuestCollection.get().getQuest().get(id);
        if(quest!=null&&quest.getQuest()!=null){quest.getQuest().setState(2);quest.setActive(true);AppQuestDuration.get().set(quest);}
    }
}
