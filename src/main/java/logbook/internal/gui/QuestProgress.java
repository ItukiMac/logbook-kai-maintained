package logbook.internal.gui;

import java.util.Optional;

import org.controlsfx.glyphfont.FontAwesome;
import org.controlsfx.glyphfont.GlyphFont;
import org.controlsfx.glyphfont.GlyphFontRegistry;

import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.WindowEvent;
import logbook.bean.AppQuest;
import logbook.bean.AppQuestCondition;
import logbook.bean.AppQuestCondition.Condition;
import logbook.bean.AppQuestCondition.FilterCondition;
import logbook.bean.AppQuestCondition.FleetCondition;
import logbook.internal.ThreadManager;
import logbook.internal.kancolle.QuestCollect;
import logbook.internal.logger.LoggerHolder;

/**
 * 任務進捗確認
 *
 */
public class QuestProgress extends logbook.plugin.api.FeatureUiV1.Window {

    @FXML
    private Label name;

    @FXML
    private Label info;

    @FXML
    private TreeView<String> condition;

    private AppQuest quest;
    private Task<?> pending;
    private long requestVersion;
    private javafx.animation.PauseTransition seasonalRefresh;
    private QuestObservationUiBinding observations;
    // A definition reload cannot replace a failed or missing quest observation.
    private String observationProblem;

    @Override public void setWindow(javafx.stage.Stage window) {
        super.setWindow(window);
        if (observations != null) observations.close();
        observations = window == null ? null : new QuestObservationUiBinding(window, gameStateView(), this::questListUpdated);
    }

    logbook.plugin.api.GameStateView gameStateView() {
        return logbook.core.LogBookCoreServices.gameStateView();
    }

    AppQuest latestObservedQuest(int questId) {
        return logbook.core.LogBookCoreServices.readGameState(() -> {
            AppQuest latest = logbook.bean.AppQuestCollection.get().getQuest().get(questId);
            if (latest == null || latest.getQuest() == null) return null;
            var original = latest.getQuest();
            var copy = new logbook.bean.QuestList.Quest();
            copy.setNo(original.getNo()); copy.setTitle(original.getTitle()); copy.setDetail(original.getDetail());
            copy.setCategory(original.getCategory()); copy.setType(original.getType()); copy.setState(original.getState());
            copy.setObservedAt(original.getObservedAt()); // Copy evidence; never replace it with snapshot/receipt time.
            var result = new AppQuest(); result.setNo(latest.getNo()); result.setExpire(latest.getExpire());
            result.setActive(latest.isActive()); result.setQuest(copy);
            return result;
        });
    }

    private void questListUpdated(logbook.plugin.api.GameStateSnapshot snapshot) {
        if (quest == null) return;
        requestVersion++;
        if (pending != null) pending.cancel();
        if (seasonalRefresh != null) seasonalRefresh.stop();
        AppQuest latest = snapshot.updateSucceeded() ? latestObservedQuest(quest.getNo()) : null;
        if (latest != null) {
            setQuest(latest);
        } else {
            observationProblem = snapshot.updateSucceeded()
                ? "未確定：更新された任務一覧でこの任務を確認できません"
                : "未確定：任務一覧の更新を確認できません";
            showUnconfirmedMessage(observationProblem);
        }
    }

    @Override protected void onDefinitionsChanged() { if (quest != null) load(); }
    @Override protected void onWindowHidden(WindowEvent event) {
        requestVersion++;
        if (pending != null) pending.cancel();
        if (seasonalRefresh != null) seasonalRefresh.stop();
    }

    void setQuest(AppQuest quest) {
        this.quest = quest;
        this.observationProblem = null;
        this.name.setText(displayTitle(quest) + " 期限:" + quest.getExpire());
        this.info.setText(displayDetail(quest));
        this.condition.setRoot(new TreeItem<String>("読み込み中"));
        this.load();
    }

    private void load() {
        if (pending != null) pending.cancel();
        if (seasonalRefresh != null) seasonalRefresh.stop();
        final long requested = ++requestVersion;
        if (observationProblem != null) {
            showUnconfirmedMessage(observationProblem);
            return;
        }
        this.condition.setShowRoot(true);
        this.condition.setRoot(new TreeItem<>("読み込み中"));
        final AppQuest requestedQuest = this.quest;
        final var definition = logbook.core.GameDefinitionRuntime.active() ? logbook.core.GameDefinitionRuntime.snapshot() : null;
        try {
            Task<Optional<AppQuestCondition>> task = new Task<Optional<AppQuestCondition>>() {
                @Override
                protected Optional<AppQuestCondition> call() throws Exception {
                    try (var scope = definition == null ? logbook.core.LogBookCoreServices.pinDefinitions() : logbook.core.LogBookCoreServices.pinDefinitions(definition)) {
                    AppQuest quest = requestedQuest;
                    AppQuestCondition condition = loadCondition(quest);
                    if (condition != null) {
                        if (!condition.isRuleUsableFor(quest)) return Optional.of(condition);
                        if (this.isCancelled()) {
                            return Optional.empty();
                        }
                        QuestCollect collect = collectCondition(quest, condition);
                        if (collect == null) {
                            return Optional.empty();
                        }
                        condition.test(collect);
                        return Optional.of(condition);
                    }
                    return Optional.empty();
                    }
                }

                @Override
                protected void succeeded() {
                    if (requested != requestVersion || isCancelled()) return;
                    if (definition != null && definition != logbook.core.LogBookCoreServices.gameDataView().snapshot()) { load(); return; }
                    AppQuest quest = requestedQuest;
                    Optional<AppQuestCondition> condition = this.getValue();
                    if (!condition.isPresent()) {
                        this.failed();
                        return;
                    }

                    if (!condition.get().isRuleUsableFor(quest)) {
                        showUnconfirmed(condition.get());
                        return;
                    }
                    TreeItem<String> root = new TreeItem<>(displayTitle(quest));
                    root.setExpanded(true);
                    if (definition != null) QuestProgress.this.info.setText(displayDetail(quest) + " / 定義: " + definition.generation());

                    FilterCondition filterCondition = condition.get().getFilter();
                    if (filterCondition != null) {
                        TreeItem<String> filterTree = new TreeItem<>("フィルター条件");
                        filterTree.setExpanded(true);
                        setFilterIcon(filterTree);

                        FleetCondition fleetCondition = filterCondition.getFleet();
                        if (fleetCondition != null) {
                            TreeItem<String> fleetTree = new TreeItem<>("艦隊条件");
                            fleetTree.setExpanded(true);
                            setFilterIcon(fleetTree);
                            fleetTree.getChildren().add(QuestProgress.this.buildFilterLeaf(fleetCondition));

                            filterTree.getChildren().add(fleetTree);
                        }
                        root.getChildren().add(filterTree);
                    }

                    TreeItem<String> conditionTree = new TreeItem<>("達成条件");
                    conditionTree.setExpanded(true);
                    for (Condition part : condition.get().getConditions()) {
                        TreeItem<String> leaf = new TreeItem<>(part.toString());
                        setConditionIcon(leaf, part.getResult());
                        conditionTree.getChildren().add(leaf);
                    }
                    setConditionIcon(conditionTree, condition.get().getResult());
                    root.getChildren().add(conditionTree);

                    QuestProgress.this.condition.setShowRoot(false);
                    QuestProgress.this.condition.setRoot(root);
                    scheduleSeasonalRefresh(condition.get());
                }

                @Override
                protected void failed() {
                    if (requested != requestVersion || isCancelled()) return;
                    Throwable t = this.getException();
                    TreeItem<String> root = new TreeItem<>("何らかの理由で集計出来ませんでした。" + (t != null ? String.valueOf(t) : ""));
                    setConditionIcon(root, false);
                    QuestProgress.this.condition.setShowRoot(true);
                    QuestProgress.this.condition.setRoot(root);
                }
            };
            this.pending = task;
            ThreadManager.getExecutorService().execute(task);

        } catch (Exception e) {
            LoggerHolder.get().error("任務確認画面で例外", e);
        }
    }

    // Isolated GUI tests replace only the data boundary; production uses the real loader/collector.
    AppQuestCondition loadCondition(AppQuest quest) {
        return AppQuestCondition.loadFromResource(quest.getQuest());
    }

    QuestCollect collectCondition(AppQuest quest, AppQuestCondition condition) {
        return QuestCollect.collect(quest, condition);
    }

    private static String displayTitle(AppQuest quest) {
        return java.util.Objects.toString(quest.getQuest().getTitle(), "任務 " + quest.getNo());
    }

    private static String displayDetail(AppQuest quest) {
        return java.util.Objects.toString(quest.getQuest().getDetail(), "").replace("<br>", "");
    }

    private void showUnconfirmed(AppQuestCondition rule) {
        String message = rule.isRuleUsable() ? "未確定：集計する任務の観測が一致しません" : rule.getRuleStatusMessage();
        showUnconfirmedMessage(message);
    }

    private void showUnconfirmedMessage(String message) {
        TreeItem<String> root = new TreeItem<>(message);
        setConditionIcon(root, null);
        this.condition.setShowRoot(true);
        this.condition.setRoot(root);
        this.info.setText(displayDetail(this.quest) + " / " + message);
    }

    private void scheduleSeasonalRefresh(AppQuestCondition rule) {
        var selection = rule.getSeasonalSelection();
        if (selection == null || selection.refreshAt() == null) return;
        this.info.setText(displayDetail(this.quest) + " / " + rule.getRuleStatusMessage());
        long millis = Math.max(1, java.time.Duration.between(java.time.Instant.now(), selection.refreshAt()).toMillis());
        seasonalRefresh = new javafx.animation.PauseTransition(javafx.util.Duration.millis(millis));
        seasonalRefresh.setOnFinished(event -> load());
        seasonalRefresh.play();
    }

    private TreeItem<String> buildFilterLeaf(FleetCondition condition) {
        TreeItem<String> item = new TreeItem<>(condition.toString());
        setFilterListIcon(item);
        if (condition.getConditions() != null) {
            if (condition.getConditions().size() == 1 && !condition.getOperator().startsWith("N")) {
                item.setValue(condition.getConditions().get(0).toString());
            } else {
                for (FleetCondition subcondition : condition.getConditions()) {
                    item.getChildren().add(this.buildFilterLeaf(subcondition));
                }
            }
        }
        return item;
    }

    private static void setFilterIcon(TreeItem<String> item) {
        GlyphFont fontAwesome = GlyphFontRegistry.font("FontAwesome");
        StackPane pane = new StackPane();
        pane.setPrefWidth(18);
        pane.getChildren().add(fontAwesome.create(FontAwesome.Glyph.FILTER).color(Color.ROYALBLUE));
        item.setGraphic(pane);
    }

    private static void setFilterListIcon(TreeItem<String> item) {
        GlyphFont fontAwesome = GlyphFontRegistry.font("FontAwesome");
        StackPane pane = new StackPane();
        pane.setPrefWidth(18);
        pane.getChildren().add(fontAwesome.create(FontAwesome.Glyph.ANGLE_RIGHT).color(Color.ROYALBLUE));
        item.setGraphic(pane);
    }

    private static void setConditionIcon(TreeItem<String> item, Boolean result) {
        GlyphFont fontAwesome = GlyphFontRegistry.font("FontAwesome");

        StackPane pane = new StackPane();
        pane.setPrefWidth(18);

        if (result != null) {
            if (result) {
                pane.getChildren().add(fontAwesome.create(FontAwesome.Glyph.CHECK).color(Color.GREEN));
            } else {
                pane.getChildren().add(fontAwesome.create(FontAwesome.Glyph.EXCLAMATION).color(Color.RED));
            }
        } else {
            pane.getChildren().add(fontAwesome.create(FontAwesome.Glyph.QUESTION).color(Color.GRAY));
        }
        item.setGraphic(pane);
    }
}
