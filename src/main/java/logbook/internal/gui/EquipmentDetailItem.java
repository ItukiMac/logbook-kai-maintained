package logbook.internal.gui;

import java.util.Optional;
import java.util.StringJoiner;
import logbook.common.Messages;
import logbook.plugin.api.EquipmentDataV1;

/** Immutable detail projection: never retains the mutable owning Ship. */
public final class EquipmentDetailItem {
    private final EquipmentDataV1.Owned source;

    private EquipmentDetailItem(EquipmentDataV1.Owned source) {
        this.source = java.util.Objects.requireNonNull(source);
    }

    public static EquipmentDetailItem toDetailItem(EquipmentDataV1.Owned source) {
        return new EquipmentDetailItem(source);
    }

    public Integer getId() {
        return source.id();
    }

    public Integer getAlv() {
        return source.alv() == null ? 0 : source.alv();
    }

    public Integer getLevel() {
        return source.level() == null ? 0 : source.level();
    }

    public EquipmentDataV1.Owner getShip() {
        return source.owner();
    }

    public Integer getShipId() {
        return source.owner() == null ? null : source.owner().id();
    }

    @Override
    public String toString() {
        return new StringJoiner("\t")
                .add(Optional.of(getAlv()).filter(v -> v > 0).map(v -> Messages.getString("item.alv", v)).orElse(""))
                .add(Optional.of(getLevel()).filter(v -> v > 0).map(v -> Messages.getString("item.level", v))
                        .orElse(""))
                .add(source.owner() == null ? "未所持" : source.owner().displayName()).toString();
    }
}
