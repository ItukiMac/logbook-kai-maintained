package logbook.bean;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


import tools.jackson.databind.ObjectMapper;

import logbook.core.LogBookCoreServices;
import logbook.internal.kancolle.Operator;
import logbook.internal.kancolle.QuestCollect;
import logbook.internal.kancolle.QuestCollect.Count;
import logbook.internal.kancolle.QuestCollect.Rank;
import logbook.internal.logger.LoggerHolder;
import lombok.Data;

/**
 * 任務条件
 *
 */
@Data
public class AppQuestCondition implements Predicate<QuestCollect> {

    @com.fasterxml.jackson.annotation.JsonIgnore
    @lombok.ToString.Exclude
    @lombok.EqualsAndHashCode.Exclude
    private transient logbook.plugin.api.GameDefinitionSnapshot definitionSnapshot;

    /** 任務のタイプ(enum: 出撃, 遠征) */
    private Type type;

    /** 任務期間(文字列:単発,デイリー,ウィークリー,マンスリー,クオータリー) */
    private String resetType;

    /** イヤリーのリセット月（イヤリー8月なら8） */
    private Integer yearlyResetMonth;

    /** 任務開始日時 */
    private String startDate;

    /** カスタム任務名 */
    private String customName;

    /** フィルター条件 */
    private FilterCondition filter;

    /** 条件 */
    private List<Condition> conditions = new ArrayList<>();

    /** 結果 */
    private Boolean result;

    /**
     * 任務(クエスト)の達成条件設定ファイルを読み込みます
     * 
     * @param questNo 任務番号
     * @return 任務条件設定
     */
    public static AppQuestCondition loadFromResource(int questNo) {
        return loadObserved(questNo, null, logbook.core.SeasonalQuestRuleService.bundled());
    }

    /** Live observation is required for seasonal rules; ID-only callers remain unconfirmed. */
    public static AppQuestCondition loadFromResource(QuestList.Quest quest) {
        return loadFromResource(quest, logbook.core.SeasonalQuestRuleService.bundled());
    }

    public static AppQuestCondition loadFromResource(QuestList.Quest quest,
            logbook.core.SeasonalQuestRuleService rules) {
        if (quest == null || quest.getNo() == null) return null;
        return loadObserved(quest.getNo(), logbook.core.SeasonalQuestRuleService.Observation.from(quest), rules);
    }

    private static AppQuestCondition loadObserved(int questNo,
            logbook.core.SeasonalQuestRuleService.Observation observation,
            logbook.core.SeasonalQuestRuleService rules) {
        try (var lease = LogBookCoreServices.pinDefinitions()) {
            AppQuestCondition result = loadPinned(questNo, observation, rules);
            if (result != null && logbook.core.GameDefinitionRuntime.active()) result.definitionSnapshot = lease.snapshot();
            return result;
        }
    }

    private static AppQuestCondition loadPinned(int questNo,
            logbook.core.SeasonalQuestRuleService.Observation observation,
            logbook.core.SeasonalQuestRuleService rules) {
        try (InputStream stream = LogBookCoreServices.getQuestResourceAsStream(questNo, true)) {
            if (stream != null) return readRule(stream, questNo, "quest", observation, rules);
        } catch (Exception e) {
            LoggerHolder.get().info("任務設定ファイルが読み込めませんでした。", e);
            return null;
        }
        // Preserve the existing regular -> custom -> event precedence and the original files.
        try {
            java.nio.file.Path customPath = java.nio.file.Paths.get("./customquest/" + questNo + ".json");
            if (java.nio.file.Files.exists(customPath)) {
                try (InputStream stream = java.nio.file.Files.newInputStream(customPath)) {
                    return readRule(stream, questNo, "customquest", observation, rules);
                }
            }
        } catch (Exception e) {
            LoggerHolder.get().info("カスタム任務設定ファイルが読み込めませんでした: " + questNo, e);
        }
        try (InputStream stream = LogBookCoreServices.getQuestResourceAsStream(questNo, false)) {
            if (stream != null) return readRule(stream, questNo, "eventquest", observation, rules);
        } catch (Exception e) {
            LoggerHolder.get().info("任務設定ファイルが読み込めませんでした。", e);
        }
        return null;
    }

    private static AppQuestCondition readRule(InputStream stream, int questNo, String origin,
            logbook.core.SeasonalQuestRuleService.Observation observation,
            logbook.core.SeasonalQuestRuleService rules) throws java.io.IOException {
                try {
        byte[] original = stream.readNBytes(1_048_577);
        if (original.length > 1_048_576) throw new java.io.IOException("Quest definition size limit");
        var resolution = rules.resolve(questNo, origin, original, observation);
        byte[] chosen = resolution.definition();
        ObjectMapper mapper = logbook.internal.json.JsonMappers.builder().enable(tools.jackson.core.json.JsonReadFeature.ALLOW_JAVA_COMMENTS).build();
        AppQuestCondition result = mapper.readValue(chosen == null ? original : chosen, AppQuestCondition.class);
        if (resolution.selection().status() != logbook.core.SeasonalQuestRuleService.Status.NOT_SEASONAL)
            result.seasonalSelection = resolution.selection();
        return result;
                } catch (tools.jackson.core.JacksonException jsonCompatibilityFailure) {
                    throw new java.io.IOException("Invalid or unreadable JSON", jsonCompatibilityFailure);
                }
            }

    @com.fasterxml.jackson.annotation.JsonIgnore
    @lombok.ToString.Exclude
    @lombok.EqualsAndHashCode.Exclude
    private transient logbook.core.SeasonalQuestRuleService.Selection seasonalSelection;

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isRuleUsable() {
        if (seasonalSelection == null) return true;
        return seasonalSelection.usable() && !seasonalGenerationChanged();
    }

    public boolean isRuleUsableFor(AppQuest quest) {
        return seasonalSelection == null || isRuleUsable() && quest != null
            && java.util.Objects.equals(quest.getNo(), seasonalSelection.questId())
            && seasonalSelection.matches(logbook.core.SeasonalQuestRuleService.Observation.from(quest.getQuest()));
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getRuleStatusMessage() {
        if (seasonalSelection == null) return "";
        return seasonalGenerationChanged()
            ? logbook.core.SeasonalQuestRuleService.Status.DEFINITION_CHANGED.message() : seasonalSelection.message();
    }

    private boolean seasonalGenerationChanged() {
        return definitionSnapshot != null && logbook.core.GameDefinitionRuntime.active()
            && definitionSnapshot != LogBookCoreServices.gameDataView().snapshot();
    }

    public boolean permitsSeasonalLog(String jstDate) {
        return seasonalSelection == null || isRuleUsable() && seasonalSelection.permitsLog(jstDate);
    }

    public boolean permitsSeasonalLog(java.time.Instant instant) {
        return seasonalSelection == null || isRuleUsable() && seasonalSelection.permitsLog(instant);
    }

    // internal use only
    public boolean isCollectStypeInternal() {
        for (Condition condition : this.conditions) {
            if ((condition.result == null || condition.result == false)
                    && (condition.stype != null && !condition.stype.isEmpty())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean test(QuestCollect t) {
        if (!isRuleUsable() || t != null && t.isSeasonalEvidenceUnavailable()) {
            return unconfirmed(!isRuleUsable() ? getRuleStatusMessage() : "未確定：集計する任務の観測が一致しません");
        }
        try (var lease = definitionSnapshot == null ? LogBookCoreServices.pinDefinitions() : LogBookCoreServices.pinDefinitions(definitionSnapshot)) {
            boolean passed = testPinned(t);
            return isRuleUsable() ? passed : unconfirmed(getRuleStatusMessage());
        }
    }

    private boolean unconfirmed(String message) {
        this.result = null;
        for (Condition condition : this.conditions) {
            condition.result = null;
            condition.current = message;
        }
        return false;
    }

    private boolean testPinned(QuestCollect t) {
        Predicate<QuestCollect> predicate = null;
        for (Condition condition : this.conditions) {
            condition.test(t);

            if (predicate == null) {
                predicate = condition;
            } else {
                predicate = predicate.and(condition);
            }
        }
        return this.result = predicate.test(t);
    }

    /**
     * フィルター条件
     *
     */
    @Data
    public static class FilterCondition {

        /** 海域条件 */
        private Set<String> area;

        /** 艦隊条件 */
        private FleetCondition fleet;

        /** Allowed fleet numbers; null preserves the legacy unrestricted behaviour. */
        private Set<Integer> deckIds;

        /** Require a recorded empty retreat snapshot; unknown historical data is not proof. */
        private Boolean noRetreat;
    }

    /**
     * フィルター条件:艦隊条件
     *
     */
    @Data
    public static class FleetCondition implements Predicate<List<ShipMst>> {

        /** 備考 */
        private String description;

        /** 艦種 */
        private LinkedHashSet<String> stype;

        /** 艦名 */
        private LinkedHashSet<String> name;

        /** 艦種もしくは艦名のリストに含まれないものに一致 */
        private boolean difference;

        /** カウント */
        private Integer count;

        /** 序列(1:旗艦) */
        private Integer order;

        /** 条件 */
        private List<FleetCondition> conditions;

        /** 演算子(AND,OR,NAND,NOR,EQ(等しい),GE(以上),GT(より大きい),LE(以下),LT(より小さい),NE(等しくない)) */
        private String operator;

        @Override
        public boolean test(List<ShipMst> ships) {
            // Unknown input entries are not evidence of a valid fleet. Keep them
            // distinct from the null markers consumed internally by AND clauses.
            if (ships == null || ships.stream().anyMatch(java.util.Objects::isNull)) return false;
            return evaluate(new ArrayList<>(ships));
        }

        /**
         * Evaluate a complete observed fleet without compacting proven retreat vacancies.
         * Null is accepted only at the explicitly absent, one-based positions; every other
         * missing member remains unknown evidence and fails closed. Ordinary test() keeps
         * rejecting all null input entries.
         */
        public boolean testObservedSlots(List<ShipMst> ships, java.util.Set<Integer> absentSlots) {
            if (ships == null || ships.isEmpty() || ships.size() > 7 || absentSlots == null
                    || absentSlots.stream().anyMatch(slot -> slot == null || slot < 1 || slot > ships.size())) {
                return false;
            }
            boolean hasSurvivor = false;
            for (int slot = 0; slot < ships.size(); slot++) {
                boolean absent = absentSlots.contains(slot + 1);
                if (absent != (ships.get(slot) == null)) return false;
                if (!absent) hasSurvivor = true;
            }
            return hasSurvivor && evaluate(new ArrayList<>(ships));
        }

        private boolean evaluate(List<ShipMst> ships) {
            if (this.count == null && this.operator != null) {
                return this.testOperator(ships);
            } else {
                return this.testShip(ships);
            }
        }

        @Override
        public String toString() {
            if (this.count == null && this.operator != null) {
                return this.toStringOperator();
            } else {
                return this.toStringShip();
            }
        }

        /**
         * 条件(type=艦娘)
         *
         * @param ships 艦隊
         * @return 条件に一致する場合true
         */
        private boolean testShip(List<ShipMst> ships) {
            if (this.count != null) {
                // 序列の指定無効
                int c = 0;
                for (int i = 0; i < ships.size(); i++) {
                    if (matchesAvailableShip(ships.get(i))) {
                        c++;
                    }
                }
                return Operator.valueOf(this.operator).compare(c, this.count);
            } else {
                if (this.order != null) {
                    // 序列の指定有り
                    if (ships.size() >= this.order) {
                        if (matchesAvailableShip(ships.get(this.order - 1))) {
                            ships.set(this.order - 1, null);
                            return true;
                        }
                    }
                } else {
                    // 序列の指定無し
                    for (int i = 0; i < ships.size(); i++) {
                        if (matchesAvailableShip(ships.get(i))) {
                            ships.set(i, null);
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private boolean matchesAvailableShip(ShipMst ship) {
            // A consumed slot is not an excluded ship, nor an available match.
            return ship != null && (this.difference ^ this.testShip0(ship));
        }

        private boolean testShip0(ShipMst ship) {
            if (ship == null) {
                return false;
            }
            if (this.stype != null) {
                String stype = ship.asStype()
                        .map(Stype::getName)
                        .orElse(null);
                return this.stype.contains(stype) || this.stype.size() > 0 && this.stype.contains("*");
            }
            if (this.name != null) {
                for (String name : this.name) {
                    if (ship.getName().startsWith(name)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * 条件(論理演算)
         *
         * @param ships 艦隊
         * @return 条件に一致する場合true
         */
        private boolean testOperator(List<ShipMst> ships) {
            Predicate<List<ShipMst>> predicate = null;
            for (FleetCondition condition : this.conditions) {
                if (predicate == null) {
                    predicate = condition::evaluate;
                } else {
                    predicate = this.operator.endsWith("AND")
                            ? predicate.and(condition::evaluate)
                            : predicate.or(condition::evaluate);
                }
            }
            if ("NAND".equals(this.operator) || "NOR".equals(this.operator)) {
                predicate = predicate.negate();
            }
            return predicate.test(new ArrayList<>(ships));
        }

        private String toStringShip() {
            StringBuilder sb = new StringBuilder();
            if (this.stype != null) {
                sb.append(this.stype.stream().map(s -> s.equals("*") ? "任意の艦種" : s).collect(Collectors.joining("または")));
            }
            if (this.name != null) {
                int subListSize = 5;
                if (this.name.size() > subListSize) {
                    int numSubLists = (this.name.size() + subListSize - 1) / subListSize;
                    List<LinkedHashSet<String>> subList = IntStream.range(0, numSubLists)
                            .mapToObj(i -> this.name.stream()
                                    .skip(i * subListSize)
                                    .limit(subListSize)
                                    .collect(Collectors.toCollection(LinkedHashSet::new)))
                            .collect(Collectors.toList());

                    sb.append(subList.stream()
                            .map(subListItem -> String.join(" または ", subListItem))
                            .collect(Collectors.joining(" または\n")));

                } else {
                    sb.append(this.name.stream().collect(Collectors.joining(" または ")));
                }
            }
            if (this.difference) {
                sb.append("以外");
            }
            if (this.count != null) {
                sb.append("が");
                sb.append(this.count);
                sb.append("隻");
                Operator ope = Operator.valueOf(this.operator);
                if (ope == Operator.EQ) {
                    // ～がX隻に等しい
                    sb.append("に");
                }
                sb.append(Operator.valueOf(this.operator).toString());
            } else {
                sb.append("を");
                if (this.order != null) {
                    if (this.order == 1) {
                        sb.append("旗艦");
                    } else {
                        sb.append("序列" + this.order + "位");
                    }
                } else {
                    sb.append("含む");
                }
            }
            if (this.description != null) {
                sb.append("(" + this.description + ")");
            }
            return sb.toString();
        }

        private String toStringOperator() {
            StringBuilder sb = new StringBuilder();
            switch (this.operator) {
                case "AND":
                    sb.append("次の条件を全て満たす");
                    break;
                case "OR":
                    sb.append("次の条件のいずれか少なくとも1つを満たす");
                    break;
                case "NAND":
                    sb.append("次の条件のいずれか少なくとも1つを満たさない");
                    break;
                case "NOR":
                    sb.append("次の条件を全て満たさない");
                    break;
                default:
                    break;
            }
            if (this.description != null) {
                sb.append("(" + this.description + ")");
            }
            return sb.toString();
        }
    }

    public enum Type {
        出撃, 遠征
    }

    /**
     * 集計条件
     */
    @Data
    public static class Condition implements Predicate<QuestCollect> {

        /** 備考 */
        private String description;

        /** 海域 */
        private LinkedHashSet<String> area;

        /** マップセル(7-2の第1ボスならG、第2ボスならM) */
        private String cell;

        /** 複数のマップセル(7-2の第1ボスならG、第2ボスならM) */
        private LinkedHashSet<String> cells;

        /** 出撃 */
        private boolean start;

        /** Observed goal event, not the preceding battle. Currently only map 1-6 is supported. */
        private boolean arrival;

        /** ボス */
        private boolean boss;

        /** ランク */
        private LinkedHashSet<String> rank;

        /** 艦種 */
        private LinkedHashSet<String> stype;

        /** カウント */
        private int count;

        /** 遠征名 */
        private List<String> missions;

        private Boolean result;

        private String current;

        @Override
        public boolean test(QuestCollect t) {
            if (this.arrival && (t.isArrivalEvidenceUnavailable() || !validArrival())) {
                this.current = "到達記録を確認できません";
                return this.result = false;
            }
            int count;
            if (this.missions != null) {
                count = this.missionCount(t);
            } else {
                count = this.count(t);
            }
            this.current = String.valueOf(count);
            return this.result = (count >= this.count);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();

            if (this.missions != null) {
                if (this.missions.size() == 0) {
                    sb.append("任意の遠征");
                } else {
                    List<String> labels = this.missions.stream()
                            .map(name -> MissionCollection.get().getMissionMap().values().stream()
                                    .filter(mission -> mission.getName().equals(name))
                                    .map(mission -> "「" + mission.getDispNo() + " " + mission.getName() + "」")
                                    .findAny()
                                    .orElse(name))
                            .collect(Collectors.toList());
                    sb.append(String.join("または", labels));
                }
                sb.append("を").append(this.count).append("回成功");
            } else if (this.stype != null && !this.stype.isEmpty()) {
                sb.append(this.stype.stream().collect(Collectors.joining("または")))
                        .append("を")
                        .append(this.count)
                        .append("隻撃沈");
            } else {
                if (this.area != null && !this.area.isEmpty()) {
                    sb.append(this.area.stream().collect(Collectors.joining("または")));
                } else {
                    sb.append("任意の海域");
                }
                if (this.cell != null || this.cells != null) {
                    sb.append("(セル").append(this.cell != null ? this.cell : this.cells.stream().collect(Collectors.joining("または"))).append(")");
                }
                if (this.arrival) {
                    sb.append("の港へ").append(this.count).append("回到達（観測記録のみ）");
                } else if (this.start) {
                    sb.append("に").append(this.count).append("回出撃");
                } else {
                    sb.append("で");
                    if (this.boss) {
                        sb.append("ボス");
                    }
                    if (this.rank == null || this.rank.isEmpty()
                            || this.rank.contains("E")
                            || this.rank.contains("D")) {
                        // D以下
                        sb.append("戦闘");
                    } else if (this.rank.contains("C")) {
                        // C以上
                        sb.append("C敗北以上");
                    } else if (this.rank.contains("B")) {
                        // B以上
                        sb.append("B勝利以上");
                    } else if (this.rank.contains("A")) {
                        // A以上
                        sb.append("A勝利以上");
                    } else {
                        // S
                        sb.append("S勝利");
                    }
                    sb.append(this.count).append("回");
                }
            }
            if (this.description != null) {
                sb.append("(" + this.description + ")");
            }
            if (this.current != null) {
                sb.append("(現在の値:" + this.current + ")");
            }
            return sb.toString();
        }

        /** Refuse mixed battle/arrival operands even for manually supplied custom definitions. */
        private boolean validArrival() {
            return this.area != null && this.area.equals(java.util.Set.of("1-6"))
                    && !this.start && !this.boss && this.cell == null && this.cells == null
                    && this.rank == null && this.stype == null && this.missions == null && this.count > 0;
        }

        /**
         * 遠征カウント
         * 
         * @param t 集計結果
         * @return カウント
         */
        private int missionCount(QuestCollect t) {
            Map<String, Long> map = t.getMissions();
            if (this.missions.size() == 0) {
                return map.values().stream().mapToInt(Long::intValue).sum();
            } else {
                return this.missions.stream().map(mission -> map.getOrDefault(mission, 0L)).mapToInt(Long::intValue).sum();
            }
        }

        /**
         * カウント
         * 
         * @param t 集計結果
         * @return カウント
         */
        private int count(QuestCollect t) {
            int count = 0;
            // 撃沈カウント
            if (this.stype != null) {
                for (String key : this.stype) {
                    Integer v = t.getStype().get(key);
                    if (v != null) {
                        count += v;
                    }
                }
                return count;
            }
            // 戦闘カウント
            Count battleCount = new Count();
            if (this.area != null) {
                for (String key : this.area) {
                    Count areaCount = t.getArea().get(key);
                    if (areaCount == null) {
                        continue;
                    }
                    battleCount.add(areaCount);
                }
            } else {
                battleCount = t.getTotal();
            }
            if (this.arrival) {
                return battleCount.getArrivals();
            }
            if (this.start) {
                // 出撃回数
                return battleCount.getStart();
            } else {
                // 評価
                Rank rank;
                if (this.cell != null) {
                    rank = battleCount.getCell().computeIfAbsent(this.area.stream().findFirst().get() + "-" + this.cell, i -> new Rank());
                } else if (this.cells != null) {
                    rank = new Rank();
                    final Map<String, Rank> cellCount = battleCount.getCell();
                    this.cells.stream().map(c -> cellCount.computeIfAbsent(this.area.stream().findFirst().get() + "-" + c, i -> new Rank())).forEach(rank::add);
                } else {
                    if (this.boss) {
                        rank = battleCount.getBoss();
                    } else {
                        rank = battleCount.getAll();
                    }
                }
                boolean any = (this.rank == null || this.rank.isEmpty());

                if (any || this.rank.contains("S"))
                    count += rank.getS();
                if (any || this.rank.contains("A"))
                    count += rank.getA();
                if (any || this.rank.contains("B"))
                    count += rank.getB();
                if (any || this.rank.contains("C"))
                    count += rank.getC();
                if (any || this.rank.contains("D"))
                    count += rank.getD();
                if (any || this.rank.contains("E"))
                    count += rank.getE();
                return count;
            }
        }
    }
}
