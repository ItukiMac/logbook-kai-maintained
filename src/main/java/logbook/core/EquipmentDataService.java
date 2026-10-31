package logbook.core;

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
    private EquipmentDataService() {
    }

    public static Inventory inventory() {
        return LogBookCoreServices.readGameState(() -> captureInventory());
    }

    private static Inventory captureInventory() {
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
                if (type == null)
                    warnings.add("Missing equipment category: " + category);
                definitions.add(copyDefinition(master, type == null ? "不明(" + category + ")" : type.getName(),
                        entry.getValue()));
            } catch (RuntimeException incomplete) {
                warnings.add("Incomplete equipment master: " + entry.getKey());
            }
        }
        if (!warnings.isEmpty())
            LoggerHolder.get().warn("Equipment inventory snapshot: " + warnings);
        return new Inventory(definitions, owned, warnings);
    }

    static Map<Integer, Integer> counts(List<Owned> values) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (Owned value : values)
            counts.merge(value.masterId(), 1, Integer::sum);
        return counts;
    }

    /** Preserve the former first-observed owner policy, including the expansion slot. */
    static Map<Integer, Owner> owners(Collection<Ship> ships, Function<Ship, String> names) {
        Map<Integer, Owner> result = new HashMap<>();
        for (Ship ship : ships) {
            if (ship == null || ship.getId() == null || ship.getShipId() == null)
                continue;
            Owner owner = new Owner(ship.getId(), ship.getShipId(), names.apply(ship));
            if (ship.getSlot() != null) {
                for (Integer id : new ArrayList<>(ship.getSlot()))
                    if (id != null && id > 0)
                        result.putIfAbsent(id, owner);
            }
            Integer extra = ship.getSlotEx();
            if (extra != null && extra > 0)
                result.putIfAbsent(extra, owner);
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
        return LogBookCoreServices.readGameState(() -> captureAircraft());
    }

    private static List<Aircraft> captureAircraft() {
        List<Aircraft> result = new ArrayList<>();
        for (SlotItem item : new ArrayList<>(SlotItemCollection.get().getSlotitemMap().values())) {
            if (item == null)
                continue;
            SlotitemMst master = Items.slotitemMst(item).orElse(null);
            if (master == null || !Items.isAircraft(master) || master.getDistance() == null
                    || master.getDistance() == 0)
                continue;
            // Reuse the existing domain calculations. No independent formula is introduced.
            int onslot = Items.isCombatAircraft(master) ? 18 : 4;
            int radius = master.getDistance();
            var type = SlotitemEquiptypeCollection.get().getEquiptypeMap().get(master.getType().get(2));
            boolean interceptor = master.is(SlotItemType.局地戦闘機);
            result.add(new Aircraft(master.getId(), master.getType().get(2), master.getType().get(3),
                    master.getName(), type == null ? "不明(" + master.getType().get(2) + ")" : type.getName(),
                    item.getAlv() == null ? 0 : item.getAlv(), item.getLevel() == null ? 0 : item.getLevel(),
                    AirBases.airSuperiority(item, onslot, false), AirBases.airSuperiority(item, onslot, true),
                    radius, radius + AirBases.distanceAdditional(radius, 20),
                    radius + AirBases.distanceAdditional(radius, 10),
                    master.getCost() == null ? 0 : master.getCost() * onslot, master.getTyku(),
                    interceptor ? master.getHoum() : 0, interceptor ? master.getHouk() : 0,
                    master.getRaig(), master.getBaku(), master.getTais(), master.getSaku()));
        }
        return List.copyOf(result);
    }

    public static Map<String, List<Category>> categories() {
        return LogBookCoreServices.readGameState(() -> captureCategories());
    }

    private static Map<String, List<Category>> captureCategories() {
        Map<String, List<Category>> result = new LinkedHashMap<>();
        Items.getCategories().forEach((name, types) -> result.put(name, types.stream()
                .filter(Objects::nonNull).map(t -> new Category(t.getId(), t.getName())).toList()));
        return Collections.unmodifiableMap(result);
    }

    public static String itemName(int masterId) {
        return LogBookCoreServices.readGameState(() -> captureItemName(masterId));
    }

    private static String captureItemName(int masterId) {
        SlotitemMst master = SlotitemMstCollection.get().getSlotitemMap().get(masterId);
        return master == null ? "不明(" + masterId + ")" : master.getName();
    }
}
