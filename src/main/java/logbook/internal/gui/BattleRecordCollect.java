package logbook.internal.gui;

import javafx.beans.property.*;
import logbook.plugin.api.RecordDataV1;

/** Screen-owned state; does not expose parser beans. */
public final class BattleRecordCollect {
    private final StringProperty unit = new SimpleStringProperty();
    public String getUnit() { return unit.get(); }
    public void setUnit(String value) { unit.set(value); }
    public StringProperty unitProperty() { return unit; }
    private final StringProperty start = new SimpleStringProperty();
    public String getStart() { return start.get(); }
    public void setStart(String value) { start.set(value); }
    public StringProperty startProperty() { return start; }
    private final IntegerProperty win = new SimpleIntegerProperty();
    public int getWin() { return win.get(); }
    public void setWin(int value) { win.set(value); }
    public IntegerProperty winProperty() { return win; }
    private final IntegerProperty s = new SimpleIntegerProperty();
    public int getS() { return s.get(); }
    public void setS(int value) { s.set(value); }
    public IntegerProperty sProperty() { return s; }
    private final IntegerProperty a = new SimpleIntegerProperty();
    public int getA() { return a.get(); }
    public void setA(int value) { a.set(value); }
    public IntegerProperty aProperty() { return a; }
    private final IntegerProperty b = new SimpleIntegerProperty();
    public int getB() { return b.get(); }
    public void setB(int value) { b.set(value); }
    public IntegerProperty bProperty() { return b; }
    private final IntegerProperty c = new SimpleIntegerProperty();
    public int getC() { return c.get(); }
    public void setC(int value) { c.set(value); }
    public IntegerProperty cProperty() { return c; }
    private final IntegerProperty d = new SimpleIntegerProperty();
    public int getD() { return d.get(); }
    public void setD(int value) { d.set(value); }
    public IntegerProperty dProperty() { return d; }
    private RecordDataV1.PeriodKey collectUnit;
    public RecordDataV1.PeriodKey getCollectUnit() { return collectUnit; }
    public void setCollectUnit(RecordDataV1.PeriodKey value) { collectUnit = value; }
    private String area;
    public String getArea() { return area; }
    public void setArea(String value) { area = value; }
    private String areaShortName;
    public String getAreaShortName() { return areaShortName; }
    public void setAreaShortName(String value) { areaShortName = value; }
    private boolean boss;
    public boolean isBoss() { return boss; }
    public void setBoss(boolean value) { boss = value; }
    private int areaSortOrder;
    public int getAreaSortOrder() { return areaSortOrder; }
    public void setAreaSortOrder(int value) { areaSortOrder = value; }
    public static BattleRecordCollect fromTotals(RecordDataV1.BattleTotals totals) {
        BattleRecordCollect row = new BattleRecordCollect();
        row.setStart(totals.departures()); row.setWin(totals.wins());
        row.setS(totals.s()); row.setA(totals.a()); row.setB(totals.b()); row.setC(totals.c()); row.setD(totals.d());
        return row;
    }
}
