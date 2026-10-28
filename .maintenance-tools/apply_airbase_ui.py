from pathlib import Path

def once(s,a,b):
    assert s.count(a)==1,(a,s.count(a));return s.replace(a,b)
p=Path('src/main/java/logbook/internal/gui/AirBaseController.java')
raw=p.read_bytes();ending='\r\n' if b'\r\n' in raw else '\n';s=raw.decode().replace('\r\n','\n')
s=once(s,'import logbook.plugin.api.FeatureUiV1;', '''import logbook.plugin.api.FeatureUiV1;
import logbook.plugin.api.AirBaseStatusV1;
import logbook.plugin.api.AirBaseStatusV1.BaseView;
import logbook.plugin.api.AirBaseStatusV1.PlaneView;
import logbook.plugin.api.EquipmentImagesV1;''')
s=once(s,'import logbook.bean.MapareaCollection;\n','')
s=once(s,'import logbook.internal.kancolle.AirBases;\n','')
s=once(s,'    private int hashCode;', '    private AirBaseStatusV1.Snapshot snapshot;')
s=once(s,'this.planeTable.setItems(this.planes);','this.planeTable.setItems(sortedList2);')
s=once(s,'        Optional.ofNullable(item.getValue()).map(AreaTable::getAirBase).ifPresent(list::add);',
'''        if (item == null) return;
        Optional.ofNullable(item.getValue()).map(AreaTable::getAirBase).ifPresent(list::add);''')
a=s.index('    void update(ActionEvent e) {');b=s.index('    private String actionKind(',a)
s=s[:a]+'''    void update(ActionEvent e) {
        AirBaseStatusV1.Snapshot next = AirBaseStatusV1.snapshot();
        if (!next.equals(this.snapshot)) render(next);
    }

    private void setAirBase() { render(AirBaseStatusV1.snapshot()); }

    /** One captured value set feeds the tree, details, warnings and copy text. */
    private void render(AirBaseStatusV1.Snapshot next) {
        TreeItem<AreaTable> selected = this.areaTable.getSelectionModel().getSelectedItem();
        BaseView before = selected == null || selected.getValue() == null ? null : selected.getValue().view;
        this.snapshot = next;
        TreeItem<AreaTable> root = new TreeItem<>(new AreaTable());
        this.areaTable.setRoot(root);
        var normal = group("通常海域"); var event = group("イベント海域"); var unknown = group("不明海域");
        root.getChildren().addAll(normal, event);
        Map<String,TreeItem<AreaTable>> areas = new java.util.LinkedHashMap<>();
        TreeItem<AreaTable> restore = null;
        for (BaseView value : next.bases()) {
            String areaKey = value.key().areaId() + ":" + value.areaType();
            TreeItem<AreaTable> sea = areas.get(areaKey);
            if (sea == null) {
                sea = group(value.areaName()); areas.put(areaKey, sea);
                (value.areaType() == null ? unknown : value.areaType() == 0 ? normal : event).getChildren().add(sea);
            }
            AreaTable row = new AreaTable(); row.view = value;
            row.setName(value.name()); row.setActionKind(value.actionText());
            row.setSeiku(AirBaseStatusV1.metric(value.airPower())); row.setDistance(AirBaseStatusV1.metric(value.distance()));
            // Legacy public bean getters return detached copies, never the live communication state.
            row.setAirBase(legacyBase(value));
            for (PlaneView plane : value.planes()) {
                Plane item = new Plane(); item.view = plane; item.setSlot(plane.slotId());
                item.setCount(plane.count()); item.setMaxCount(plane.maxCount()); item.setCond(plane.condition());
                item.setPlaneInfo(legacyPlane(plane)); row.getPlanes().add(item);
            }
            TreeItem<AreaTable> leaf = new TreeItem<>(row); sea.getChildren().add(leaf);
            if (before != null && before.key().equals(value.key())) restore = leaf;
        }
        if (!unknown.getChildren().isEmpty()) root.getChildren().add(unknown);
        if (restore != null) this.areaTable.getSelectionModel().select(restore);
        else plane(null, null, null);
    }

    private static TreeItem<AreaTable> group(String name) {
        AreaTable value = new AreaTable(); value.setName(name);
        TreeItem<AreaTable> item = new TreeItem<>(value); item.setExpanded(true); return item;
    }

    private static PlaneInfo legacyPlane(PlaneView p) {
        PlaneInfo copy = new PlaneInfo(); copy.setSquadronId(p.squadronId()); copy.setState(p.state());
        copy.setSlotid(p.slotId()); copy.setCount(p.count()); copy.setMaxCount(p.maxCount()); copy.setCond(p.condition()); return copy;
    }

    private static AirBase legacyBase(BaseView b) {
        AirBase copy = new AirBase(); copy.setAreaId(b.key().areaId()); copy.setRid(b.key().baseId());
        copy.setName(b.name()); copy.setActionKind(b.actionKind());
        Mapinfo.Distance d = new Mapinfo.Distance(); d.setBase(b.distanceBase()); d.setBonus(b.distanceBonus()); copy.setDistance(d);
        copy.setPlaneInfo(b.planes().stream().map(AirBaseController::legacyPlane).collect(Collectors.toList())); return copy;
    }

'''+s[b:]
a=s.index('        this.planes.clear();',s.index('    private void plane('));b=s.index('\n    /**\n     * ウインドウを閉じる',a)
s=s[:a]+'''        this.planes.clear(); this.info.getChildren().clear();
        this.seikuTotal.setText("—"); this.lesser.setText("—"); this.equal.setText("—");
        this.superior.setText("—"); this.greater.setText("—");
        if (this.snapshot != null && !this.snapshot.warnings().isEmpty()) notice("状態未確定", this.snapshot.warnings());
        if (value == null || value.getValue() == null || value.getValue().view == null) return;
        AreaTable row = value.getValue(); BaseView base = row.view; this.planes.addAll(row.getPlanes());
        Integer power = base.airPower(); this.seikuTotal.setText(AirBaseStatusV1.metric(power));
        if (power == null) {
            this.lesser.setText("未記録"); this.equal.setText("未記録");
            this.superior.setText("未記録"); this.greater.setText("未記録");
        } else {
            // Same threshold display, with long intermediates instead of overflowing int multiplication.
            this.lesser.setText(String.valueOf((long)power * 3));
            this.equal.setText(String.valueOf((long)(power * (3D / 2D))));
            this.superior.setText(String.valueOf((long)(power * (2D / 3D))));
            this.greater.setText(String.valueOf((long)(power * (1D / 3D))));
        }
        if (base.lowSupply()) notice("補給不足", List.of());
        if (base.fatigued()) notice("疲労", List.of());
        if (base.relocating()) notice("配置転換中", List.of());
        if (!base.warnings().isEmpty()) notice("情報不足", base.warnings());
    }

    private void notice(String title, List<String> messages) {
        Button button = new Button(title); HBox.setMargin(button, new Insets(1));
        if (!messages.isEmpty()) button.setTooltip(new javafx.scene.control.Tooltip(String.join("\\n", messages)));
        this.info.getChildren().add(button);
    }
'''+s[b:]
s=once(s,'        private AirBase airBase;', '        private AirBase airBase;\n        private BaseView view;')
s=once(s,'        private PlaneInfo planeInfo;', '        private PlaneInfo planeInfo;\n        private PlaneView view;')
s=once(s,'            this.slot = new SimpleIntegerProperty(slot);', '            this.slot = slot == null ? null : new SimpleIntegerProperty(slot);')
# Built-in plane copies never re-read the item map for copied text.
a=s.index('        public String toString() {',s.index('    public static class Plane'))
s=s[:a]+s[a:].replace('        public String toString() {','''        public String toString() {
            if (this.view != null) return new StringJoiner("\\t").add(this.view.name())
                    .add(AirBaseStatusV1.metric(this.view.count())).add(AirBaseStatusV1.metric(this.view.maxCount()))
                    .add(AirBaseStatusV1.metric(this.view.condition())).toString();''',1)
# Frozen status/name with existing public live-image renderer; old externally constructed rows keep legacy behavior.
a=s.index('            if (!empty) {',s.index('    private static class ItemImageCell'))
s=s[:a]+'''            Plane captured = getTableRow() == null ? null : getTableRow().getItem();
            if (!empty && captured != null && captured.view != null) {
                PlaneView data = captured.view;
                this.setText(data.name());
                this.setGraphic(data.masterId() == null ? null : new ImageView(EquipmentImagesV1.item(data.masterId())));
                if (data.state() != null && data.state() != 1) this.getStyleClass().add("change");
                return;
            }
'''+s[a:]
# Old code did not clear an orange/red icon when a recycled cell became healthy.
s=once(s,'            this.getStyleClass().removeAll("cond0", "cond1", "cond2", "cond3");',
'            this.getStyleClass().removeAll("cond0", "cond1", "cond2", "cond3");\n            this.setGraphic(null);')
assert 'Mapinfo.get()' not in s and 'AirBases.airSuperiority(' not in s
p.write_bytes(s.replace('\n',ending).encode())
print('AIRBASE_UI_APPLIED one_captured_display=true selection_by_identity=true old_bean_signatures_retained=true')
