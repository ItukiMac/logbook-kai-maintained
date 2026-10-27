from pathlib import Path
import json

changed=[]
def new(path,content):
    p=Path(path);assert not p.exists(),path
    p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content,encoding='utf8');changed.append(path)

new('src/main/java/logbook/plugin/api/EquipmentDataV1.java',r'''package logbook.plugin.api;

import java.util.List;
import java.util.Map;
import logbook.core.EquipmentDataService;

/**
 * Read-only equipment inventory contract. Values and containers are detached,
 * immutable copies. Capture is not an atomic transaction with legacy writers;
 * separate queries can observe different game states. No game/config/file write
 * operation or mutable bean/JavaFX type is exposed by this data contract.
 */
public final class EquipmentDataV1 {
    private EquipmentDataV1() { }
    public static int version() { return 1; }
    public static Inventory inventory() { return EquipmentDataService.inventory(); }
    public static List<Aircraft> aircraft() { return EquipmentDataService.aircraft(); }
    public static Map<String, List<Category>> categories() { return EquipmentDataService.categories(); }
    public static String itemName(int masterId) { return EquipmentDataService.itemName(masterId); }

    public record Inventory(List<Definition> definitions, List<Owned> owned, List<String> warnings) {
        public Inventory {
            definitions = List.copyOf(definitions);
            owned = List.copyOf(owned);
            warnings = List.copyOf(warnings);
        }
    }
    public record Category(int id, String name) {
        public int getId() { return id; }
        public String getName() { return name; }
    }
    public record Owner(int id, int masterId, String displayName) { }
    public record Owned(int id, int masterId, Integer alv, Integer level, boolean locked, Owner owner) {
        public int getId() { return id; }
        public int getSlotitemId() { return masterId; }
        public Integer getAlv() { return alv; }
        public Integer getLevel() { return level; }
        public boolean getLocked() { return locked; }
    }
    public record Definition(int id, int type2, int type3, String name, String type, int count,
            int houg, int houm, int leng, int luck, int houk, int baku, int raig,
            int saku, int tais, int tyku, int souk) { }
    public record Aircraft(int id, int type2, int type3, String name, String type,
            int alv, int level, int seiku, int interceptSeiku, int distance,
            int distanceTaiteichan, int distanceCatalina, int cost,
            int tyku, int houm, int houk, int raig, int baku, int tais, int saku) { }
}
''')

new('src/main/java/logbook/core/EquipmentDataService.java',r'''package logbook.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import logbook.bean.Ship;
import logbook.bean.ShipCollection;
import logbook.bean.SlotItem;
import logbook.bean.SlotItemCollection;
import logbook.bean.SlotitemMst;
import logbook.bean.SlotitemMstCollection;
import logbook.bean.SlotitemEquiptypeCollection;
import logbook.constants.SlotItemType;
import logbook.internal.kancolle.AirBases;
import logbook.internal.kancolle.Items;
import logbook.internal.kancolle.Ships;
import logbook.internal.logger.LoggerHolder;
import logbook.plugin.api.EquipmentDataV1.*;

/** Host-only adaptation of existing beans/calculations. It does not invoke their writers. */
public final class EquipmentDataService {
    private EquipmentDataService() { }

    public static Inventory inventory() {
        var ownerIndex = owners(new ArrayList<>(ShipCollection.get().getShipMap().values()), Ships::toName);
        List<Owned> owned = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (SlotItem item : new ArrayList<>(SlotItemCollection.get().getSlotitemMap().values())) {
            if (item == null || item.getId() == null || item.getSlotitemId() == null) {
                warnings.add("Incomplete owned equipment identity");
                continue;
            }
            owned.add(copyOwned(item, ownerIndex.get(item.getId())));
        }
        Map<Integer, Integer> counts = counts(owned);
        var masters = new HashMap<>(SlotitemMstCollection.get().getSlotitemMap());
        var types = new HashMap<>(SlotitemEquiptypeCollection.get().getEquiptypeMap());
        List<Definition> definitions = new ArrayList<>();
        for (var entry : counts.entrySet()) {
            SlotitemMst master = masters.get(entry.getKey());
            if (master == null) {
                warnings.add("Missing equipment master: " + entry.getKey());
                continue; // Keep the owned instance; do not invent stats for an unknown master.
            }
            try {
                int category = master.getType().get(2);
                var type = types.get(category);
                if (type == null) warnings.add("Missing equipment category: " + category);
                definitions.add(copyDefinition(master, type == null ? "不明(" + category + ")" : type.getName(), entry.getValue()));
            } catch (RuntimeException incomplete) {
                warnings.add("Incomplete equipment master: " + entry.getKey());
            }
        }
        if (!warnings.isEmpty()) LoggerHolder.get().warn("Equipment inventory snapshot: " + warnings);
        return new Inventory(definitions, owned, warnings);
    }

    static Map<Integer, Integer> counts(List<Owned> values) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (Owned value : values) counts.merge(value.masterId(), 1, Integer::sum);
        return counts;
    }

    /** Preserve the former first-observed owner policy, including the expansion slot. */
    static Map<Integer, Owner> owners(Collection<Ship> ships, Function<Ship, String> names) {
        Map<Integer, Owner> result = new HashMap<>();
        for (Ship ship : ships) {
            if (ship == null || ship.getId() == null || ship.getShipId() == null) continue;
            Owner owner = new Owner(ship.getId(), ship.getShipId(), names.apply(ship));
            if (ship.getSlot() != null) {
                for (Integer id : new ArrayList<>(ship.getSlot()))
                    if (id != null && id > 0) result.putIfAbsent(id, owner);
            }
            Integer extra = ship.getSlotEx();
            if (extra != null && extra > 0) result.putIfAbsent(extra, owner);
        }
        return result;
    }

    public static Owned copyOwned(SlotItem value, Owner owner) {
        Objects.requireNonNull(value);
        return new Owned(value.getId(), value.getSlotitemId(), value.getAlv(), value.getLevel(),
                Boolean.TRUE.equals(value.getLocked()), owner);
    }

    public static Definition copyDefinition(SlotitemMst master, String type, int count) {
        Objects.requireNonNull(master);
        return new Definition(master.getId(), master.getType().get(2), master.getType().get(3),
                master.getName(), type, count, master.getHoug(), master.getHoum(), master.getLeng(),
                master.getLuck(), master.getHouk(), master.getBaku(), master.getRaig(), master.getSaku(),
                master.getTais(), master.getTyku(), master.getSouk());
    }

    public static List<Aircraft> aircraft() {
        List<Aircraft> result = new ArrayList<>();
        for (SlotItem item : new ArrayList<>(SlotItemCollection.get().getSlotitemMap().values())) {
            if (item == null) continue;
            SlotitemMst master = Items.slotitemMst(item).orElse(null);
            if (master == null || !Items.isAircraft(master) || master.getDistance() == null || master.getDistance() == 0) continue;
            // Reuse the existing domain calculations. No independent formula is introduced.
            int onslot = Items.isCombatAircraft(master) ? 18 : 4;
            int radius = master.getDistance();
            var type = SlotitemEquiptypeCollection.get().getEquiptypeMap().get(master.getType().get(2));
            boolean interceptor = master.is(SlotItemType.局地戦闘機);
            result.add(new Aircraft(master.getId(), master.getType().get(2), master.getType().get(3),
                    master.getName(), type == null ? "不明(" + master.getType().get(2) + ")" : type.getName(),
                    item.getAlv() == null ? 0 : item.getAlv(), item.getLevel() == null ? 0 : item.getLevel(),
                    AirBases.airSuperiority(item, onslot, false), AirBases.airSuperiority(item, onslot, true),
                    radius, radius + AirBases.distanceAdditional(radius, 20), radius + AirBases.distanceAdditional(radius, 10),
                    master.getCost() == null ? 0 : master.getCost() * onslot, master.getTyku(),
                    interceptor ? master.getHoum() : 0, interceptor ? master.getHouk() : 0,
                    master.getRaig(), master.getBaku(), master.getTais(), master.getSaku()));
        }
        return List.copyOf(result);
    }

    public static Map<String, List<Category>> categories() {
        Map<String, List<Category>> result = new LinkedHashMap<>();
        Items.getCategories().forEach((name, types) -> result.put(name, types.stream()
                .filter(Objects::nonNull).map(t -> new Category(t.getId(), t.getName())).toList()));
        return Collections.unmodifiableMap(result);
    }

    public static String itemName(int masterId) {
        SlotitemMst master = SlotitemMstCollection.get().getSlotitemMap().get(masterId);
        return master == null ? "不明(" + masterId + ")" : master.getName();
    }
}
''')

new('src/main/java/logbook/plugin/api/EquipmentImagesV1.java',r'''package logbook.plugin.api;

import javafx.application.Platform;
import javafx.scene.image.Image;
import logbook.bean.ShipCollection;
import logbook.bean.SlotitemMstCollection;
import logbook.internal.kancolle.Items;
import logbook.internal.kancolle.Ships;

/** Host-rendered visual resources. Images use the current host state, not a frozen inventory transaction. */
public final class EquipmentImagesV1 {
    private EquipmentImagesV1() { }
    public static int version() { return 1; }
    private static void checkThread() {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("Equipment images require the JavaFX thread");
    }
    public static Image item(int masterId) {
        checkThread();
        var master = SlotitemMstCollection.get().getSlotitemMap().get(masterId);
        return master == null ? null : Items.itemImage(master);
    }
    public static Image owner(Integer shipId) {
        checkThread();
        var ship = shipId == null ? null : ShipCollection.get().getShipMap().get(shipId);
        return Ships.shipWithItemImage(ship);
    }
}
''')

new('src/main/java/logbook/internal/gui/EquipmentProjection.java',r'''package logbook.internal.gui;

import logbook.plugin.api.EquipmentDataV1;

/** New screen-owned copies; legacy Item/AirBaseItem APIs remain available to existing callers. */
public final class EquipmentProjection {
    private EquipmentProjection() { }
    public static Item item(EquipmentDataV1.Definition row) {
        Item value = new Item();
        value.setId(row.id()); value.setType2(row.type2()); value.setType3(row.type3());
        value.setName(row.name()); value.setType(row.type()); value.setCount(row.count());
        value.setHoug(row.houg()); value.setHoum(row.houm()); value.setLeng(row.leng());
        value.setLuck(row.luck()); value.setHouk(row.houk()); value.setBaku(row.baku());
        value.setRaig(row.raig()); value.setSaku(row.saku()); value.setTais(row.tais());
        value.setTyku(row.tyku()); value.setSouk(row.souk());
        return value;
    }
    public static AirBaseItem aircraft(EquipmentDataV1.Aircraft row) {
        AirBaseItem value = new AirBaseItem();
        value.setId(row.id()); value.setType2(row.type2()); value.setType3(row.type3());
        value.setName(row.name()); value.setType(row.type()); value.setAlv(row.alv()); value.setLevel(row.level());
        value.setCount(1); value.setSeiku(row.seiku()); value.setInterceptSeiku(row.interceptSeiku());
        value.setDistance(row.distance()); value.setDistanceTaiteichan(row.distanceTaiteichan());
        value.setDistanceCatalina(row.distanceCatalina()); value.setCost(row.cost());
        value.setTyku(row.tyku()); value.setHoum(row.houm()); value.setHouk(row.houk());
        value.setRaig(row.raig()); value.setBaku(row.baku()); value.setTais(row.tais()); value.setSaku(row.saku());
        return value;
    }
}
''')

new('src/main/java/logbook/internal/gui/EquipmentDetailItem.java',r'''package logbook.internal.gui;

import java.util.Optional;
import java.util.StringJoiner;
import logbook.common.Messages;
import logbook.plugin.api.EquipmentDataV1;

/** Immutable detail projection: never retains the mutable owning Ship. */
public final class EquipmentDetailItem {
    private final EquipmentDataV1.Owned source;
    private EquipmentDetailItem(EquipmentDataV1.Owned source) { this.source = java.util.Objects.requireNonNull(source); }
    public static EquipmentDetailItem toDetailItem(EquipmentDataV1.Owned source) { return new EquipmentDetailItem(source); }
    public Integer getId() { return source.id(); }
    public Integer getAlv() { return source.alv() == null ? 0 : source.alv(); }
    public Integer getLevel() { return source.level() == null ? 0 : source.level(); }
    public EquipmentDataV1.Owner getShip() { return source.owner(); }
    public Integer getShipId() { return source.owner() == null ? null : source.owner().id(); }
    @Override public String toString() {
        return new StringJoiner("\t")
                .add(Optional.of(getAlv()).filter(v -> v > 0).map(v -> Messages.getString("item.alv", v)).orElse(""))
                .add(Optional.of(getLevel()).filter(v -> v > 0).map(v -> Messages.getString("item.level", v)).orElse(""))
                .add(source.owner() == null ? "未所持" : source.owner().displayName()).toString();
    }
}
''')
Path('../evidence').mkdir(exist_ok=True)
Path('../evidence/equipment-data-paths.json').write_text(json.dumps(changed,indent=2))
print('EQUIPMENT_DATA_PREPARED files='+str(len(changed))+' immutable_values=true write_api=false')
