package logbook.plugin.api;
import java.util.*;
import logbook.bean.*;
import logbook.core.LogBookCoreServices;
/** Explicit projections for supplies, repairs and construction. No configuration or credentials. */
public final class OperationsDataV1 {
    private OperationsDataV1() { }
    public record Supply(Integer id,Integer count) { }
    public record Repair(Integer id,Integer shipId,Integer state,Long completeTime,List<Integer> materials) { }
    public record Construction(Integer dockId,Integer state,Integer createdShipId,Integer completeTime,List<Integer> materials) { }
    public record Snapshot(List<Supply> supplies,List<Repair> repairs,List<Construction> construction) {
        public Snapshot {supplies=List.copyOf(supplies);repairs=List.copyOf(repairs);construction=List.copyOf(construction);}
    }
    private static List<Integer> nullable(Integer... values){return Collections.unmodifiableList(Arrays.asList(values));}
    public static Snapshot snapshot(){return LogBookCoreServices.readGameState(()->{
        var supplies=UseitemCollection.get().getUseitemMap().values().stream().filter(Objects::nonNull).map(x->new Supply(x.getId(),x.getCount())).toList();
        var repairs=NdockCollection.get().getNdockMap().values().stream().filter(Objects::nonNull).map(x->new Repair(x.getId(),x.getShipId(),x.getState(),x.getCompleteTime(),nullable(x.getItem1(),x.getItem2(),x.getItem3(),x.getItem4()))).toList();
        var construction=CreateshipCollection.get().getCreateshipMap().values().stream().filter(Objects::nonNull).map(Createship::getKdock).filter(Objects::nonNull).map(x->new Construction(x.getId(),x.getState(),x.getCreatedShipId(),x.getCompleteTime(),nullable(x.getItem1(),x.getItem2(),x.getItem3(),x.getItem4(),x.getItem5()))).toList();
        return new Snapshot(supplies,repairs,construction);
    });}
}
