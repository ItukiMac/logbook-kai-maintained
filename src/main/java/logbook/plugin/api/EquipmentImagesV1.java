package logbook.plugin.api;

import javafx.application.Platform;
import javafx.scene.image.Image;
import logbook.bean.ShipCollection;
import logbook.bean.SlotitemMstCollection;
import logbook.internal.kancolle.Items;
import logbook.internal.kancolle.Ships;

/** Host-rendered visual resources. Images use the current host state, not a frozen inventory transaction. */
public final class EquipmentImagesV1 {
    private EquipmentImagesV1() { }
    public static int version() { return 1; }
    private static void checkThread() {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("Equipment images require the JavaFX thread");
    }
    public static Image item(int masterId) {
        checkThread();
        var master = SlotitemMstCollection.get().getSlotitemMap().get(masterId);
        return master == null ? null : Items.itemImage(master);
    }
    public static Image owner(Integer shipId) {
        checkThread();
        var ship = shipId == null ? null : ShipCollection.get().getShipMap().get(shipId);
        return Ships.shipWithItemImage(ship);
    }
}
