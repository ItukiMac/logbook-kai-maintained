package logbook.plugin.api;

import java.util.List;
import java.util.Objects;
import logbook.core.AirBaseStatusService;

/** Detached display values, not an atomic transaction with legacy communication writers. */
public final class AirBaseStatusV1 {
    private AirBaseStatusV1() { }
    public static int version() { return 1; }
    public static Snapshot snapshot() { return AirBaseStatusService.snapshot(); }

    public record Key(int areaId, int baseId) { }
    public record PlaneView(Integer squadronId, Integer state, Integer slotId,
            Integer count, Integer maxCount, Integer condition, Integer masterId,
            String name, Integer improvement, Integer proficiency) { }

    /** Null numeric metrics mean unavailable, never an invented zero. */
    public record BaseView(Key key, String areaName, Integer areaType, String name,
            Integer actionKind, Integer distanceBase, Integer distanceBonus,
            Integer distance, Integer airPower, List<PlaneView> planes, List<String> warnings) {
        public BaseView {
            Objects.requireNonNull(key);
            planes = List.copyOf(planes); warnings = List.copyOf(warnings);
        }
        public boolean lowSupply() {
            return planes.stream().anyMatch(p -> Integer.valueOf(1).equals(p.state())
                    && p.count()!=null && p.maxCount()!=null && p.count()>=0 && p.maxCount()>=0
                    && !p.count().equals(p.maxCount()));
        }
        public boolean fatigued() {
            return planes.stream().anyMatch(p -> Integer.valueOf(1).equals(p.state())
                    && p.condition()!=null && (p.condition()==2 || p.condition()==3));
        }
        public boolean relocating() { return planes.stream().anyMatch(p -> Integer.valueOf(2).equals(p.state())); }
        public String actionText() {
            if(actionKind==null) return "未記録";
            return switch(actionKind) {case 0 -> "待機";case 1 -> "出撃";case 2 -> "防空";
                case 3 -> "退避";case 4 -> "休息";default -> "不明("+actionKind+")";};
        }
    }
    public record Snapshot(List<BaseView> bases, List<String> warnings) {
        public Snapshot { bases=List.copyOf(bases); warnings=List.copyOf(warnings); }
    }
    public static String metric(Integer value) { return value==null ? "未記録" : value.toString(); }
}
