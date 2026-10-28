package logbook.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import logbook.bean.Maparea;
import logbook.bean.MapareaCollection;
import logbook.bean.Mapinfo;
import logbook.bean.Mapinfo.AirBase;
import logbook.bean.Mapinfo.PlaneInfo;
import logbook.bean.SlotItem;
import logbook.bean.SlotItemCollection;
import logbook.bean.SlotitemMst;
import logbook.bean.SlotitemMstCollection;
import logbook.internal.kancolle.AirBases;
import logbook.plugin.api.AirBaseStatusV1.*;

/** Host adaptation only: no Config.store, game requests, repair or file writes. */
public final class AirBaseStatusService {
    private AirBaseStatusService() { }
    public static Snapshot snapshot() {
        return snapshot(Mapinfo.get().getAirBase(), MapareaCollection.get().getMaparea(),
                SlotItemCollection.get().getSlotitemMap(), SlotitemMstCollection.get().getSlotitemMap());
    }

    /** All mutable inputs are read on capture; returned values share none of them. */
    static Snapshot snapshot(List<AirBase> bases, Map<Integer,Maparea> areas,
            Map<Integer,SlotItem> equipment, Map<Integer,SlotitemMst> masters) {
        List<String> warnings=new ArrayList<>(); List<BaseView> result=new ArrayList<>();
        if(bases==null) return new Snapshot(List.of(),List.of("基地航空隊一覧が未記録"));
        // Copies isolate calculations after capture, not simultaneous legacy writers.
        Map<Integer,SlotItem> itemCopies=new LinkedHashMap<>();
        Map<Integer,SlotitemMst> masterCopies=new LinkedHashMap<>();
        var areaCopies=new LinkedHashMap<Integer,Maparea>();
        areas.forEach((id,a)->{if(a!=null){Maparea c=new Maparea();c.setId(a.getId());c.setName(a.getName());c.setType(a.getType());areaCopies.put(id,c);}});
        var raw=new ArrayList<>(bases);var seen=new HashSet<Key>();var duplicates=new HashSet<Key>();
        for(AirBase base:raw) {
            if(base==null || base.getAreaId()==null || base.getAreaId()<=0 || base.getRid()==null || base.getRid()<=0) {
                warnings.add("基地識別子が不完全");continue;
            }
            AirBase b=copyBase(base);Key key=new Key(b.getAreaId(),b.getRid());
            if(!seen.add(key)){duplicates.add(key);warnings.add("基地識別子が重複: "+key);continue;}
            List<String> notes=new ArrayList<>();Maparea area=areaCopies.get(key.areaId());
            if(area==null)notes.add("海域マスターが未記録");
            Integer action=b.getActionKind();boolean powerKnown=action!=null && action>=0 && action<=4;
            if(!powerKnown)notes.add("行動状態が未記録または不明");
            Integer radius=null,rb=b.getDistance()==null?null:b.getDistance().getBase(),bonus=b.getDistance()==null?null:b.getDistance().getBonus();
            if(rb!=null && bonus!=null && rb>=0 && bonus>=0) {
                try{radius=Math.addExact(rb,bonus);}catch(ArithmeticException e){notes.add("半径の範囲外");}
            } else notes.add("半径が未記録または不正");
            List<PlaneView> planes=new ArrayList<>();
            if(b.getPlaneInfo()==null){notes.add("中隊一覧が未記録");powerKnown=false;}
            else for(PlaneInfo p:b.getPlaneInfo()) {
                if(p==null){notes.add("中隊が未記録");powerKnown=false;continue;}
                Integer id=p.getSlotid();SlotItem item=null;SlotitemMst master=null;
                if(id!=null && id>0) {
                    if(!itemCopies.containsKey(id))itemCopies.put(id,copyItem(equipment.get(id)));
                    item=itemCopies.get(id);
                    if(item!=null && item.getSlotitemId()!=null) {
                        int mid=item.getSlotitemId();
                        if(!masterCopies.containsKey(mid))masterCopies.put(mid,copyMaster(masters.get(mid)));
                        master=masterCopies.get(mid);
                    }
                    if(item==null || !id.equals(item.getId()) || master==null || !item.getSlotitemId().equals(master.getId())) {
                        notes.add("配備装備が不明: "+id);powerKnown=false;
                    }
                }
                if(p.getState()==null || p.getState()<0 || p.getState()>2){notes.add("中隊状態が不明");powerKnown=false;}
                if(p.getCount()!=null && p.getCount()<0){notes.add("稼働数が不正");powerKnown=false;}
                if(p.getMaxCount()!=null && p.getMaxCount()<0)notes.add("定数が不正");
                if(p.getCond()!=null && (p.getCond()<0 || p.getCond()>3))notes.add("疲労状態が不明");
                if(id==null || id>0 && p.getCount()==null || (id<=0 && p.getCount()!=null && p.getCount()>0)) {
                    notes.add("配備または稼働数が未記録");powerKnown=false;
                }
                if(master!=null && (master.getType()==null || master.getType().size()<4
                        || master.getType().stream().anyMatch(java.util.Objects::isNull)
                        || master.getTyku()==null || master.getTyku()<0)) {notes.add("装備計算値が未記録");powerKnown=false;}
                if(item!=null && (item.getAlv()!=null && (item.getAlv()<0 || item.getAlv()>7)
                        || item.getLevel()!=null && (item.getLevel()<0 || item.getLevel()>10))) {notes.add("装備熟練/改修が範囲外");powerKnown=false;}
                String name=id==null ? "未記録" : id<=0 ? "未配備" : master==null ? "不明装備("+id+")" : master.getName();
                planes.add(new PlaneView(p.getSquadronId(),p.getState(),id,p.getCount(),p.getMaxCount(),p.getCond(),
                        master==null?null:master.getId(),name==null?"名称未記録":name,item==null?null:item.getLevel(),item==null?null:item.getAlv()));
            }
            Integer power=null;
            if(powerKnown) {
                try {power=AirBases.airSuperiority(b,itemCopies,masterCopies);if(power<0){power=null;notes.add("制空値が範囲外");}}
                catch(NullPointerException | IndexOutOfBoundsException | ArithmeticException e){notes.add("制空計算に必要な値が不完全");}
            }
            result.add(new BaseView(key,area==null?"不明海域("+key.areaId()+")":area.getName(),area==null?null:area.getType(),
                    b.getName()==null?"名称未記録":b.getName(),action,rb,bonus,radius,power,planes,notes));
        }
        result.removeIf(b->duplicates.contains(b.key()));
        result.sort(Comparator.comparingInt((BaseView b)->b.key().areaId()).thenComparingInt(b->b.key().baseId()));
        return new Snapshot(result,warnings);
    }
    private static AirBase copyBase(AirBase b) {
        AirBase c=new AirBase();c.setAreaId(b.getAreaId());c.setRid(b.getRid());c.setName(b.getName());c.setActionKind(b.getActionKind());
        if(b.getDistance()!=null){var d=new Mapinfo.Distance();d.setBase(b.getDistance().getBase());d.setBonus(b.getDistance().getBonus());c.setDistance(d);}
        if(b.getPlaneInfo()!=null){var ps=new ArrayList<PlaneInfo>();for(PlaneInfo p:new ArrayList<>(b.getPlaneInfo())) {
            if(p==null){ps.add(null);continue;}var x=new PlaneInfo();x.setSquadronId(p.getSquadronId());x.setState(p.getState());x.setSlotid(p.getSlotid());
            x.setCount(p.getCount());x.setMaxCount(p.getMaxCount());x.setCond(p.getCond());ps.add(x);}c.setPlaneInfo(ps);}
        return c;
    }
    private static SlotItem copyItem(SlotItem i) {
        if(i==null)return null;SlotItem c=new SlotItem();c.setId(i.getId());c.setSlotitemId(i.getSlotitemId());c.setLevel(i.getLevel());c.setAlv(i.getAlv());return c;
    }
    private static SlotitemMst copyMaster(SlotitemMst m) {
        if(m==null)return null;SlotitemMst c=new SlotitemMst();c.setId(m.getId());c.setName(m.getName());
        if(m.getType()!=null)c.setType(new ArrayList<>(m.getType()));
        c.setTyku(m.getTyku());c.setHouk(m.getHouk());c.setHoum(m.getHoum());c.setSaku(m.getSaku());return c;
    }
}
