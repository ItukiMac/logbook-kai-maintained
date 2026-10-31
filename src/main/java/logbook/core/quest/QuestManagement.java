package logbook.core.quest;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import logbook.core.LogBookCoreServices;
import logbook.plugin.api.QuestListV1;
import logbook.core.quest.QuestLedger.*;

public final class QuestManagement {
    private QuestManagement() { }
    public record Row(Key key,String title,State observed,State manual,State effective,boolean conflict,String recordedAt) { }
    public record Table(long revision,List<Row> rows,List<Event> history,String coverage) {
        public Table { rows=List.copyOf(rows);history=List.copyOf(history); }
    }
    public static QuestLedger ledger() { return new QuestLedger(Path.of("config","quest-ledger")); }
    public static Table snapshot() throws java.io.IOException {
        var snapshot=ledger().snapshot();Map<Key,String> catalog=new LinkedHashMap<>();
        try(var scope=LogBookCoreServices.pinDefinitions()) {
            for(String name:scope.snapshot().resourceNames()) {
                var match=java.util.regex.Pattern.compile("logbook/(?:eventquest|quest)/([0-9]+)\\.json").matcher(name);
                if(match.matches()) {int id=Integer.parseInt(match.group(1));catalog.put(new Key(id,"unresolved","unresolved"),"任務 #"+id);}
            }
        }
        for(var quest:QuestListV1.snapshot().quests()) {
            Key key=QuestIdentity.resolve(quest.no(),logbook.bean.AppQuestCollection.get().getQuest().get(quest.no()));
            catalog.put(key,quest.title());
        }
        return project(snapshot,catalog);
    }
    public static Table project(Snapshot snapshot,Map<Key,String> catalog) {
        Map<Key,String> names=new LinkedHashMap<>(catalog);
        for(var event:snapshot.history()) names.putIfAbsent(event.key(),"任務 #"+event.key().questId());
        List<Row> rows=new ArrayList<>();
        for(var item:names.entrySet()) {
            var game=snapshot.latest(item.getKey(),Source.GAME);var manual=snapshot.latest(item.getKey(),Source.MANUAL);
            State observed=game.map(Event::state).orElse(State.UNKNOWN),edited=manual.map(Event::state).orElse(null);
            boolean conflict=game.isPresent()&&manual.isPresent()&&game.get().revision()>manual.get().revision()&&observed!=edited;
            rows.add(new Row(item.getKey(),item.getValue(),observed,edited,edited==null?observed:edited,conflict,
                manual.or(()->game).map(Event::effectiveAt).orElse("")));
        }
        rows.sort(Comparator.comparingInt((Row r)->r.key().questId()).thenComparing(r->r.key().edition()).thenComparing(r->r.key().period()));
        return new Table(snapshot.revision(),rows,snapshot.history(),"同梱定義・観測済み・履歴・手動追加の任務。未登録の全ゲーム任務を網羅した台帳ではありません。unresolved は開催・周期の未照合です。");
    }
    public static Event correct(long revision,Key key,State state,Instant effectiveAt,String reason) throws java.io.IOException {
        return ledger().append(revision,UUID.randomUUID().toString(),key,state,Source.MANUAL,effectiveAt,reason);
    }
}
