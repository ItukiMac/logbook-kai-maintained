package logbook.internal.gui;

import logbook.plugin.api.FeatureUiV1;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.SortedList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.chart.PieChart;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableColumn.SortType;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.control.cell.TreeItemPropertyValueFactory;
import javafx.util.Duration;
import logbook.bean.Mission;
import logbook.bean.MissionCollection;
import logbook.internal.Tuple;
import logbook.internal.Tuple.Pair;
import logbook.plugin.api.RecordDataV1;
import logbook.plugin.api.RecordDataV1.Period;
import logbook.plugin.api.RecordDataV1.MissionRow;
import logbook.internal.logger.LoggerHolder;

/**
 * 遠征ログ
 *
 */
public class MissionLogController extends FeatureUiV1.Window {

    @FXML
    private SplitPane splitPane1;

    @FXML
    private SplitPane splitPane2;

    @FXML
    private SplitPane splitPane3;

    /** 統計 */
    @FXML
    private TreeTableView<MissionRecordCollect> collect;

    /** 集計 */
    @FXML
    private TreeTableColumn<MissionRecordCollect, String> unit;

    /** 大成功 */
    @FXML
    private TreeTableColumn<MissionRecordCollect, Integer> successGood;

    /** 成功 */
    @FXML
    private TreeTableColumn<MissionRecordCollect, Integer> success;

    /** 失敗 */
    @FXML
    private TreeTableColumn<MissionRecordCollect, Integer> fail;

    /** 詳細 */
    @FXML
    private TableView<MissionRecordDetail> detail;

    /** 行番号 */
    @FXML
    private TableColumn<MissionRecordDetail, Integer> row;

    /** 日付 */
    @FXML
    private TableColumn<MissionRecordDetail, String> date;

    /** 遠征名 */
    @FXML
    private TableColumn<MissionRecordDetail, String> name;

    /** 結果 */
    @FXML
    private TableColumn<MissionRecordDetail, String> result;

    /** 燃料 */
    @FXML
    private TableColumn<MissionRecordDetail, Integer> fuel;

    /** 弾薬 */
    @FXML
    private TableColumn<MissionRecordDetail, Integer> ammo;

    /** 鋼材 */
    @FXML
    private TableColumn<MissionRecordDetail, Integer> metal;

    /** ボーキ */
    @FXML
    private TableColumn<MissionRecordDetail, Integer> bauxite;

    /** アイテム1 */
    @FXML
    private TableColumn<MissionRecordDetail, String> item1name;

    /** アイテム1 */
    @FXML
    private TableColumn<MissionRecordDetail, String> item1count;

    /** アイテム2 */
    @FXML
    private TableColumn<MissionRecordDetail, String> item2name;

    /** アイテム2 */
    @FXML
    private TableColumn<MissionRecordDetail, String> item2count;

    /** 集計 */
    @FXML
    private TableView<MissionAggregate> aggregate;

    /** 資材 */
    @FXML
    private TableColumn<MissionAggregate, String> resource;

    /** 個数 */
    @FXML
    private TableColumn<MissionAggregate, Integer> count;

    /** 平均 */
    @FXML
    private TableColumn<MissionAggregate, Double> average;

    @FXML
    private PieChart chart;

    /** ログ */
    private Map<Period, List<MissionRow>> logMap = new EnumMap<>(Period.class);

    /** 詳細 */
    private ObservableList<MissionRecordDetail> details = FXCollections.observableArrayList();

    /** 集計 */
    private ObservableList<MissionAggregate> aggregates = FXCollections.observableArrayList();

    @FXML
    void initialize() {
        FeatureUiV1.Tables.setVisible(this.detail, this.getClass() + "#" + "detail");
        FeatureUiV1.Tables.setVisible(this.aggregate, this.getClass() + "#" + "aggregate");
        // SplitPaneの分割サイズ
        Timeline x = new Timeline();
        x.getKeyFrames().add(new KeyFrame(Duration.millis(1), (e) -> {
            FeatureUiV1.Controls.setSplitWidth(this.splitPane1, this.getClass() + "#" + "splitPane1");
            FeatureUiV1.Controls.setSplitWidth(this.splitPane2, this.getClass() + "#" + "splitPane2");
            FeatureUiV1.Controls.setSplitWidth(this.splitPane3, this.getClass() + "#" + "splitPane3");
        }));
        x.play();

        // 集計
        this.collect.setShowRoot(false);
        this.collect.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        this.unit.setCellValueFactory(new TreeItemPropertyValueFactory<>("unit"));
        this.successGood.setCellValueFactory(new TreeItemPropertyValueFactory<>("successGood"));
        this.success.setCellValueFactory(new TreeItemPropertyValueFactory<>("success"));
        this.fail.setCellValueFactory(new TreeItemPropertyValueFactory<>("fail"));

        // 詳細
        SortedList<MissionRecordDetail> sortedList = new SortedList<>(this.details);
        this.detail.setItems(sortedList);
        sortedList.comparatorProperty().bind(this.detail.comparatorProperty());
        this.detail.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        this.detail.setOnKeyPressed(FeatureUiV1.Tables::defaultOnKeyPressedHandler);

        this.row.setCellFactory(FeatureUiV1.Tables.getRowCountCellFactory());
        this.date.setCellValueFactory(new PropertyValueFactory<>("date"));
        this.name.setCellValueFactory(new PropertyValueFactory<>("name"));
        this.result.setCellValueFactory(new PropertyValueFactory<>("result"));
        this.fuel.setCellValueFactory(new PropertyValueFactory<>("fuel"));
        this.ammo.setCellValueFactory(new PropertyValueFactory<>("ammo"));
        this.metal.setCellValueFactory(new PropertyValueFactory<>("metal"));
        this.bauxite.setCellValueFactory(new PropertyValueFactory<>("bauxite"));
        this.item1name.setCellValueFactory(new PropertyValueFactory<>("item1name"));
        this.item1count.setCellValueFactory(new PropertyValueFactory<>("item1count"));
        this.item2name.setCellValueFactory(new PropertyValueFactory<>("item2name"));
        this.item2count.setCellValueFactory(new PropertyValueFactory<>("item2count"));

        // 集計
        SortedList<MissionAggregate> sortedList2 = new SortedList<>(this.aggregates);
        this.aggregate.setItems(sortedList2);
        sortedList2.comparatorProperty().bind(this.aggregate.comparatorProperty());
        this.aggregate.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        this.aggregate.setOnKeyPressed(FeatureUiV1.Tables::defaultOnKeyPressedHandler);
        this.resource.setCellValueFactory(new PropertyValueFactory<>("resource"));
        this.count.setCellValueFactory(new PropertyValueFactory<>("count"));
        this.average.setCellValueFactory(new PropertyValueFactory<>("average"));

        this.readLog();
        this.setCollect();

        this.collect
                .setSortPolicy((params) -> sortCollect(params.getSortOrder(), this.collect.getRoot().getChildren()));
        FeatureUiV1.Trees.restoreLayout(this.collect, this.getClass() + "#" + "collect");

        // 選択された時のリスナーを設定
        this.collect.getSelectionModel()
                .selectedItemProperty()
                .addListener(this::detail);
        this.aggregate.getSelectionModel()
                .selectedItemProperty()
                .addListener(this::chart);
    }

    private boolean sortCollect(List<TreeTableColumn<MissionRecordCollect, ?>> sortOrder,
            List<TreeItem<MissionRecordCollect>> items) {
        items.sort((o1, o2) -> {
            MissionRecordCollect c1 = (MissionRecordCollect) o1.getValue();
            MissionRecordCollect c2 = (MissionRecordCollect) o2.getValue();
            for (TreeTableColumn<MissionRecordCollect, ?> column : sortOrder) {
                int diff = 0;
                if (column == this.unit) {
                    if (c1.getName() != null && c2.getName() != null) {
                        diff = c1.getName().compareTo(c2.getName());
                    } else {
                        diff = c1.getSortOrder() - c2.getSortOrder();
                    }
                } else if (column == this.successGood) {
                    diff = c1.successGoodProperty().get() - c2.successGoodProperty().get();
                } else if (column == this.success) {
                    diff = c1.successProperty().get() - c2.successProperty().get();
                } else if (column == this.fail) {
                    diff = c1.failProperty().get() - c2.failProperty().get();
                }
                if (diff != 0) {
                    return (column.getSortType() == SortType.DESCENDING ? -1 : 1) * diff;
                }
            }
            return c1.getSortOrder() - c2.getSortOrder();
        });
        items.forEach(item -> sortCollect(sortOrder, item.getChildren()));
        return true;
    }

    @FXML
    void copyDetail(ActionEvent event) {
        FeatureUiV1.Tables.selectionCopy(this.detail);
    }

    @FXML
    void selectAllDetail(ActionEvent event) {
        FeatureUiV1.Tables.selectAll(this.detail);
    }

    @FXML
    void columnVisibleDetail(ActionEvent event) {
        try {
            FeatureUiV1.Tables.showVisibleSetting(this.detail, this.getClass() + "#" + "detail",
                    this.getWindow());
        } catch (Exception e) {
            LoggerHolder.get().error("FXMLの初期化に失敗しました", e);
        }
    }

    @FXML
    void copyAggregate(ActionEvent event) {
        FeatureUiV1.Tables.selectionCopy(this.aggregate);
    }

    @FXML
    void selectAllAggregate(ActionEvent event) {
        FeatureUiV1.Tables.selectAll(this.aggregate);
    }

    @FXML
    void columnVisibleAggregate(ActionEvent event) {
        try {
            FeatureUiV1.Tables.showVisibleSetting(this.aggregate, this.getClass() + "#" + "aggregate",
                    this.getWindow());
        } catch (Exception e) {
            LoggerHolder.get().error("FXMLの初期化に失敗しました", e);
        }
    }

    /**
     * 右ペインに詳細表示するリスナー
     *
     * @param observable 値が変更されたObservableValue
     * @param oldValue 古い値
     * @param value 新しい値
     */
    private void detail(ObservableValue<? extends TreeItem<MissionRecordCollect>> observable,
            TreeItem<MissionRecordCollect> oldValue, TreeItem<MissionRecordCollect> value) {
        this.details.clear();
        this.aggregates.clear();

        this.chart.getData().clear();

        if (value != null) {
            this.chart.setTitle("入手資材");

            MissionRecordCollect collect = value.getValue();

            List<MissionRow> subLog = this.logMap.get(collect.getCollectUnit())
                    .stream()
                    .filter(e -> collect.getName() == null || e.getName().equals(collect.getName()))
                    .collect(Collectors.toList());

            this.details.addAll(subLog.stream()
                    .map(MissionRecordDetail::toMissionRecordDetail)
                    .collect(Collectors.toList()));

            List<Pair<String, Integer>> aggregateBase = this.aggregateBase(subLog);

            BiConsumer<Map<String, Integer>, Pair<String, Integer>> accumulator = (map, pair) -> {
                map.merge(pair.getKey(), pair.getValue(), Integer::sum);
            };
            BiConsumer<Map<String, Integer>, Map<String, Integer>> combiner = (map1, map2) -> {
                for (Entry<String, Integer> entry : map2.entrySet()) {
                    map1.merge(entry.getKey(), entry.getValue(), Integer::sum);
                }
            };
            Map<String, Integer> resources = aggregateBase.stream()
                    .collect(LinkedHashMap<String, Integer>::new, accumulator, combiner);

            for (Entry<String, Integer> entry : resources.entrySet()) {
                MissionAggregate agg = new MissionAggregate();
                agg.setResource(entry.getKey());
                agg.setCount(entry.getValue());
                double average = ((double) entry.getValue()) / subLog.size();
                average = BigDecimal.valueOf(entry.getValue())
                        .divide(BigDecimal.valueOf(subLog.size()), 2, RoundingMode.HALF_UP)
                        .setScale(2)
                        .doubleValue();
                agg.setAverage(average);

                // 資材別の合計を表示する
                this.aggregates.add(agg);

                // 右ペインのチャートに資材別の取得割合を表示する
                if (entry.getValue() > 0) {
                    this.chart.getData().add(new PieChart.Data(entry.getKey(), entry.getValue()));
                }
            }
        }
    }

    /**
     * 右ペインのチャートに遠征・資材別の取得割合を表示する
     *
     * @param observable 値が変更されたObservableValue
     * @param oldValue 古い値
     * @param value 新しい値
     */
    private void chart(ObservableValue<? extends MissionAggregate> observable,
            MissionAggregate oldValue, MissionAggregate value) {
        this.chart.getData().clear();

        if (value != null) {
            TreeItem<MissionRecordCollect> ti = this.collect.getSelectionModel()
                    .getSelectedItem();

            if (ti != null) {
                this.chart.setTitle(value.resourceProperty().get() + " の入手元");

                MissionRecordCollect collect = ti.getValue();
                Map<String, List<MissionRow>> subLog = this.logMap.get(collect.getCollectUnit())
                        .stream()
                        .filter(e -> collect.getName() == null || e.getName().equals(collect.getName()))
                        .collect(Collectors.groupingBy(MissionRow::getName));

                for (Entry<String, List<MissionRow>> entry : subLog.entrySet()) {
                    int sum = this.aggregateBase(entry.getValue()).stream()
                            .filter(p -> p.getKey().equals(value.resourceProperty().get()))
                            .mapToInt(Pair<String, Integer>::getValue)
                            .sum();
                    if (sum > 0) {
                        this.chart.getData().add(new PieChart.Data(entry.getKey(), sum));
                    }
                }
            }
        }
    }

    /**
     * ログを読み込む
     */
    private void readLog() {
        // 今日
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("GMT+04:00"))
                .truncatedTo(ChronoUnit.DAYS);
        // ログ読み込み制限
        ZonedDateTime limit = now.minusMonths(2);

        List<MissionRow> all = RecordDataV1.missionsSince(limit);
        for (Period unit : Period.values()) {
            this.logMap.put(unit, all.stream()
                    .filter(log -> unit.accept(log.getDate(), now))
                    .collect(Collectors.toList()));
        }
    }

    /**
     * 左ペインの集計
     */
    private void setCollect() {
        // 統計
        // ルート要素(非表示)
        TreeItem<MissionRecordCollect> root = new TreeItem<MissionRecordCollect>(new MissionRecordCollect());
        this.collect.setRoot(root);

        int index = 0;
        Map<String, Integer> orders = MissionCollection.get().getMissionMap().values().stream().collect(
                Collectors.toMap(Mission::getName, m -> m.getMapareaId() * 10000 + m.getId(), (k1, k2) -> k1));
        for (Period unit : Period.values()) {
            List<MissionRow> list = this.logMap.get(unit);
            // 単位のルート
            MissionRecordCollect rootValue = collect(list, null);
            rootValue.setUnit(unit.getName());
            rootValue.setCollectUnit(unit);
            rootValue.setSortOrder(index++);

            TreeItem<MissionRecordCollect> unitRoot = new TreeItem<MissionRecordCollect>(rootValue);
            unitRoot.setExpanded(true);

            // 遠征の名前
            List<String> names = list.stream()
                    .map(MissionRow::getName)
                    .distinct()
                    .sorted(Comparator.naturalOrder())
                    .collect(Collectors.toList());
            for (String name : names) {
                // 遠征毎の集計
                MissionRecordCollect subValue = collect(list, name);
                subValue.setCollectUnit(unit);
                subValue.setName(name);
                subValue.setSortOrder(orders.getOrDefault(name, Integer.MAX_VALUE));
                TreeItem<MissionRecordCollect> subRow = new TreeItem<MissionRecordCollect>(subValue);
                unitRoot.getChildren().add(subRow);
            }
            root.getChildren().add(unitRoot);
        }
    }

    /**
     * 集計行の作成
     *
     * @param list 集計対象
     * @param name 遠征名
     * @return 集計行(左ペインの行)
     */
    private static MissionRecordCollect collect(List<MissionRow> list, String name) {
        MissionRecordCollect row = new MissionRecordCollect();

        List<MissionRow> subList;
        if (name != null) {
            subList = list.stream()
                    .filter(e -> e.getName().equals(name))
                    .collect(Collectors.toList());
            row.setUnit(name);
        } else {
            subList = list;
            row.setUnit("-");
        }
        row.setSuccessGood((int) subList.stream().map(MissionRow::getResult)
                .filter("大成功"::equals).count());
        row.setSuccess((int) subList.stream().map(MissionRow::getResult)
                .filter("成功"::equals).count());
        row.setFail((int) subList.stream().map(MissionRow::getResult)
                .filter("失敗"::equals).count());
        return row;
    }

    private List<Pair<String, Integer>> aggregateBase(List<MissionRow> log) {
        return log.stream()
                .flatMap(e -> {
                    List<Pair<String, Integer>> pairs = new ArrayList<>();
                    pairs.add(Tuple.of("燃料", e.getFuel()));
                    pairs.add(Tuple.of("弾薬", e.getAmmo()));
                    pairs.add(Tuple.of("鋼材", e.getMetal()));
                    pairs.add(Tuple.of("ボーキ", e.getBauxite()));
                    if (!e.getItem1name().isEmpty()) {
                        pairs.add(Tuple.of(e.getItem1name(), e.getItem1count()));
                    }
                    if (!e.getItem2name().isEmpty()) {
                        pairs.add(Tuple.of(e.getItem2name(), e.getItem2count()));
                    }
                    return pairs.stream();
                })
                .collect(Collectors.toList());
    }
}
