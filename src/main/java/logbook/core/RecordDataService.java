package logbook.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import logbook.bean.AppConfig;
import logbook.internal.kancolle.BattleLogs;
import logbook.internal.kancolle.MissionLogs;
import logbook.internal.log.CreateitemLogFormat;
import logbook.internal.log.LogWriter;
import logbook.internal.logger.LoggerHolder;
import logbook.plugin.api.RecordDataV1;
import logbook.plugin.api.RecordDataV1.*;

/** Host adapter; keeps CSV readers and mutable parser beans out of feature contracts. */
public final class RecordDataService {
    private RecordDataService() { }
    private static ZonedDateTime today() {
        return ZonedDateTime.now(ZoneId.of("GMT+04:00")).truncatedTo(ChronoUnit.DAYS);
    }
    public static List<BattleRow> battles() {
        return BattleLogs.readSimpleLog(log -> true).stream().map(RecordDataService::copyBattle).toList();
    }
    public static List<BattleRow> battles(PeriodKey period) {
        var now = today();
        return BattleLogs.readSimpleLog(log -> period.accept(log.getDate(), now)).stream()
                .map(RecordDataService::copyBattle).toList();
    }
    public static Map<PeriodKey, List<BattleRow>> recentBattles() {
        var now = today(); var limit = now.minusMonths(2);
        var rows = BattleLogs.readSimpleLog(log -> log.getDate().compareTo(limit) > 0).stream()
                .map(RecordDataService::copyBattle).toList();
        Map<PeriodKey, List<BattleRow>> result = new LinkedHashMap<>();
        for (var period : Period.values())
            result.put(period, rows.stream().filter(row -> period.accept(row.date(), now)).toList());
        return Collections.unmodifiableMap(result);
    }
    public static List<MissionRow> missions() {
        return MissionLogs.readSimpleLog(log -> true).stream().map(RecordDataService::copyMission).toList();
    }
    public static List<MissionRow> missionsSince(ZonedDateTime exclusive) {
        Objects.requireNonNull(exclusive);
        return MissionLogs.readSimpleLog(log -> log.getDate().isAfter(exclusive)).stream()
                .map(RecordDataService::copyMission).toList();
    }
    public static List<DevelopmentRow> developments() {
        Path path = Path.of(AppConfig.get().getReportPath()).resolve(new CreateitemLogFormat().fileName());
        if (!Files.exists(path)) return List.of();
        try (Stream<String> lines = Files.lines(path, LogWriter.DEFAULT_CHARSET)) {
            return lines.skip(1).map(RecordDataService::parseDevelopment).flatMap(Optional::stream).toList();
        } catch (Exception failure) {
            LoggerHolder.get().warn("開発報告書の読込中に例外", failure);
            return List.of();
        }
    }
    public static Optional<DevelopmentRow> parseDevelopment(String line) {
        if (line == null) return Optional.empty();
        try {
            String[] c = line.split(",", -1);
            if (c.length < 8) return Optional.empty();
            return Optional.of(new DevelopmentRow(c[0], c[1], c[2], Integer.parseInt(c[3]),
                    Integer.parseInt(c[4]), Integer.parseInt(c[5]), Integer.parseInt(c[6]), c[7]));
        } catch (NumberFormatException malformed) { return Optional.empty(); }
    }
    public static String periodName(String name) { return BattleLogs.Unit.valueOf(name).getName(); }
    public static boolean periodAccept(String name, ZonedDateTime target, ZonedDateTime now) {
        return BattleLogs.Unit.valueOf(name).accept(target, now);
    }
    public static BattleTotals summarize(List<BattleRow> rows, String area, boolean bossOnly) {
        Objects.requireNonNull(rows);
        long departures = 0; int s=0, a=0, b=0, c=0, d=0;
        for (BattleRow row : rows) {
            Objects.requireNonNull(row);
            if (area != null && !area.equals(row.areaShortName())) continue;
            if (!bossOnly && row.boss().contains("出撃")) departures++;
            if (bossOnly && !row.boss().contains("ボス")) continue;
            switch (row.rank()) {
                case "S" -> s++;
                case "A" -> a++;
                case "B" -> b++;
                case "C" -> c++;
                case "D" -> d++;
                default -> { }
            }
        }
        return new BattleTotals(bossOnly ? "-" : Long.toString(departures), s+a+b, s,a,b,c,d);
    }
    public static BattleRow copyBattle(BattleLogs.SimpleBattleLog row) {
        Objects.requireNonNull(row);
        return new BattleRow(row.getDateString(), row.getDate(), row.getArea(), row.getAreaShortName(), row.getAreaSortOrder(), row.getCell(), row.getBoss(), row.getRank(), row.getIntercept(), row.getFformation(), row.getEformation(), row.getDispseiku(), row.getFtouch(), row.getEtouch(), row.getEfleet(), row.getEName1(), row.getEHp1(), row.getDropType(), row.getDropShip(), row.getDropItem(), row.getShipExp(), row.getExp());
    }
    public static MissionRow copyMission(MissionLogs.SimpleMissionLog row) {
        Objects.requireNonNull(row);
        return new MissionRow(row.getDateString(), row.getDate(), row.getResult(), row.getArea(), row.getName(), row.getFuel(), row.getAmmo(), row.getMetal(), row.getBauxite(), row.getItem1name(), row.getItem1count(), row.getItem2name(), row.getItem2count(), row.getExp());
    }
}
