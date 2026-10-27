from pathlib import Path
import re,json

changed=[]
def add(path,text):
    p=Path(path);assert not p.exists(),path
    p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf8');changed.append(path)
def edit(path,fn):
    p=Path(path);raw=p.read_bytes();crlf=b'\r\n' in raw
    text=raw.decode('utf8').replace('\r\n','\n');new=fn(text)
    assert new!=text,path
    p.write_bytes(new.replace('\n','\r\n' if crlf else '\n').encode('utf8'));changed.append(path)
def once(text,old,new):
    assert text.count(old)==1,(old,text.count(old));return text.replace(old,new)
def clone(path,newpath,fn):
    add(newpath,fn(Path(path).read_text()))

battle=[('String',n) for n in ('dateString',)]+[('ZonedDateTime','date')]+[('String',n) for n in ('area','areaShortName')]+[('int','areaSortOrder')]+[('String',n) for n in ('cell','boss','rank','intercept','fformation','eformation','dispseiku','ftouch','etouch','efleet','eName1','eHp1','dropType','dropShip','dropItem','shipExp','exp')]
mission=[('String','dateString'),('ZonedDateTime','date')]+[('String',n) for n in ('result','area','name')]+[('int',n) for n in ('fuel','ammo','metal','bauxite')]+[('String','item1name'),('int','item1count'),('String','item2name'),('int','item2count'),('int','exp')]
development=[('String',n) for n in ('date','item','type')]+[('int',n) for n in ('fuel','ammo','metal','bauxite')]+[('String','secretary')]
def cap(n):return n[0].upper()+n[1:]
def record(name,fields):
    return '    public record '+name+'('+', '.join(t+' '+n for t,n in fields)+') {\n'+''.join('        public '+t+' get'+cap(n)+'() { return '+n+'; }\n' for t,n in fields)+'    }\n'
api='''package logbook.plugin.api;

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
'''+record('BattleRow',battle)+record('MissionRow',mission)+record('DevelopmentRow',development)+'}\n'
add('src/main/java/logbook/plugin/api/RecordDataV1.java',api)
service='''package logbook.core;

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
'''
for name,typ,fields in [('Battle','BattleLogs.SimpleBattleLog',battle),('Mission','MissionLogs.SimpleMissionLog',mission)]:
    service+='    public static '+name+'Row copy'+name+'('+typ+' row) {\n        Objects.requireNonNull(row);\n        return new '+name+'Row('+', '.join('row.get'+cap(n)+'()' for t,n in fields)+');\n    }\n'
service+='}\n'
add('src/main/java/logbook/core/RecordDataService.java',service)

# Preserve the old binary API as an adapter; there is only one aggregation algorithm.
def legacy(s):
    start=s.index('    public static BattleLogCollect collect(')
    end=s.index('    private static ZonedDateTime unitToday()',start)
    prefix='''    /** @deprecated New record features use immutable RecordDataV1.BattleTotals. */
    @Deprecated
    public static BattleLogCollect collect(List<SimpleBattleLog> logs, String areaShortName, boolean bossOnly) {
        var totals = logbook.plugin.api.RecordDataV1.summarizeBattles(logs.stream()
                .map(logbook.core.RecordDataService::copyBattle).toList(), areaShortName, bossOnly);
        BattleLogCollect value = new BattleLogCollect();
        value.setStart(totals.departures()); value.setWin(totals.wins());
        value.setS(totals.s()); value.setA(totals.a()); value.setB(totals.b());
        value.setC(totals.c()); value.setD(totals.d());
        return value;
    }

'''
    return s[:start]+prefix+s[end:]
edit('src/main/java/logbook/internal/kancolle/BattleLogs.java',legacy)
edit('src/main/java/logbook/internal/gui/MainMenuController.java',lambda s:once(s,'BattleLogs.read(BattleLogDetail.toBattleLogDetail(battleLog).getDate())','BattleLogs.read(battleLog.getDateString())'))

# New screen-owned view models leave all old public models available for existing plugins.
def battle_collect(s):
    s=s.replace('BattleLogCollect','BattleRecordCollect').replace('import logbook.internal.kancolle.BattleLogs.IUnit;','import logbook.plugin.api.RecordDataV1.PeriodKey;').replace('IUnit','PeriodKey')
    pos=s.rfind('}')
    return s[:pos]+'''    public static BattleRecordCollect fromTotals(logbook.plugin.api.RecordDataV1.BattleTotals totals) {
        BattleRecordCollect row = new BattleRecordCollect();
        row.setStart(totals.departures()); row.setWin(totals.wins());
        row.setS(totals.s()); row.setA(totals.a()); row.setB(totals.b()); row.setC(totals.c()); row.setD(totals.d());
        return row;
    }
'''+s[pos:]
clone('src/main/java/logbook/internal/gui/BattleLogCollect.java','src/main/java/logbook/internal/gui/BattleRecordCollect.java',battle_collect)

def detail(s):
    s=s.replace('BattleLogDetail','BattleRecordDetail').replace('import logbook.internal.kancolle.BattleLogs.SimpleBattleLog;','import logbook.plugin.api.RecordDataV1;\nimport logbook.plugin.api.RecordDataV1.BattleRow;').replace('SimpleBattleLog','BattleRow')
    s=s.replace('import javafx.beans.property.IntegerProperty;','import javafx.beans.property.ObjectProperty;')
    s=s.replace('import javafx.beans.property.SimpleIntegerProperty;','import javafx.beans.property.SimpleObjectProperty;')
    s=s.replace('IntegerProperty','ObjectProperty<Integer>').replace('new SimpleObjectProperty<Integer>()','new SimpleObjectProperty<>()')
    # The replacement above converts SimpleIntegerProperty after imports were normalized.
    s=s.replace('new SimpleIntegerProperty()','new SimpleObjectProperty<>()')
    for n in ('shipExp','exp'):
        title=cap(n)
        s=s.replace('public int get'+title+'()', 'public Integer get'+title+'()')
        s=s.replace('void set'+title+'(int '+n+')', 'void set'+title+'(Integer '+n+')')
        s=once(s,'Integer.toString(this.'+n+'.get())','this.'+n+'Value.display()')
        s=once(s,'detail.set'+title+'(Integer.parseInt(log.get'+title+'()));','detail.'+n+'Value = RecordDataV1.integerValue(log.get'+title+'());\n        detail.set'+title+'(detail.'+n+'Value.value());')
    pos=s.index('    /**\n     * 日付を取得します。')
    s=s[:pos]+'''    private RecordDataV1.RecordedInteger shipExpValue = RecordDataV1.integerValue(null);
    private RecordDataV1.RecordedInteger expValue = RecordDataV1.integerValue(null);
    public RecordDataV1.RecordedInteger getShipExpValue() { return shipExpValue; }
    public RecordDataV1.RecordedInteger getExpValue() { return expValue; }

'''+s[pos:]
    assert 'SimpleInteger' not in s and 'SimpleObjectProperty<Integer>Property' not in s
    return s
clone('src/main/java/logbook/internal/gui/BattleLogDetail.java','src/main/java/logbook/internal/gui/BattleRecordDetail.java',detail)
clone('src/main/java/logbook/internal/gui/MissionLogCollect.java','src/main/java/logbook/internal/gui/MissionRecordCollect.java',lambda s:s.replace('MissionLogCollect','MissionRecordCollect').replace('import logbook.internal.kancolle.BattleLogs.Unit;','import logbook.plugin.api.RecordDataV1.Period;').replace('Unit','Period'))
clone('src/main/java/logbook/internal/gui/MissionLogDetail.java','src/main/java/logbook/internal/gui/MissionRecordDetail.java',lambda s:s.replace('MissionLogDetail','MissionRecordDetail').replace('import logbook.internal.kancolle.MissionLogs.SimpleMissionLog;','import logbook.plugin.api.RecordDataV1.MissionRow;').replace('SimpleMissionLog','MissionRow'))

# Move live consumers to detached data. Legacy classes stay in core for ABI compatibility.
def battle_controller(s):
    s=once(s,'import logbook.internal.kancolle.BattleLogs;','import logbook.plugin.api.RecordDataV1;')
    s=once(s,'import logbook.internal.kancolle.BattleLogs.IUnit;','import logbook.plugin.api.RecordDataV1.PeriodKey;')
    s=once(s,'import logbook.internal.kancolle.BattleLogs.SimpleBattleLog;','import logbook.plugin.api.RecordDataV1.BattleRow;')
    s=once(s,'import logbook.internal.kancolle.BattleLogs.Unit;','import logbook.plugin.api.RecordDataV1.Period;')
    s=re.sub(r'\bIUnit\b','PeriodKey',s);s=re.sub(r'\bUnit\b','Period',s)
    s=s.replace('SimpleBattleLog','BattleRow').replace('BattleLogDetail','BattleRecordDetail').replace('BattleLogCollect','BattleRecordCollect')
    # BattleLogDetailAggregate is an existing view-only model, not a raw-data contract.
    s=s.replace('BattleRecordDetailAggregate','BattleLogDetailAggregate')
    s=s.replace('BattleLogs.CustomUnit','RecordDataV1.DateRange')
    s=once(s,'this.logMap = BattleLogs.readSimpleLog();','this.logMap = new java.util.LinkedHashMap<>(RecordDataV1.recentBattles());')
    s=s.replace('BattleLogs.readSimpleLog(unit)','RecordDataV1.battles(unit)')
    s,n=re.subn(r'BattleLogs\.collect\((list, [^;]+)\);',r'BattleRecordCollect.fromTotals(RecordDataV1.summarizeBattles(\1));',s)
    assert n==4,n
    # Null property values stay numerically sortable; table and copy explicitly distinguish unknown from zero.
    old='            this.exp.setCellValueFactory(new PropertyValueFactory<>("exp"));'
    new=old+'''
            this.shipExp.setCellFactory(column -> new RecordedExperienceCell(true));
            this.exp.setCellFactory(column -> new RecordedExperienceCell(false));'''
    s=once(s,old,new)
    # Numeric filter/group keys must remain non-null even for old records.
    s=s.replace('this.addFilterColumn(this.detail, this.shipExp, listener, BattleRecordDetail::getShipExp)',
                'this.addFilterColumn(this.detail, this.shipExp, listener, BattleRecordDetail::getShipExp)')
    # Numeric grouping uses a non-null label for missing/invalid values.
    s=s.replace('this.addAggregate(this.shipExp, BattleRecordDetail::getShipExp)',
                'this.addAggregate(this.shipExp, row -> row.getShipExpValue().display())')
    s=s.replace('this.addAggregate(this.exp, BattleRecordDetail::getExp)',
                'this.addAggregate(this.exp, row -> row.getExpValue().display())')
    pos=s.rfind('}')
    s=s[:pos]+'''    /** Render unknown experience without changing the persisted record or inventing zero. */
    private static final class RecordedExperienceCell extends javafx.scene.control.TableCell<BattleRecordDetail, Integer> {
        private final boolean ship;
        private RecordedExperienceCell(boolean ship) { this.ship = ship; }
        @Override protected void updateItem(Integer value, boolean empty) {
            super.updateItem(value, empty);
            BattleRecordDetail row = getTableRow() == null ? null : getTableRow().getItem();
            if (empty || row == null) { setText(null); return; }
            setText((ship ? row.getShipExpValue() : row.getExpValue()).display());
        }
    }
'''+s[pos:]
    assert 'BattleLogs' not in s,s[s.find('BattleLogs')-60:s.find('BattleLogs')+120]
    return s
edit('src/main/java/logbook/internal/gui/BattleLogController.java',battle_controller)

def mission_controller(s):
    s=once(s,'import logbook.internal.kancolle.MissionLogs;','import logbook.plugin.api.RecordDataV1;')
    s=once(s,'import logbook.internal.kancolle.BattleLogs.Unit;','import logbook.plugin.api.RecordDataV1.Period;')
    s=once(s,'import logbook.internal.kancolle.MissionLogs.SimpleMissionLog;','import logbook.plugin.api.RecordDataV1.MissionRow;')
    s=s.replace('SimpleMissionLog','MissionRow').replace('MissionLogCollect','MissionRecordCollect').replace('MissionLogDetail','MissionRecordDetail')
    s=re.sub(r'\bUnit\b','Period',s)
    s=once(s,'MissionLogs.readSimpleLog(log -> log.getDate().compareTo(limit) > 0)',
           'RecordDataV1.missions().stream().filter(log -> log.getDate().compareTo(limit) > 0).toList()')
    assert 'MissionLogs' not in s
    return s
edit('src/main/java/logbook/internal/gui/MissionLogController.java',mission_controller)

def development_controller(s):
    s=once(s,'import logbook.plugin.api.FeatureUiV1;','import logbook.plugin.api.FeatureUiV1;\nimport logbook.plugin.api.RecordDataV1;')
    for imp in ('java.nio.file.Files','java.nio.file.Path','java.nio.file.Paths','logbook.bean.AppConfig','logbook.internal.log.CreateitemLogFormat','logbook.internal.log.LogWriter'):
        s=s.replace('import '+imp+';\n','')
    s=once(s,'        Path logFile = Paths.get(AppConfig.get().getReportPath(), new CreateitemLogFormat().fileName());\n','')
    start=s.index('            try (Stream<String> lines = Files.lines(')
    end=s.index('\n\n            Map<Recipe, Long>',start)
    s=s[:start]+'''            logs = RecordDataV1.developments().stream()
                    .map(CreateItem::fromSnapshot)
                    .map(item -> item.setEquipType(map.getOrDefault(item.getType(), -1)))
                    .collect(toList());'''+s[end:]
    start=s.index('        public static CreateItem parse(String line) {')
    end=s.index('\n    }',start)
    s=s[:start]+'''        /** Legacy parser entry point delegates to the shared data parser. */
        public static CreateItem parse(String line) {
            return RecordDataV1.parseDevelopment(line).map(CreateItem::fromSnapshot).orElse(null);
        }

        public static CreateItem fromSnapshot(RecordDataV1.DevelopmentRow row) {
            CreateItem value = new CreateItem();
            value.setDate(row.date()); value.setItem(row.item()); value.setType(row.type());
            value.setRecipe(new Recipe(Integer.toString(row.fuel()), Integer.toString(row.ammo()),
                    Integer.toString(row.metal()), Integer.toString(row.bauxite())));
            value.setSecretary(row.secretary());
            return value;
        }
'''+s[end:]
    assert 'Files.' not in s and 'LogWriter' not in s
    return s
edit('src/main/java/logbook/internal/gui/CreateItemController.java',development_controller)

# The runtime-package proof must extract the actual new view models, not silently leave fallback copies.
def probe_test(s):
    s=once(s,"'BattleLogDetailAggregate', 'MissionLogCollect', 'MissionLogDetail', 'MissionAggregate')",
        "'BattleLogDetailAggregate', 'BattleRecordCollect', 'BattleRecordDetail',\n            'MissionRecordCollect', 'MissionRecordDetail', 'MissionAggregate')")
    return s
edit('scripts/tests/test_record_ui_integration.py',probe_test)

Path('../evidence/changed-paths.json').write_text(json.dumps(changed,indent=2)+'\n')
print('RECORD_DATA_APPLIED',len(changed),'files; legacy APIs retained; original files not accessed')
