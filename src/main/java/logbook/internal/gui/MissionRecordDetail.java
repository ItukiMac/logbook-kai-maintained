package logbook.internal.gui;

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
    public String getName() { return source.name(); }
    public String getResult() { return source.result(); }
    public String getItem1name() { return source.item1name(); }
    public String getItem2name() { return source.item2name(); }
    public int getFuel() { return source.fuel(); }
    public int getAmmo() { return source.ammo(); }
    public int getMetal() { return source.metal(); }
    public int getBauxite() { return source.bauxite(); }
    public String getItem1count() { return source.item1count() == 0 ? "" : Integer.toString(source.item1count()); }
    public String getItem2count() { return source.item2count() == 0 ? "" : Integer.toString(source.item2count()); }
    @Override public String toString() {
        return new StringJoiner("\t")
                .add(getDate())
                .add(getName())
                .add(getResult())
                .add(Integer.toString(getFuel()))
                .add(Integer.toString(getAmmo()))
                .add(Integer.toString(getMetal()))
                .add(Integer.toString(getBauxite()))
                .add(getItem1name())
                .add(getItem1count())
                .add(getItem2name())
                .add(getItem2count())
                .toString();
    }
}
