package logbook.internal.gui;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.TextArea;
import javafx.util.StringConverter;
import logbook.internal.logger.LoggerHolder;
import logbook.plugin.api.QuestEditorDataV1;
import logbook.plugin.api.QuestEditorDataV1.Choice;

/**
 * 任務達成条件編集コントローラー
 */
public class QuestConditionEditController extends logbook.plugin.api.FeatureUiV1.Window {

    @FXML
    private ComboBox<Choice> questSelector;

    @FXML
    private javafx.scene.control.RadioButton normalQuestRadio;
    @FXML
    private javafx.scene.control.RadioButton customQuestRadio;
    @FXML
    private javafx.scene.control.ToggleGroup questTypeGroup;
    @FXML
    private Button removeBtn;

    @FXML
    private Label questType;
    @FXML
    private javafx.scene.control.TitledPane customSettingsPane;
    @FXML
    private TextField customNameField;
    @FXML
    private TextField customStartDateField;
    @FXML
    private ComboBox<String> resetType;
    @FXML
    private TextField yearlyResetMonth;

    @FXML
    private TextField filterArea;
    @FXML
    private TextField filterDeckIds;
    @FXML
    private CheckBox filterNoRetreat;
    @FXML
    private Label editNotice;
    private tools.jackson.databind.JsonNode originalDocument;
    private tools.jackson.databind.JsonNode loadedForm;
    private byte[] loadedBytes;
    private logbook.plugin.api.QuestDocumentsV1.Session documentSession;
    private boolean loadFailed;
    private boolean arrivalAreaConflict;

    // Overridden only by the isolated FXML test controller, not a runtime property.
    java.nio.file.Path questDirectory() {
        return java.nio.file.Paths.get("./customquest");
    }

    @FXML
    private VBox fleetConditionsBox;

    @FXML
    private VBox conditionsBox;

    @FXML
    void initialize() {
        try {
            this.questSelector.setConverter(new StringConverter<Choice>() {
                @Override
                public String toString(Choice object) {
                    return object == null ? null : object.label();
                }

                @Override
                public Choice fromString(String string) {
                    return null;
                }
            });

            // リストの表示数を20に設定
            this.questSelector.setVisibleRowCount(20);

            // ラジオボタンの切り替え
            this.questTypeGroup.selectedToggleProperty().addListener((ob, o, n) -> {
                this.updateQuestList();
            });

            this.updateQuestList();

            // ResetType初期化
            this.resetType.setItems(FXCollections.observableArrayList(QuestEditorDataV1.resetTypes()));

            // 任務選択時の動作
            this.questSelector.getSelectionModel().selectedItemProperty().addListener((ob, o, n) -> {
                if (n != null) {
                    this.setQuestData(n);
                }
            });

            // カスタム設定の表示制御
            this.customSettingsPane.visibleProperty().bind(this.customQuestRadio.selectedProperty());
            this.customSettingsPane.managedProperty().bind(this.customQuestRadio.selectedProperty());

            // イヤリーの時のみ入力可
            this.yearlyResetMonth.disableProperty()
                    .bind(this.resetType.getSelectionModel().selectedItemProperty().isNotEqualTo("イヤリー"));

            // 海域入力時の動作
            this.filterArea.textProperty().addListener((ob, o, n) -> {
                this.updateConditionsArea(n);
            });

        } catch (Exception e) {
            LoggerHolder.get().error("FXMLの初期化に失敗しました", e);
        }
    }

    private void setQuestData(Choice appQuest) {
        Choice quest = appQuest;
        if (quest != null) {
            // 除去ボタンの活性制御
            boolean isCustom = quest.id() >= 10001 && quest.id() <= 10005;
            this.removeBtn.setDisable(!isCustom);

            // Type
            this.questType.setText(quest.categoryName());

            // Captured metadata supplies defaults; the stored definition still overrides them.
            this.resetType.getSelectionModel().select(quest.defaultResetType());
            this.yearlyResetMonth.setText("");

            // Filter / Conditions Clear (do not carry arrival-mode guards into another quest).
            this.conditionsBox.getChildren().clear();
            this.arrivalAreaConflict = false;
            this.filterArea.setText("");
            this.filterDeckIds.clear();
            this.filterNoRetreat.setSelected(false);
            this.originalDocument = null;
            this.loadedForm = null;
            this.loadedBytes = null;
            this.documentSession = null;
            this.loadFailed = false;
            this.editNotice.setText("艦隊番号は1～4をカンマ区切り。空欄は制限なし。保存前のファイルは別名で保持します。");
            this.fleetConditionsBox.getChildren().clear();
            this.conditionsBox.getChildren().clear();

            this.customNameField.setText("");
            this.customStartDateField.setText(logbook.internal.util.DateUtil.nowString());

            // Load existing custom conditions
            this.loadQuestData(quest);
        }
    }

    @SuppressWarnings("unchecked")
    private void loadQuestData(Choice quest) {
        try {
            this.documentSession = logbook.plugin.api.QuestDocumentsV1.at(this.questDirectory()).open(quest.id());
            this.loadedBytes = this.documentSession.document().bytes();
            if (this.loadedBytes == null)
                return;
            this.originalDocument = QuestEditorDocument.parse(this.loadedBytes);
            var condition = QuestEditorDataV1.form(this.loadedBytes);

            if (condition != null) {
                // ResetType
                if (condition.resetType() != null) {
                    this.resetType.getSelectionModel().select(condition.resetType());
                }
                if (condition.yearlyResetMonth() != null) {
                    this.yearlyResetMonth.setText(String.valueOf(condition.yearlyResetMonth()));
                }

                if (condition.customName() != null) {
                    this.customNameField.setText(condition.customName());
                }
                if (condition.startDate() != null) {
                    this.customStartDateField.setText(condition.startDate());
                }

                if (condition.filter() != null) {
                    if (condition.filter().deckIds() != null)
                        this.filterDeckIds.setText(condition.filter().deckIds().stream()
                                .sorted().map(String::valueOf).collect(Collectors.joining(",")));
                    this.filterNoRetreat.setSelected(Boolean.TRUE.equals(condition.filter().noRetreat()));
                }

                // Filter Area
                // Note: Reconstruct Filter Text from Achievement Conditions to support 7-2-1
                // etc.
                // The logic below will handle setting filterArea.setText handles the simple
                // case if no achievement conditions exist.

                // Reconstruct Filter Text from Achievement Conditions to support 7-2-1 etc.
                if (condition.conditions() != null) {
                    List<String> areas = new java.util.ArrayList<>();
                    for (QuestEditorDataV1.Goal cond : condition.conditions()) {
                        if (cond.area() != null && !cond.area().isEmpty()) {
                            String area = cond.area().iterator().next();
                            if ("5-6".equals(area)) {
                                if ("G".equals(cond.cell())) {
                                    area = "5-6-1";
                                } else if ("N".equals(cond.cell())) {
                                    area = "5-6-2";
                                } else if ("Z".equals(cond.cell())) {
                                    area = "5-6-3";
                                }
                            } else if ("7-2".equals(area)) {
                                if ("G".equals(cond.cell())) {
                                    area = "7-2-1";
                                } else if ("M".equals(cond.cell())) {
                                    area = "7-2-2";
                                }
                            } else if ("7-3".equals(area)) {
                                if ("E".equals(cond.cell())) {
                                    area = "7-3-1";
                                } else if ("P".equals(cond.cell())) {
                                    area = "7-3-2";
                                }
                            } else if ("7-5".equals(area)) {
                                if ("K".equals(cond.cell())) {
                                    area = "7-5-1";
                                } else if ("Q".equals(cond.cell())) {
                                    area = "7-5-2";
                                } else if ("T".equals(cond.cell())) {
                                    area = "7-5-3";
                                }
                            }
                            areas.add(area);
                        }
                    }
                    String joined = String.join(",", areas);
                    this.filterArea.setText(joined);
                }

                // If filterArea is empty but filter.area exists (no conditions case?)
                if (this.filterArea.getText().isEmpty() && condition.filter() != null
                        && condition.filter().area() != null) {
                    this.filterArea.setText(String.join(",", condition.filter().area()));
                }

                // Fleet Conditions
                if (condition.filter() != null && condition.filter().fleet() != null) {
                    QuestEditorDataV1.Fleet fleet = condition.filter().fleet();
                    String globalOp = fleet.operator(); // AND or OR

                    if (fleet.conditions() != null) {
                        for (QuestEditorDataV1.Fleet sub : fleet.conditions()) {
                            this.addFleetCondition(null);
                            HBox row = (HBox) this.fleetConditionsBox.getChildren()
                                    .get(this.fleetConditionsBox.getChildren().size() - 1);

                            int idx = 0;
                            // Logic (Row 2+)
                            if (this.fleetConditionsBox.getChildren().size() > 1) {
                                ComboBox<String> logicCombo = (ComboBox<String>) row.getChildren().get(idx++);
                                logicCombo.getSelectionModel().select("OR".equals(globalOp) ? "または" : "かつ");
                            }

                            // Type/Name
                            ComboBox<String> typeCombo = (ComboBox<String>) row.getChildren().get(idx++);
                            boolean isStype = sub.stype() != null && !sub.stype().isEmpty();
                            typeCombo.getSelectionModel().select(isStype ? "艦種" : "艦名");

                            // Text
                            TextField tf = (TextField) row.getChildren().get(idx++);
                            if (isStype) {
                                tf.setText(String.join(",", sub.stype()));
                            } else if (sub.name() != null) {
                                tf.setText(String.join(",", sub.name()));
                            }

                            // Flagship / Second Ship
                            if (this.fleetConditionsBox.getChildren().size() == 1) {
                                CheckBox fs = (CheckBox) row.getChildren().get(idx++);
                                fs.setSelected(sub.order() != null && sub.order() == 1);
                            } else if (this.fleetConditionsBox.getChildren().size() == 2) {
                                // 2行目で、かつ要素数が足りてる(チェックボックスがある)場合
                                // Flagshipチェックボックスを追加するかどうかの判定は addFleetCondition で行われているので
                                // ここでは単に取得してセットする
                                if (idx < row.getChildren().size() && row.getChildren().get(idx) instanceof CheckBox) {
                                    CheckBox ss = (CheckBox) row.getChildren().get(idx++);
                                    ss.setSelected(sub.order() != null && sub.order() == 2);
                                }
                            }

                            // Count
                            TextField cf = (TextField) row.getChildren().get(idx++);
                            if (sub.count() != null) {
                                cf.setText(String.valueOf(sub.count()));
                            }
                            idx++; // Unit

                            // Compare
                            ComboBox<String> opCombo = (ComboBox<String>) row.getChildren().get(idx++);
                            if (sub.operator() != null) {
                                switch (sub.operator()) {
                                case "GE":
                                    opCombo.getSelectionModel().select("以上");
                                    break;
                                case "EQ":
                                    opCombo.getSelectionModel().select("等しい");
                                    break;
                                case "LE":
                                    opCombo.getSelectionModel().select("以下");
                                    break;
                                }
                            }
                        }
                    }
                }

                // Achievement Conditions (Rows are already created by filterArea listener)
                if (condition.conditions() != null) {
                    for (int i = 0; i < condition.conditions().size(); i++) {
                        if (i >= this.conditionsBox.getChildren().size())
                            break;

                        QuestEditorDataV1.Goal cond = condition.conditions().get(i);
                        HBox row = (HBox) this.conditionsBox.getChildren().get(i);

                        // row children: Label(Area), (Label, TextField)?, S, A, B, Count, Label
                        int idx = 1;
                        // Check if cell field exists
                        if (hasCellField(row)) {
                            idx++; // Label("マス")
                            TextField cellField = (TextField) row.getChildren().get(idx++);
                            if (cond.cell() != null) {
                                cellField.setText(cond.cell());
                            } else if (cond.cells() != null && !cond.cells().isEmpty()) {
                                cellField.setText(String.join(",", cond.cells()));
                            }
                        }

                        CheckBox s = (CheckBox) row.getChildren().get(idx++);
                        CheckBox a = (CheckBox) row.getChildren().get(idx++);
                        CheckBox b = (CheckBox) row.getChildren().get(idx++);
                        TextField countField = (TextField) row.getChildren().get(idx++);

                        if (cond.rank() != null) {
                            s.setSelected(cond.rank().contains("S"));
                            a.setSelected(cond.rank().contains("A"));
                            b.setSelected(cond.rank().contains("B"));
                        }
                        countField.setText(String.valueOf(cond.count()));
                        CheckBox arrival = arrivalControl(row);
                        if (arrival != null)
                            arrival.setSelected(cond.arrival());
                    }
                }
            }
            this.loadedForm = formMapper().valueToTree(this.readForm());
            this.editNotice.setText("表示に対応していない既存条件も保持します。表現できない構造の変更は保存を中止します。");
        } catch (Exception e) {
            this.loadFailed = true;
            this.editNotice.setText("読込に失敗したため保存できません。元ファイルは変更しません: " + e.getMessage());
            LoggerHolder.get().error("カスタム任務設定の読み込みに失敗しました", e);
        }
    }

    private void updateConditionsArea(String areaText) {
        // Keep entered values when the same area is retained (including whitespace edits).
        // setQuestData first clears the area, so controls never leak into another quest.
        var retained = new java.util.LinkedHashMap<String, java.util.Deque<HBox>>();
        for (javafx.scene.Node node : this.conditionsBox.getChildren()) {
            HBox row = (HBox) node;
            String area = ((Label) row.getChildren().get(0)).getText();
            retained.computeIfAbsent(area, key -> new java.util.ArrayDeque<>()).add(row);
        }
        // An active goal row cannot silently turn into a battle row when its map is edited.
        long newArrivalRows = areaText == null ? 0
                : Arrays.stream(areaText.split(","))
                        .map(String::trim).filter("1-6"::equals).count();
        var oldArrivalRows = retained.get("1-6");
        if (oldArrivalRows != null && newArrivalRows < oldArrivalRows.size()
                && oldArrivalRows.stream().anyMatch(row -> arrivalControl(row) != null
                        && arrivalControl(row).isSelected())) {
            this.arrivalAreaConflict = true;
            this.editNotice.setText("港到達を解除してから1-6を削除・変更してください。保存は中止しています。");
            return;
        }
        if (this.arrivalAreaConflict)
            this.editNotice.setText("海域の矛盾を解消しました。既存条件を保持して保存します。");
        this.arrivalAreaConflict = false;
        this.conditionsBox.getChildren().clear();
        if (areaText == null || areaText.isEmpty()) {
            return;
        }

        // 選択されているクエストがカスタム任務かチェック
        Choice appQuest = this.questSelector.getSelectionModel().getSelectedItem();
        boolean isCustom = false;
        if (appQuest != null) {
            int no = appQuest.id();
            isCustom = no >= 10001 && no <= 10005;
        }

        String[] areas = areaText.split(",");
        for (String area : areas) {
            String val = area.trim();
            if (!val.isEmpty()) {
                var previous = retained.get(val);
                if (previous != null && !previous.isEmpty()) {
                    this.conditionsBox.getChildren().add(previous.removeFirst());
                    continue;
                }
                HBox row = new HBox(10);
                row.setAlignment(Pos.CENTER_LEFT);

                Label label = new Label(val);
                label.setPrefWidth(50);

                CheckBox checkS = new CheckBox("S");
                CheckBox checkA = new CheckBox("A");
                CheckBox checkB = new CheckBox("B");

                TextField count = new TextField();
                count.setPrefWidth(50);
                Label countLabel = new Label("回");

                row.getChildren().add(label);

                // マス入力欄を追加 (5-6, 7-2, 7-3, 7-5) または カスタム任務
                if (val.startsWith("5-6") || val.startsWith("7-2") || val.startsWith("7-3") || val.startsWith("7-5")
                        || isCustom) {
                    row.getChildren().add(new Label("マス"));
                    TextField cellField = new TextField();
                    cellField.setPrefWidth(40);
                    // 初期値を設定
                    if (val.equals("5-6-1")) {
                        cellField.setText("G");
                    } else if (val.equals("5-6-2")) {
                        cellField.setText("N");
                    } else if (val.equals("5-6-3")) {
                        cellField.setText("Z");
                    } else if (val.equals("7-2-1")) {
                        cellField.setText("G");
                    } else if (val.equals("7-2-2")) {
                        cellField.setText("M");
                    } else if (val.equals("7-3-1")) {
                        cellField.setText("E");
                    } else if (val.equals("7-3-2")) {
                        cellField.setText("P");
                    } else if (val.equals("7-5-1")) {
                        cellField.setText("K");
                    } else if (val.equals("7-5-2")) {
                        cellField.setText("Q");
                    } else if (val.equals("7-5-3")) {
                        cellField.setText("T");
                    }
                    row.getChildren().add(cellField);
                }

                row.getChildren().addAll(checkS, checkA, checkB, count, countLabel);
                if ("1-6".equals(val)) {
                    CheckBox arrival = new CheckBox("港到達");
                    arrival.setId("arrivalMode");
                    arrival.setMnemonicParsing(false);
                    arrival.setOnAction(event -> this.updateConditionsArea(this.filterArea.getText()));
                    arrival.setTooltip(new javafx.scene.control.Tooltip(
                            "1-6のゴール到達記録で数えます。戦闘回数からは推測しません。"));
                    // Keep unsaved battle inputs so switching back does not discard them.
                    checkS.disableProperty().bind(arrival.selectedProperty());
                    checkA.disableProperty().bind(arrival.selectedProperty());
                    checkB.disableProperty().bind(arrival.selectedProperty());
                    if (hasCellField(row))
                        row.getChildren().get(2).disableProperty().bind(arrival.selectedProperty());
                    row.getProperties().put("arrivalControl", arrival);
                    row.getChildren().add(arrival);
                }
                this.conditionsBox.getChildren().add(row);
            }
        }
    }

    private static boolean hasCellField(HBox row) {
        return row.getChildren().size() > 2 && row.getChildren().get(1) instanceof Label
                && row.getChildren().get(2) instanceof TextField;
    }

    private static CheckBox arrivalControl(HBox row) {
        return (CheckBox) row.getProperties().get("arrivalControl");
    }

    /**
     * 艦隊条件追加
     *
     * @param e ActionEvent
     */
    @FXML
    void addFleetCondition(javafx.event.ActionEvent e) {
        HBox row = new HBox(3); // Spacing 3px
        row.setAlignment(Pos.CENTER_LEFT);

        int rowsInfo = this.fleetConditionsBox.getChildren().size();

        // 2行目以降は論理演算子を追加
        if (rowsInfo > 0) {
            ComboBox<String> logic = new ComboBox<>(FXCollections.observableArrayList("かつ", "または"));
            logic.getSelectionModel().select("かつ");
            logic.setPrefWidth(80);
            row.getChildren().add(logic);
        }

        // Type/Name Selector
        ComboBox<String> typeSelector = new ComboBox<>(FXCollections.observableArrayList("艦種", "艦名"));
        typeSelector.getSelectionModel().select("艦種"); // Default
        typeSelector.setPrefWidth(70);
        row.getChildren().add(typeSelector);

        TextField text = new TextField();
        text.setPromptText("例：駆逐艦,赤城");
        HBox.setHgrow(text, Priority.ALWAYS);

        // 入力補正リスナー
        text.textProperty().addListener((ob, o, n) -> {
            if (n != null && (n.contains("「") || n.contains("」"))) {
                // 「A」「B」 -> A,B
                String replaced = n.replaceAll("」\\s*「", ",")
                        .replaceAll("「", "")
                        .replaceAll("」", "");
                if (!replaced.equals(n)) {
                    text.setText(replaced);
                }
            }
        });

        row.getChildren().add(text);

        TextField count = new TextField();
        count.setPrefWidth(30);
        Label unit = new Label("隻");

        ComboBox<String> compare = new ComboBox<>(FXCollections.observableArrayList("以上", "等しい", "以下"));

        // 旗艦チェックボックス (1行目のみ)
        CheckBox flagship = null;
        if (rowsInfo == 0) {
            flagship = new CheckBox("旗艦");
            row.getChildren().add(flagship);
        } else if (rowsInfo == 1) {
            // 2行目のみ、かつ1行目が旗艦指定の場合に「二番艦」を追加
            HBox firstRow = (HBox) this.fleetConditionsBox.getChildren().get(0);
            // 1行目の要素構造: [Type], [Text], [Flagship], [Count], [Unit], [Compare], [Del]
            // Flagship は index 2 にあるはず
            if (firstRow.getChildren().size() > 2 && firstRow.getChildren().get(2) instanceof CheckBox) {
                CheckBox firstFlagship = (CheckBox) firstRow.getChildren().get(2);
                if (firstFlagship.isSelected()) {
                    CheckBox secondShip = new CheckBox("二番艦");
                    row.getChildren().add(secondShip);

                    // 2番艦チェックボックスの制御
                    secondShip.selectedProperty().addListener((ob, o, n) -> {
                        this.updateInputControl(n, count, compare);
                    });
                    // 隻数入力欄の制御
                    count.textProperty().addListener((ob, o, n) -> {
                        this.updateCheckboxControl(n, secondShip);
                    });
                }
            }
        }

        row.getChildren().addAll(count, unit);

        // 比較演算子
        compare.getSelectionModel().select("以上");
        compare.setPrefWidth(80);
        row.getChildren().add(compare);

        // 旗艦チェックボックスの制御
        if (flagship != null) {
            CheckBox f = flagship;
            f.selectedProperty().addListener((ob, o, n) -> {
                this.updateInputControl(n, count, compare);
            });
            // 隻数入力欄の制御
            count.textProperty().addListener((ob, o, n) -> {
                this.updateCheckboxControl(n, f);
            });
        }

        Button remove = new Button("削除");
        remove.setOnAction(ev -> {
            this.fleetConditionsBox.getChildren().remove(row);
            this.updateFleetDeleteButtons();
        });
        row.getChildren().add(remove);

        this.fleetConditionsBox.getChildren().add(row);

        this.updateFleetDeleteButtons();
    }

    /**
     * 削除ボタンの有効/無効制御
     * 末尾の行のみ有効にする
     */
    private void updateFleetDeleteButtons() {
        javafx.collections.ObservableList<javafx.scene.Node> children = this.fleetConditionsBox.getChildren();
        int size = children.size();
        for (int i = 0; i < size; i++) {
            javafx.scene.Node node = children.get(i);
            if (node instanceof HBox) {
                HBox row = (HBox) node;
                // 削除ボタンは末尾にある前提
                if (!row.getChildren().isEmpty()) {
                    javafx.scene.Node lastChild = row.getChildren().get(row.getChildren().size() - 1);
                    if (lastChild instanceof Button) {
                        Button deleteBtn = (Button) lastChild;
                        // 最終行のみ有効
                        deleteBtn.setDisable(i != size - 1);
                    }
                }
            }
        }
    }

    /**
     * 入力制御の更新
     *
     * @param selected 選択状態
     * @param count    隻数入力欄
     * @param compare  比較演算子
     */
    private void updateInputControl(boolean selected, TextField count, ComboBox<String> compare) {
        if (selected) {
            count.setText("");
            count.setDisable(true);
            compare.setDisable(true);
        } else {
            count.setDisable(false);
            compare.setDisable(false);
        }
    }

    /**
     * 入力制御の更新（隻数入力時）
     *
     * @param text     入力内容
     * @param checkbox チェックボックス
     */
    private void updateCheckboxControl(String text, CheckBox checkbox) {
        if (text != null && !text.isEmpty()) {
            checkbox.setDisable(true);
        } else {
            checkbox.setDisable(false);
        }
    }

    /**
     * 艦種一覧を表示
     *
     * @param e ActionEvent
     */
    @FXML
    void showShipTypeList(javafx.event.ActionEvent e) {
        StringBuilder sb = new StringBuilder();
        for (var group : QuestEditorDataV1.shipTypeGroups()) {
            sb.append(String.join(", ", group)).append("\n");
        }

        Alert alert = new Alert(AlertType.INFORMATION);
        alert.initOwner(this.questSelector.getScene().getWindow());
        alert.setTitle("艦種一覧");
        alert.setHeaderText("艦種グループに含まれる艦種");
        alert.getDialogPane().setContent(new TextArea(sb.toString()));
        alert.showAndWait();
    }

    @SuppressWarnings("unchecked")
    logbook.bean.AppQuestCondition readForm() {
        if (this.arrivalAreaConflict)
            throw new IllegalArgumentException("港到達を解除してから1-6を削除・変更してください。");
        Choice appQuest = this.questSelector.getSelectionModel().getSelectedItem();
        if (appQuest == null)
            throw new IllegalStateException("任務が選択されていません。");
        // Root Object
        logbook.bean.AppQuestCondition condition = new logbook.bean.AppQuestCondition();

        // Type & ResetType
        int category = appQuest.category();
        condition.setType(
                category == 4 ? logbook.bean.AppQuestCondition.Type.遠征 : logbook.bean.AppQuestCondition.Type.出撃);
        condition.setResetType(this.resetType.getSelectionModel().getSelectedItem());

        if ("イヤリー".equals(condition.getResetType())) {
            try {
                condition.setYearlyResetMonth(Integer.parseInt(this.yearlyResetMonth.getText()));
            } catch (NumberFormatException ex) {
                // Ignore or set default?
            }
        }

        // Filter
        logbook.bean.AppQuestCondition.FilterCondition filter = new logbook.bean.AppQuestCondition.FilterCondition();
        filter.setDeckIds(QuestEditorDocument.deckIds(this.filterDeckIds.getText()));
        if (this.filterNoRetreat.isSelected())
            filter.setNoRetreat(true);
        String areaText = this.filterArea.getText();
        if (areaText != null && !areaText.isEmpty()) {
            filter.setArea(Arrays.stream(areaText.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> {
                        if ("5-6-1".equals(s) || "5-6-2".equals(s) || "5-6-3".equals(s)) {
                            return "5-6";
                        }
                        if ("7-2-1".equals(s) || "7-2-2".equals(s)) {
                            return "7-2";
                        }
                        if ("7-3-1".equals(s) || "7-3-2".equals(s)) {
                            return "7-3";
                        }
                        if ("7-5-1".equals(s) || "7-5-2".equals(s) || "7-5-3".equals(s)) {
                            return "7-5";
                        }
                        return s;
                    })
                    .collect(Collectors.toSet()));
        }

        // Fleet Conditions
        logbook.bean.AppQuestCondition.FleetCondition fleet = new logbook.bean.AppQuestCondition.FleetCondition();
        List<logbook.bean.AppQuestCondition.FleetCondition> subConditions = new java.util.ArrayList<>();

        String globalLogic = "AND"; // Default

        for (int i = 0; i < this.fleetConditionsBox.getChildren().size(); i++) {
            HBox row = (HBox) this.fleetConditionsBox.getChildren().get(i);

            logbook.bean.AppQuestCondition.FleetCondition sub = new logbook.bean.AppQuestCondition.FleetCondition();

            // Row components check
            // Row 1: [Type/Name], [Text], [Flagship]?, [Count], [Unit], [Compare], [Del]
            // Row 2+: [Logic], [Type/Name], [Text], [Count], [Unit], [Compare], [Del]

            int idx = 0;

            // Logic (Row 2+)
            if (i > 0) {
                ComboBox<String> logicCombo = (ComboBox<String>) row.getChildren().get(idx++);
                String l = logicCombo.getSelectionModel().getSelectedItem(); // "艦種" or "艦名"
                if (i == 1) {
                    globalLogic = "または".equals(l) ? "OR" : "AND";
                }
            }

            // Type/Name Selector
            ComboBox<String> typeCombo = (ComboBox<String>) row.getChildren().get(idx++);
            String typeVal = typeCombo.getSelectionModel().getSelectedItem(); // "艦種" or "艦名"

            // Text
            TextField tf = (TextField) row.getChildren().get(idx++);
            String text = tf.getText();
            if (text != null && !text.isEmpty()) {
                java.util.LinkedHashSet<String> valSet = Arrays.stream(text.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

                if ("艦種".equals(typeVal)) {
                    sub.setStype(valSet);
                } else {
                    sub.setName(valSet);
                }
            }

            // Flagship / Second Ship
            if (idx < row.getChildren().size() && row.getChildren().get(idx) instanceof CheckBox) {
                CheckBox cb = (CheckBox) row.getChildren().get(idx++);
                if (cb.isSelected()) {
                    if (i == 0) {
                        sub.setOrder(1); // Flagship
                    } else if (i == 1) {
                        sub.setOrder(2); // Second Ship
                    }
                }
            }

            // Count
            TextField cf = (TextField) row.getChildren().get(idx++);
            try {
                sub.setCount(Integer.parseInt(cf.getText()));
            } catch (Exception ex) {
                // ignore
            }

            // Unit Label
            idx++;

            // Compare Operator
            ComboBox<String> opCombo = (ComboBox<String>) row.getChildren().get(idx++);
            if (sub.getOrder() == null) {
                String op = opCombo.getSelectionModel().getSelectedItem();
                switch (op) {
                case "以上":
                    sub.setOperator("GE");
                    break;
                case "等しい":
                    sub.setOperator("EQ");
                    break;
                case "以下":
                    sub.setOperator("LE");
                    break;
                }
            }

            subConditions.add(sub);
        }

        if (!subConditions.isEmpty()) {
            fleet.setOperator(globalLogic);
            fleet.setConditions(subConditions);
            filter.setFleet(fleet);
        }
        condition.setFilter(filter);

        // Custom Name
        String customName = this.customNameField.getText();
        if (customName == null || customName.trim().isEmpty()) {
            int no = appQuest.id();
            if (no >= 10001 && no <= 10005) {
                customName = "カスタム任務" + (no - 10000);
            }
        }
        condition.setCustomName(customName);

        // Start Date
        condition.setStartDate(this.customStartDateField.getText());

        // Achievement Conditions
        for (javafx.scene.Node node : this.conditionsBox.getChildren()) {
            HBox row = (HBox) node;
            // Label(Area), [Label, TextField]?, S, A, B, Count, Label
            Label areaLabel = (Label) row.getChildren().get(0);

            int idx = 1;
            String cellValue = null;
            if (hasCellField(row)) {
                idx++; // Label("マス")
                TextField cellField = (TextField) row.getChildren().get(idx++);
                cellValue = cellField.getText();
            }

            CheckBox s = (CheckBox) row.getChildren().get(idx++);
            CheckBox a = (CheckBox) row.getChildren().get(idx++);
            CheckBox b = (CheckBox) row.getChildren().get(idx++);
            TextField countField = (TextField) row.getChildren().get(idx++);

            logbook.bean.AppQuestCondition.Condition cond = new logbook.bean.AppQuestCondition.Condition();
            cond.setBoss(true); // Default

            String labelText = areaLabel.getText();
            if ("5-6-1".equals(labelText)) {
                cond.setArea(new java.util.LinkedHashSet<>(Arrays.asList("5-6")));
                cond.setCell("G");
            } else if ("5-6-2".equals(labelText)) {
                cond.setArea(new java.util.LinkedHashSet<>(Arrays.asList("5-6")));
                cond.setCell("N");
            } else if ("5-6-3".equals(labelText)) {
                cond.setArea(new java.util.LinkedHashSet<>(Arrays.asList("5-6")));
                cond.setCell("Z");
            } else if ("7-2-1".equals(labelText)) {
                cond.setArea(new java.util.LinkedHashSet<>(Arrays.asList("7-2")));
                cond.setCell("G");
            } else if ("7-2-2".equals(labelText)) {
                cond.setArea(new java.util.LinkedHashSet<>(Arrays.asList("7-2")));
                cond.setCell("M");
            } else if ("7-3-1".equals(labelText)) {
                cond.setArea(new java.util.LinkedHashSet<>(Arrays.asList("7-3")));
                cond.setCell("E");
            } else if ("7-3-2".equals(labelText)) {
                cond.setArea(new java.util.LinkedHashSet<>(Arrays.asList("7-3")));
                cond.setCell("P");
            } else if ("7-5-1".equals(labelText)) {
                cond.setArea(new java.util.LinkedHashSet<>(Arrays.asList("7-5")));
                cond.setCell("K");
            } else if ("7-5-2".equals(labelText)) {
                cond.setArea(new java.util.LinkedHashSet<>(Arrays.asList("7-5")));
                cond.setCell("Q");
            } else if ("7-5-3".equals(labelText)) {
                cond.setArea(new java.util.LinkedHashSet<>(Arrays.asList("7-5")));
                cond.setCell("T");
            } else {
                cond.setArea(new java.util.LinkedHashSet<>(Arrays.asList(labelText)));
            }

            // マス入力を反映 (ユーザー入力を優先)
            if (cellValue != null && !cellValue.isEmpty()) {
                if (cellValue.contains(",")) {
                    cond.setCells(new java.util.LinkedHashSet<>(Arrays.asList(cellValue.split(","))));
                    cond.setCell(null);
                } else {
                    cond.setCell(cellValue);
                    cond.setCells(null);
                }
            }

            java.util.LinkedHashSet<String> ranks = new java.util.LinkedHashSet<>();
            if (s.isSelected())
                ranks.add("S");
            if (a.isSelected())
                ranks.add("A");
            if (b.isSelected())
                ranks.add("B");
            if (!ranks.isEmpty()) {
                cond.setRank(ranks);
            }

            CheckBox arrival = arrivalControl(row);
            if (arrival != null && arrival.isSelected()) {
                String value = countField.getText();
                if (value == null || !value.trim().matches("[0-9]+"))
                    throw new IllegalArgumentException("港到達の回数は1以上の整数で入力してください。");
                try {
                    cond.setCount(Integer.parseInt(value.trim()));
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("港到達の回数は2147483647以下で入力してください。", ex);
                }
                if (cond.getCount() <= 0)
                    throw new IllegalArgumentException("港到達の回数は1以上の整数で入力してください。");
                cond.setArrival(true);
                cond.setBoss(false);
                cond.setRank(null);
                cond.setCell(null);
                cond.setCells(null);
            } else {
                try {
                    cond.setCount(Integer.parseInt(countField.getText()));
                } catch (Exception ex) {
                    cond.setCount(1);
                }
            }

            condition.getConditions().add(cond);
        }

        return condition;
    }

    private static tools.jackson.databind.ObjectMapper formMapper() {
        var mapper = QuestEditorDocument.mapper();
        mapper = mapper.rebuild()
                .changeDefaultPropertyInclusion(value -> com.fasterxml.jackson.annotation.JsonInclude.Value.construct(
                        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL,
                        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL))
                .build();
        mapper = mapper.rebuild()
                .addMixIn(logbook.bean.AppQuestCondition.FleetCondition.class, FleetConditionMixin.class).build();
        mapper = mapper.rebuild().addMixIn(logbook.bean.AppQuestCondition.Condition.class, ConditionMixin.class)
                .build();
        mapper = mapper.rebuild().addMixIn(logbook.bean.AppQuestCondition.class, AppQuestConditionMixin.class).build();
        return mapper;
    }

    java.nio.file.Path saveDocument() throws java.io.IOException {
        try {
            if (this.loadFailed)
                throw new java.io.IOException("読込失敗のため保存を中止しました。");
            var currentForm = formMapper().valueToTree(this.readForm());
            var output = this.originalDocument == null ? currentForm
                    : QuestEditorDocument.merge(this.originalDocument, this.loadedForm, currentForm);
            QuestEditorDocument.validate(output);
            var appQuest = this.questSelector.getSelectionModel().getSelectedItem();
            if (this.documentSession == null || this.documentSession.document().questId() != appQuest.id())
                throw new java.io.IOException("読込時と保存対象が異なります。対象を再読込してください。");
            // Exact no-edit saves retain comments/formatting as well as all unrepresented fields.
            byte[] bytes = this.originalDocument != null && output.equals(this.originalDocument)
                    ? this.loadedBytes
                    : formMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(output);
            var plan = this.documentSession.prepareSave(bytes);
            // Explicit Save button / package-local test hook. The session owns the exact captured target.
            var backup = this.documentSession.apply(plan, true).retained();
            this.loadedBytes = bytes.clone();
            this.originalDocument = output.deepCopy();
            this.loadedForm = currentForm.deepCopy();
            return backup;
        } catch (tools.jackson.core.JacksonException jsonCompatibilityFailure) {
            throw new java.io.IOException("Invalid or unreadable JSON", jsonCompatibilityFailure);
        }
    }

    @FXML
    void save(javafx.event.ActionEvent e) {
        Choice appQuest = this.questSelector.getSelectionModel().getSelectedItem();
        if (appQuest == null)
            return;
        try {
            java.nio.file.Path backup = this.saveDocument();
            // Show Alert
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.INFORMATION);
            alert.initOwner(this.questSelector.getScene().getWindow());
            alert.setTitle("保存成功");
            alert.setHeaderText(null);
            alert.setContentText("./customquest/" + appQuest.id() + ".jsonを出力しました。"
                    + (backup == null ? "" : "\n変更前の保存先: " + backup));
            alert.showAndWait();

        } catch (Exception ex) {
            LoggerHolder.get().error("保存に失敗しました", ex);
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.ERROR);
            alert.initOwner(this.questSelector.getScene().getWindow());
            alert.setTitle("保存失敗");
            alert.setHeaderText("保存に失敗しました");
            alert.setContentText(ex.getMessage());
            alert.showAndWait();
        }
    }

    // Isolated FXML tests may supply synthetic choices without altering global collections.
    QuestEditorDataV1.Selection editorChoices(boolean custom) {
        return QuestEditorDataV1.choices(custom);
    }

    private void updateQuestList() {
        var selection = this.editorChoices(!this.normalQuestRadio.isSelected());
        this.questSelector.setItems(FXCollections.observableArrayList(selection.choices()));
        this.questSelector.getSelectionModel().clearSelection();
        if (this.removeBtn != null)
            this.removeBtn.setDisable(true);
        if (this.editNotice != null && !selection.diagnostics().isEmpty())
            this.editNotice.setText(String.join("\n", selection.diagnostics()));
    }

    private logbook.plugin.api.QuestDocumentsV1.Plan prepareRetirementDocument() throws java.io.IOException {
        Choice appQuest = this.questSelector.getSelectionModel().getSelectedItem();
        if (appQuest == null || this.documentSession == null
                || this.documentSession.document().questId() != appQuest.id())
            throw new java.io.IOException("読込時と退避対象が異なります。対象を再読込してください。");
        return this.documentSession.prepareRetirement();
    }

    private java.nio.file.Path applyRetirementDocument(logbook.plugin.api.QuestDocumentsV1.Plan plan)
            throws java.io.IOException {
        var selected = this.questSelector.getSelectionModel().getSelectedItem();
        if (selected == null || this.documentSession == null
                || selected.id() != plan.questId()
                || this.documentSession.document().questId() != plan.questId())
            throw new java.io.IOException("確認中に選択対象が変わりました。退避せず、対象を再確認してください。");
        var result = this.documentSession.apply(plan, true);
        if (result.retained() != null) {
            this.setQuestData(this.questSelector.getSelectionModel().getSelectedItem());
            this.removeBtn.setDisable(true);
        }
        return result.retained();
    }

    // Retained explicit package-local test hook; user-facing retirement confirms the captured plan below.
    java.nio.file.Path removeDocument() throws java.io.IOException {
        return applyRetirementDocument(prepareRetirementDocument());
    }

    @FXML
    void remove(javafx.event.ActionEvent e) {
        Choice appQuest = this.questSelector.getSelectionModel().getSelectedItem();
        if (appQuest == null) {
            return;
        }
        int no = appQuest.id();
        if (no < 10001 || no > 10005) {
            return;
        }

        try {
            var plan = this.prepareRetirementDocument();
            var preparedSession = this.documentSession;
            if (plan.existing()) {
                var proceed = new javafx.scene.control.ButtonType("原本を保持して退避",
                        javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
                var confirm = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION,
                        "対象: " + plan.target() + "\n読込時SHA-256: " + plan.expectedSha256()
                                + "\n\n有効な定義名から外し、同じフォルダーへ別名で原本と検証済みコピーを残します。"
                                + "\nこのカスタム枠は空きます。受諾履歴・到達記録・他の設定は変更しません。"
                                + "\nファイルが読込後に変更されていた場合は退避を中止します。",
                        proceed, javafx.scene.control.ButtonType.CANCEL);
                confirm.initOwner(this.questSelector.getScene().getWindow());
                confirm.setTitle("カスタム任務の退避確認");
                confirm.setHeaderText("完全削除ではなく、復旧可能な別名退避です");
                if (confirm.showAndWait().filter(proceed::equals).isEmpty()) {
                    preparedSession.apply(plan, false);
                    return;
                }
            }
            java.nio.file.Path retained = this.applyRetirementDocument(plan);

            if (retained != null) {

                javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                        javafx.scene.control.Alert.AlertType.INFORMATION);
                alert.initOwner(this.questSelector.getScene().getWindow());
                alert.setTitle("除去成功");
                alert.setHeaderText(null);
                alert.setContentText("./customquest/" + no + ".json を除去しました。\n元設定の保存先: " + retained);
                alert.showAndWait();
            } else {
                javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                        javafx.scene.control.Alert.AlertType.WARNING);
                alert.initOwner(this.questSelector.getScene().getWindow());
                alert.setTitle("除去");
                alert.setHeaderText(null);
                alert.setContentText("削除対象のカスタム任務設定ファイルが存在しませんでした。");
                alert.showAndWait();
            }
        } catch (Exception ex) {
            LoggerHolder.get().error("削除に失敗しました", ex);
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.ERROR);
            alert.initOwner(this.questSelector.getScene().getWindow());
            alert.setTitle("削除失敗");
            alert.setHeaderText("削除に失敗しました");
            alert.setContentText(ex.getMessage());
            alert.showAndWait();
        }
    }

    // Mixin classes for Jackson serialization control
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({ "difference" })
    abstract class FleetConditionMixin {
        java.util.LinkedHashSet<String> stype;
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({ "start" })
    abstract class ConditionMixin {
        // Do not add an arrival capability requirement to ordinary battle-only documents.
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT)
        public abstract boolean isArrival();
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({ "collectStypeInternal" })
    abstract class AppQuestConditionMixin {
    }
}
