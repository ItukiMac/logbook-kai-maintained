package logbook.core;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.*;
import logbook.bean.*;
import logbook.internal.logger.LoggerHolder;
import logbook.plugin.api.*;

/** Serializes host API mutation and publishes a deep immutable result after all synchronous handlers. */
public final class GameStateService implements GameStateView {
    private static final GameStateService SHARED=new GameStateService();
    public static GameStateService shared(){return SHARED;}
    private volatile GameStateSnapshot current=new GameStateSnapshot(0,Instant.EPOCH,"","runtime-not-started",false,List.of(),List.of(),List.of(),Map.of());
    private final CopyOnWriteArrayList<Subscription> listeners=new CopyOnWriteArrayList<>();
    private boolean updating;
    @Override public GameStateSnapshot snapshot(){return current;}
    @Override public AutoCloseable subscribe(Consumer<GameStateSnapshot> callback){
        Subscription s=new Subscription(Objects.requireNonNull(callback));listeners.add(s);return s;
    }
    /** Use for legacy read adapters; never hand mutable beans to asynchronous work from the supplier. */
    public synchronized <T> T read(Supplier<T> query){return Objects.requireNonNull(query).get();}
    public synchronized void applyApiUpdate(String uri,BooleanSupplier handlers){
        Objects.requireNonNull(uri);Objects.requireNonNull(handlers);
        if(updating)throw new IllegalStateException("Reentrant API mutation is not allowed");
        updating=true;
        try(var scope=LogBookCoreServices.pinDefinitions()) {
            boolean success=handlers.getAsBoolean();
            GameStateSnapshot next=capture(current.sequence()+1,uri,scope.snapshot().generation(),success);
            current=next;
            for(Subscription s:listeners)if(s.active)try{s.callback.accept(next);}catch(RuntimeException e){LoggerHolder.get().warn("ゲーム状態の通知先で例外",e);}
        } finally {updating=false;}
    }
    private static <T> List<T> copy(List<T> value){return value==null?List.of():List.copyOf(value);}
    static GameStateSnapshot capture(long sequence,String api,String generation,boolean success){
        List<GameStateSnapshot.ShipState> ships=new ArrayList<>();
        for(var ship:new ArrayList<>(ShipCollection.get().getShipMap().values()))if(ship!=null)ships.add(new GameStateSnapshot.ShipState(
            ship.getId(),ship.getShipId(),ship.getLv(),ship.getNowhp(),ship.getMaxhp(),ship.getCond(),copy(ship.getSlot())));
        List<GameStateSnapshot.FleetState> fleets=new ArrayList<>();
        for(var fleet:new ArrayList<>(DeckPortCollection.get().getDeckPortMap().values()))if(fleet!=null)fleets.add(new GameStateSnapshot.FleetState(fleet.getId(),copy(fleet.getShip()),copy(fleet.getMission())));
        List<GameStateSnapshot.QuestState> quests=new ArrayList<>();
        for(var app:AppQuestCollection.get().getQuest().values())if(app!=null && app.getQuest()!=null){
            var q=app.getQuest();quests.add(new GameStateSnapshot.QuestState(q.getNo(),q.getTitle(),q.getDetail(),q.getCategory(),q.getType(),q.getState(),q.getProgressFlag(),copy(q.getGetMaterial()),q.getBonusFlag(),q.getInvalidFlag(),app.getExpire()));
        }
        Map<Integer,Integer> materials=new LinkedHashMap<>();
        var data=AppCondition.get().getMaterial();
        if(data!=null)data.forEach((id,m)->{if(id!=null && m!=null && m.getValue()!=null)materials.put(id,m.getValue());});
        return new GameStateSnapshot(sequence,Instant.now(),api,generation,success,ships,fleets,quests,materials);
    }
    private final class Subscription implements AutoCloseable{
        final Consumer<GameStateSnapshot> callback;volatile boolean active=true;
        Subscription(Consumer<GameStateSnapshot> callback){this.callback=callback;}
        public void close(){active=false;listeners.remove(this);}
    }
}
