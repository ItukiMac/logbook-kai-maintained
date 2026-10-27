from pathlib import Path

def cap(n):return n[0].upper()+n[1:]
def save(name,text):
    p=Path('src/main/java/logbook/internal/gui')/(name+'.java')
    assert p.exists(),p
    p.write_text(text)

battle_strings=['area','areaShortName','cell','boss','rank','intercept','fformation','eformation','dispseiku','ftouch','etouch','efleet','dropType','dropShip','dropItem']
text='''package logbook.internal.gui;

import java.time.ZoneId;
import java.util.StringJoiner;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import logbook.internal.util.DateUtil;
import logbook.plugin.api.RecordDataV1;

/** Screen-owned projection of an immutable record; legacy BattleLogDetail remains an ABI adapter. */
public final class BattleRecordDetail {
    private final RecordDataV1.BattleRow source;
    private final RecordDataV1.RecordedInteger shipExpValue;
    private final RecordDataV1.RecordedInteger expValue;
    private final ReadOnlyObjectWrapper<Integer> shipExp;
    private final ReadOnlyObjectWrapper<Integer> exp;
    private BattleRecordDetail(RecordDataV1.BattleRow source) {
        this.source = java.util.Objects.requireNonNull(source);
        this.shipExpValue = RecordDataV1.integerValue(source.shipExp());
        this.expValue = RecordDataV1.integerValue(source.exp());
        this.shipExp = new ReadOnlyObjectWrapper<>(shipExpValue.value());
        this.exp = new ReadOnlyObjectWrapper<>(expValue.value());
    }
    public static BattleRecordDetail toBattleRecordDetail(RecordDataV1.BattleRow source) {
        return new BattleRecordDetail(source);
    }
    public String getDate() { return DateUtil.DATE_FORMAT.format(source.date().withZoneSameInstant(ZoneId.of("Asia/Tokyo"))); }
    public Integer getShipExp() { return shipExp.get(); }
    public Integer getExp() { return exp.get(); }
    public ReadOnlyObjectProperty<Integer> shipExpProperty() { return shipExp.getReadOnlyProperty(); }
    public ReadOnlyObjectProperty<Integer> expProperty() { return exp.getReadOnlyProperty(); }
    public RecordDataV1.RecordedInteger getShipExpValue() { return shipExpValue; }
    public RecordDataV1.RecordedInteger getExpValue() { return expValue; }
'''
for n in battle_strings:text+='    public String get'+cap(n)+'() { return source.'+n+'(); }\n'
copy_fields=['date','area','cell','boss','rank','intercept','fformation','eformation','dispseiku','ftouch','etouch','efleet','dropType','dropShip','dropItem']
text+='    @Override public String toString() {\n        return new StringJoiner("\\t")\n'+''.join('                .add(get'+cap(n)+'())\n' for n in copy_fields)+'                .add(shipExpValue.display()).add(expValue.display()).toString();\n    }\n}\n'
save('BattleRecordDetail',text)

text='''package logbook.internal.gui;

import java.time.ZoneId;
import java.util.StringJoiner;
import logbook.internal.util.DateUtil;
import logbook.plugin.api.RecordDataV1;

/** Immutable record projection; JavaFX obtains values through standard bean getters. */
public final class MissionRecordDetail {
    private final RecordDataV1.MissionRow source;
    private MissionRecordDetail(RecordDataV1.MissionRow source) { this.source = java.util.Objects.requireNonNull(source); }
    public static MissionRecordDetail toMissionRecordDetail(RecordDataV1.MissionRow source) { return new MissionRecordDetail(source); }
    public String getDate() { return DateUtil.DATE_FORMAT.format(source.date().withZoneSameInstant(ZoneId.of("Asia/Tokyo"))); }
'''
for n in ['name','result','item1name','item2name']:text+='    public String get'+cap(n)+'() { return source.'+n+'(); }\n'
for n in ['fuel','ammo','metal','bauxite']:text+='    public int get'+cap(n)+'() { return source.'+n+'(); }\n'
for n in ['item1count','item2count']:text+='    public String get'+cap(n)+'() { return source.'+n+'() == 0 ? "" : Integer.toString(source.'+n+'()); }\n'
text+='    @Override public String toString() {\n        return new StringJoiner("\\t")\n'
for n in ['date','name','result','fuel','ammo','metal','bauxite','item1name','item1count','item2name','item2count']:
    text+='                .add('+('Integer.toString(get'+cap(n)+'())' if n in ['fuel','ammo','metal','bauxite'] else 'get'+cap(n)+'()')+')\n'
text+='                .toString();\n    }\n}\n'
save('MissionRecordDetail',text)

def mutable_view(name,stringprops,intprops,fields):
    text='package logbook.internal.gui;\n\nimport javafx.beans.property.*;\nimport logbook.plugin.api.RecordDataV1;\n\n/** Screen-owned state; does not expose parser beans. */\npublic final class '+name+' {\n'
    for n in stringprops:
        text+='    private final StringProperty '+n+' = new SimpleStringProperty();\n'
        text+='    public String get'+cap(n)+'() { return '+n+'.get(); }\n'
        text+='    public void set'+cap(n)+'(String value) { '+n+'.set(value); }\n'
        text+='    public StringProperty '+n+'Property() { return '+n+'; }\n'
    for n in intprops:
        text+='    private final IntegerProperty '+n+' = new SimpleIntegerProperty();\n'
        text+='    public int get'+cap(n)+'() { return '+n+'.get(); }\n'
        text+='    public void set'+cap(n)+'(int value) { '+n+'.set(value); }\n'
        text+='    public IntegerProperty '+n+'Property() { return '+n+'; }\n'
    for typ,n in fields:
        text+='    private '+typ+' '+n+';\n'
        text+='    public '+typ+' '+('is' if typ=='boolean' else 'get')+cap(n)+'() { return '+n+'; }\n'
        text+='    public void set'+cap(n)+'('+typ+' value) { '+n+' = value; }\n'
    return text
text=mutable_view('BattleRecordCollect',['unit','start'],['win','s','a','b','c','d'],[('RecordDataV1.PeriodKey','collectUnit'),('String','area'),('String','areaShortName'),('boolean','boss'),('int','areaSortOrder')])
text+='''    public static BattleRecordCollect fromTotals(RecordDataV1.BattleTotals totals) {
        BattleRecordCollect row = new BattleRecordCollect();
        row.setStart(totals.departures()); row.setWin(totals.wins());
        row.setS(totals.s()); row.setA(totals.a()); row.setB(totals.b()); row.setC(totals.c()); row.setD(totals.d());
        return row;
    }
}
'''
save('BattleRecordCollect',text)
text=mutable_view('MissionRecordCollect',['unit'],['successGood','success','fail'],[('RecordDataV1.Period','collectUnit'),('String','name'),('int','sortOrder')])+'}\n'
save('MissionRecordCollect',text)
print('COMPACT_RECORD_VIEWS_READY old_public_rows_unchanged=true')
