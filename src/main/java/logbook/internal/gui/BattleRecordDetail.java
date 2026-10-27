package logbook.internal.gui;

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
    public String getArea() { return source.area(); }
    public String getAreaShortName() { return source.areaShortName(); }
    public String getCell() { return source.cell(); }
    public String getBoss() { return source.boss(); }
    public String getRank() { return source.rank(); }
    public String getIntercept() { return source.intercept(); }
    public String getFformation() { return source.fformation(); }
    public String getEformation() { return source.eformation(); }
    public String getDispseiku() { return source.dispseiku(); }
    public String getFtouch() { return source.ftouch(); }
    public String getEtouch() { return source.etouch(); }
    public String getEfleet() { return source.efleet(); }
    public String getDropType() { return source.dropType(); }
    public String getDropShip() { return source.dropShip(); }
    public String getDropItem() { return source.dropItem(); }
    @Override public String toString() {
        return new StringJoiner("\t")
                .add(getDate())
                .add(getArea())
                .add(getCell())
                .add(getBoss())
                .add(getRank())
                .add(getIntercept())
                .add(getFformation())
                .add(getEformation())
                .add(getDispseiku())
                .add(getFtouch())
                .add(getEtouch())
                .add(getEfleet())
                .add(getDropType())
                .add(getDropShip())
                .add(getDropItem())
                .add(shipExpValue.display()).add(expValue.display()).toString();
    }
}
