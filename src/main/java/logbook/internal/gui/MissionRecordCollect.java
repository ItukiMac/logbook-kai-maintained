package logbook.internal.gui;

import javafx.beans.property.*;
import logbook.plugin.api.RecordDataV1;

/** Screen-owned state; does not expose parser beans. */
public final class MissionRecordCollect {
    private final StringProperty unit = new SimpleStringProperty();
    public String getUnit() { return unit.get(); }
    public void setUnit(String value) { unit.set(value); }
    public StringProperty unitProperty() { return unit; }
    private final IntegerProperty successGood = new SimpleIntegerProperty();
    public int getSuccessGood() { return successGood.get(); }
    public void setSuccessGood(int value) { successGood.set(value); }
    public IntegerProperty successGoodProperty() { return successGood; }
    private final IntegerProperty success = new SimpleIntegerProperty();
    public int getSuccess() { return success.get(); }
    public void setSuccess(int value) { success.set(value); }
    public IntegerProperty successProperty() { return success; }
    private final IntegerProperty fail = new SimpleIntegerProperty();
    public int getFail() { return fail.get(); }
    public void setFail(int value) { fail.set(value); }
    public IntegerProperty failProperty() { return fail; }
    private RecordDataV1.Period collectUnit;
    public RecordDataV1.Period getCollectUnit() { return collectUnit; }
    public void setCollectUnit(RecordDataV1.Period value) { collectUnit = value; }
    private String name;
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    private int sortOrder;
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int value) { sortOrder = value; }
}
