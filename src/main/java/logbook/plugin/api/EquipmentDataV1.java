package logbook.plugin.api;

import java.util.List;
import java.util.Map;
import logbook.core.EquipmentDataService;

/**
 * Read-only equipment inventory contract. Values and containers are detached,
 * immutable copies. Capture is serialized with host API/local commands; uncooperative legacy plugins remain outside that boundary;
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
