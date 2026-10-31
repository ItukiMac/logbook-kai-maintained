package logbook.internal.gui;

import logbook.plugin.api.EquipmentDataV1;

/** New screen-owned copies; legacy Item/AirBaseItem APIs remain available to existing callers. */
public final class EquipmentProjection {
    private EquipmentProjection() {
    }

    public static Item item(EquipmentDataV1.Definition row) {
        Item value = new Item();
        value.setId(row.id());
        value.setType2(row.type2());
        value.setType3(row.type3());
        value.setName(row.name());
        value.setType(row.type());
        value.setCount(row.count());
        value.setHoug(row.houg());
        value.setHoum(row.houm());
        value.setLeng(row.leng());
        value.setLuck(row.luck());
        value.setHouk(row.houk());
        value.setBaku(row.baku());
        value.setRaig(row.raig());
        value.setSaku(row.saku());
        value.setTais(row.tais());
        value.setTyku(row.tyku());
        value.setSouk(row.souk());
        return value;
    }

    public static AirBaseItem aircraft(EquipmentDataV1.Aircraft row) {
        AirBaseItem value = new AirBaseItem();
        value.setId(row.id());
        value.setType2(row.type2());
        value.setType3(row.type3());
        value.setName(row.name());
        value.setType(row.type());
        value.setAlv(row.alv());
        value.setLevel(row.level());
        value.setCount(1);
        value.setSeiku(row.seiku());
        value.setInterceptSeiku(row.interceptSeiku());
        value.setDistance(row.distance());
        value.setDistanceTaiteichan(row.distanceTaiteichan());
        value.setDistanceCatalina(row.distanceCatalina());
        value.setCost(row.cost());
        value.setTyku(row.tyku());
        value.setHoum(row.houm());
        value.setHouk(row.houk());
        value.setRaig(row.raig());
        value.setBaku(row.baku());
        value.setTais(row.tais());
        value.setSaku(row.saku());
        return value;
    }
}
