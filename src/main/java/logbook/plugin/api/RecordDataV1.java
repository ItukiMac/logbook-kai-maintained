package logbook.plugin.api;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import logbook.core.RecordDataService;

/**
 * Version 1 read-only record contract. Returned lists/maps and value records are
 * detached immutable snapshots. No JavaFX, mutable bean or parser type appears
 * in its public signatures. Disk capture is not an atomic transaction across
 * files; legacy readers' missing/corrupt-line policies are retained.
 * No write, deletion, repair, arbitrary path or deployment operation is exposed.
 */
public final class RecordDataV1 {
    private RecordDataV1() { }
    public static int version() { return 1; }
    public static List<BattleRow> battles() { return RecordDataService.battles(); }
    public static List<BattleRow> battles(PeriodKey period) { return RecordDataService.battles(Objects.requireNonNull(period)); }
    public static Map<PeriodKey, List<BattleRow>> recentBattles() { return RecordDataService.recentBattles(); }
    public static List<MissionRow> missions() { return RecordDataService.missions(); }
    public static List<DevelopmentRow> developments() { return RecordDataService.developments(); }
    public static Optional<DevelopmentRow> parseDevelopment(String line) { return RecordDataService.parseDevelopment(line); }
    public static BattleTotals summarizeBattles(List<BattleRow> rows, String area, boolean bossOnly) {
        return RecordDataService.summarize(rows, area, bossOnly);
    }

    public interface PeriodKey {
        String getName();
        boolean accept(ZonedDateTime target, ZonedDateTime now);
    }
    /** Keeps existing reset-zone and period selection rules through one host implementation. */
    public enum Period implements PeriodKey {
        DAILY, WEEKLY, MONTHLY, LAST_WEEK, LAST_MONTH;
        public String getName() { return RecordDataService.periodName(name()); }
        public boolean accept(ZonedDateTime target, ZonedDateTime now) {
            return RecordDataService.periodAccept(name(), target, now);
        }
    }
    public static final class DateRange implements PeriodKey {
        private final ZonedDateTime from;
        private final ZonedDateTime to;
        private final String name;
        public DateRange(LocalDate from, LocalDate to) {
            Objects.requireNonNull(from); Objects.requireNonNull(to);
            if (to.isBefore(from)) throw new IllegalArgumentException("End date precedes start date");
            var zone = ZoneId.of("GMT+04:00");
            this.from = ZonedDateTime.of(LocalDateTime.of(from, LocalTime.MIN), zone);
            this.to = ZonedDateTime.of(LocalDateTime.of(to, LocalTime.MAX), zone);
            var format = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT);
            this.name = format.format(from) + "-" + format.format(to) + "(" + (to.toEpochDay()-from.toEpochDay()+1) + "日)";
        }
        public ZonedDateTime getFrom() { return from; }
        public ZonedDateTime getTo() { return to; }
        public String getName() { return name; }
        public boolean accept(ZonedDateTime target, ZonedDateTime now) {
            return !target.isBefore(from) && !target.isAfter(to);
        }
    }

    public enum ValueState { RECORDED, MISSING, INVALID }
    public record RecordedInteger(String raw, Integer value, ValueState state) {
        public RecordedInteger {
            Objects.requireNonNull(state);
            if ((state == ValueState.RECORDED) != (value != null))
                throw new IllegalArgumentException("Recorded state and value disagree");
        }
        public String display() {
            return state == ValueState.RECORDED ? value.toString() : state == ValueState.MISSING ? "未記録" : "不正";
        }
    }
    /** Missing/malformed historical experience is never silently converted to zero. */
    public static RecordedInteger integerValue(String raw) {
        if (raw == null || raw.isEmpty()) return new RecordedInteger(raw, null, ValueState.MISSING);
        try { return new RecordedInteger(raw, Integer.valueOf(raw), ValueState.RECORDED); }
        catch (NumberFormatException invalid) { return new RecordedInteger(raw, null, ValueState.INVALID); }
    }
    public record BattleTotals(String departures, int wins, int s, int a, int b, int c, int d) { }
    public record BattleRow(String dateString, ZonedDateTime date, String area, String areaShortName, int areaSortOrder, String cell, String boss, String rank, String intercept, String fformation, String eformation, String dispseiku, String ftouch, String etouch, String efleet, String eName1, String eHp1, String dropType, String dropShip, String dropItem, String shipExp, String exp) {
        public String getDateString() { return dateString; }
        public ZonedDateTime getDate() { return date; }
        public String getArea() { return area; }
        public String getAreaShortName() { return areaShortName; }
        public int getAreaSortOrder() { return areaSortOrder; }
        public String getCell() { return cell; }
        public String getBoss() { return boss; }
        public String getRank() { return rank; }
        public String getIntercept() { return intercept; }
        public String getFformation() { return fformation; }
        public String getEformation() { return eformation; }
        public String getDispseiku() { return dispseiku; }
        public String getFtouch() { return ftouch; }
        public String getEtouch() { return etouch; }
        public String getEfleet() { return efleet; }
        public String getEName1() { return eName1; }
        public String getEHp1() { return eHp1; }
        public String getDropType() { return dropType; }
        public String getDropShip() { return dropShip; }
        public String getDropItem() { return dropItem; }
        public String getShipExp() { return shipExp; }
        public String getExp() { return exp; }
    }
    public record MissionRow(String dateString, ZonedDateTime date, String result, String area, String name, int fuel, int ammo, int metal, int bauxite, String item1name, int item1count, String item2name, int item2count, int exp) {
        public String getDateString() { return dateString; }
        public ZonedDateTime getDate() { return date; }
        public String getResult() { return result; }
        public String getArea() { return area; }
        public String getName() { return name; }
        public int getFuel() { return fuel; }
        public int getAmmo() { return ammo; }
        public int getMetal() { return metal; }
        public int getBauxite() { return bauxite; }
        public String getItem1name() { return item1name; }
        public int getItem1count() { return item1count; }
        public String getItem2name() { return item2name; }
        public int getItem2count() { return item2count; }
        public int getExp() { return exp; }
    }
    public record DevelopmentRow(String date, String item, String type, int fuel, int ammo, int metal, int bauxite, String secretary) {
        public String getDate() { return date; }
        public String getItem() { return item; }
        public String getType() { return type; }
        public int getFuel() { return fuel; }
        public int getAmmo() { return ammo; }
        public int getMetal() { return metal; }
        public int getBauxite() { return bauxite; }
        public String getSecretary() { return secretary; }
    }
}
