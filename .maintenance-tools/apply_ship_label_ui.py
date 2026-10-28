from pathlib import Path

path=Path('src/main/java/logbook/internal/gui/ShipTablePane.java')
raw=path.read_bytes()
old=raw.decode('utf8').replace('\r\n','\n')
assert old.count('import logbook.bean.ShipLabelCollection;')==1
text=old.replace('import logbook.bean.ShipLabelCollection;','import logbook.plugin.api.ShipLabelsV1;')
start=text.index('    @FXML\n    void addLabel() {')
end=text.index('    /**\n     * 艦隊晒しページ',start)
assert text[start:end].count('ShipLabelCollection.get()')==2
replacement=r'''    /** Capture the exact confirmed ships and manual labels before opening a modal dialog. */
    private List<ShipLabelsV1.Target> labelTargets() {
        try {
            return ShipLabelsV1.capture(this.table.getSelectionModel().getSelectedItems().stream()
                    .map(ShipItem::getId).collect(Collectors.toList()));
        } catch (IllegalArgumentException | IllegalStateException changed) {
            Tools.Controls.alert(AlertType.WARNING, "艦娘の状態が変わりました",
                    "ラベルを変更していません。対象を選び直して再実行してください。",
                    this.getScene().getWindow());
            return null;
        }
    }

    /** Real post-confirmation path, also exercised by the isolated GUI probe. */
    ShipLabelsV1.Result applyLabelEdit(List<ShipLabelsV1.Target> targets, String label, boolean add) {
        ShipLabelsV1.Result result = add ? ShipLabelsV1.add(targets, label) : ShipLabelsV1.remove(targets, label);
        if (result.accepted()) {
            for (ShipItem row : this.shipItems) {
                Set<String> values = result.displayLabels().get(row.getId());
                if (values != null) row.setLabel(FXCollections.observableSet(new LinkedHashSet<>(values)));
            }
            this.updateLabel();
            this.updateFilter();
            this.table.refresh();
        }
        return result;
    }

    private void confirmedLabelEdit(List<ShipLabelsV1.Target> targets, String label, boolean add) {
        if (!applyLabelEdit(targets, label, add).accepted()) {
            Tools.Controls.alert(AlertType.WARNING, "ラベルを変更できませんでした",
                    "確認中に艦娘またはラベルの状態が変わりました。変更していません。対象を選び直してください。",
                    this.getScene().getWindow());
        }
    }

    @FXML
    void addLabel() {
        if (this.table.getSelectionModel().getSelectedItems().isEmpty()) {
            Tools.Controls.alert(AlertType.INFORMATION, "艦娘が選ばれていません",
                    "ラベルを追加する艦娘を選択してください", this.getScene().getWindow());
            return;
        }
        List<ShipLabelsV1.Target> targets = labelTargets();
        if (targets == null) return;
        String shipNames = this.table.getSelectionModel().getSelectedItems().stream()
                .map(ship -> Ships.shipMst(ship.getShip()).map(ShipMst::getName).orElse(""))
                .collect(Collectors.joining(","));
        if (shipNames.length() > 50) shipNames = shipNames.substring(0, 50) + "...";
        TextInputDialog dialog = new TextInputDialog("");
        dialog.initOwner(this.getScene().getWindow());
        dialog.setTitle("艦娘にラベルを追加");
        dialog.setHeaderText(shipNames + "にラベルを追加します");
        PublicAutoCompletion.bindAutoCompletion(dialog.getEditor(), new SuggestSupport(this.labelValue.getItems()));
        var result = dialog.showAndWait();
        if (result.isPresent() && !result.get().isEmpty()) confirmedLabelEdit(targets, result.get(), true);
    }

    @FXML
    void removeLabel() {
        if (this.table.getSelectionModel().getSelectedItems().isEmpty()) {
            Tools.Controls.alert(AlertType.INFORMATION, "艦娘が選ばれていません",
                    "ラベルを除去する艦娘を選択してください", this.getScene().getWindow());
            return;
        }
        List<ShipLabelsV1.Target> targets = labelTargets();
        if (targets == null) return;
        String shipNames = this.table.getSelectionModel().getSelectedItems().stream()
                .map(ship -> Ships.shipMst(ship.getShip()).map(ShipMst::getName).orElse(""))
                .collect(Collectors.joining(","));
        if (shipNames.length() > 50) shipNames = shipNames.substring(0, 50) + "...";
        // Event tags are game-owned and must not be offered as editable manual labels.
        Set<String> labels = new TreeSet<>();
        targets.forEach(target -> labels.addAll(target.expectedLabels()));
        ChoiceDialog<String> dialog = new ChoiceDialog<>();
        dialog.initOwner(this.getScene().getWindow());
        dialog.getItems().addAll(labels);
        dialog.setTitle("艦娘からラベルを除去");
        dialog.setHeaderText(shipNames + "からラベルを除去");
        var result = dialog.showAndWait();
        if (result.isPresent() && !result.get().isEmpty()) confirmedLabelEdit(targets, result.get(), false);
    }

'''
text=text[:start]+replacement+text[end:]
assert 'ShipLabelCollection' not in text
ending='\r\n' if b'\r\n' in raw else '\n'
path.write_bytes(text.replace('\n',ending).encode('utf8'))
p=Path('scripts/verify_r3.sh');s=p.read_text()
anchor="report={'parallel_integration':"
assert s.count(anchor)==1
check="""# User-confirmed label edits must execute the real GUI and command checks.
ship_label_reports=[json.loads(f.read_text()) for f in p.glob('ship-label-fixture-*.json')]
assert len(ship_label_reports)==1 and ship_label_reports[0]['result']=='passed'
ship_labels=ship_label_reports[0]
assert ship_labels['dialog_cancel_no_change'] and ship_labels['dialog_confirm_add_remove']
assert ship_labels['stale_batch_rejected'] and ship_labels['settings_reread'] and ship_labels['original_history_unchanged']
assert not ship_labels['production_accessed']
label_suites=[s for s in suites if s.get('name') in {'logbook.core.ShipLabelCommandTest','logbook.internal.gui.ShipLabelUiBoundaryTest'}]
assert len(label_suites)==2 and all(int(s.get('skipped',0))==0 for s in label_suites)
assert sum(int(s.get('tests',0)) for s in label_suites)>=23
"""
s=s.replace(anchor,check+anchor)
s=s.replace("'equipment_ui_packaging':equipment,", "'ship_label_editing':ship_labels,'equipment_ui_packaging':equipment,")
p.write_text(s)
print('SHIP_LABEL_UI_PREPARED expected_old_labels=true no_direct_shared_label_writes=true')
