package logbook.internal.kancolle;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.stream.Collectors;

import logbook.bean.AppQuest;
import logbook.bean.AppQuestCondition;
import logbook.bean.AppQuestCondition.FilterCondition;
import logbook.bean.AppQuestCondition.Type;
import logbook.bean.AppQuestDuration;
import logbook.bean.BattleLog;
import logbook.bean.Enemy;
import logbook.bean.MapinfoMst;
import logbook.bean.MapinfoMstCollection;
import logbook.bean.Ship;
import logbook.bean.ShipMst;
import logbook.bean.Stype;
import logbook.internal.kancolle.BattleLogs.SimpleBattleLog;
import logbook.internal.kancolle.MissionLogs.SimpleMissionLog;
import logbook.internal.logger.LoggerHolder;
import logbook.internal.util.DateUtil;
import lombok.Data;

/**
 * 任務進捗集計
 *
 */
@Data
public class QuestCollect {

    private Count total = new Count();

    /** A broken/missing-safety-boundary journal must not be presented as a successful count. */
    private boolean arrivalEvidenceUnavailable;

    private Map<String, Count> area = new HashMap<>();

    private Map<String, Integer> stype = new HashMap<>();

    private Map<String, Long> missions = new HashMap<>();

    @Data
    public static class Count {
        private int start;
        private int arrivals;
        private Rank all = new Rank();
        private Map<String, Rank> cell = new HashMap<>();
        private Rank boss = new Rank();

        public void add(Count count) {
            this.start += count.start;
            this.arrivals += count.arrivals;
            this.all.add(count.all);
            for (Entry<String, Rank> entry : count.cell.entrySet()) {
                this.cell.merge(entry.getKey(), entry.getValue(), Rank::add);
            }
            this.boss.add(count.boss);
        }
    }

    @Data
    public static class Rank {
        private int s;
        private int a;
        private int b;
        private int c;
        private int d;
        private int e;

        public Rank add(Rank rank) {
            this.s += rank.s;
            this.a += rank.a;
            this.b += rank.b;
            this.c += rank.c;
            this.d += rank.d;
            this.e += rank.e;
            return this;
        }
    }

    public static QuestCollect collect(AppQuest quest, AppQuestCondition condition) {
        try (var scope = condition.getDefinitionSnapshot() == null ? logbook.core.LogBookCoreServices.pinDefinitions() : logbook.core.LogBookCoreServices.pinDefinitions(condition.getDefinitionSnapshot())) {
            return collectPinned(quest, condition);
        }
    }
    private static QuestCollect collectPinned(AppQuest quest, AppQuestCondition condition) {
        if (condition.getType() == Type.遠征) {
            return missionCollect(quest, condition);
        } else {
            return battleCollect(quest, condition);
        }
    }

    private static QuestCollect missionCollect(AppQuest quest, AppQuestCondition condition) {
        QuestCollect collect = new QuestCollect();
        List<SimpleMissionLog> logs = AppQuestDuration.get().getMissionCondition(quest)
                .orElse(Collections.emptyList());
        collect.setMissions(logs.stream()
                .filter(log -> {
                    if (condition.getStartDate() == null)
                        return true;
                    try {
                        boolean isCount = LocalDateTime.parse(log.getDateString(), DateUtil.DATE_FORMAT)
                                .compareTo(LocalDateTime.parse(condition.getStartDate(), DateUtil.DATE_FORMAT)) >= 0; 
                        if (LoggerHolder.get().isDebugEnabled()) {
                            LoggerHolder.get().debug(log.getDateString() + " : " + condition.getStartDate() + " : " + isCount);
                        }
                        return isCount;
                    } catch (Exception e) {
                        LoggerHolder.get().warn("遠征ログの日付パースエラー: " + log.getDateString(), e);
                        return false;
                    }
                })
                .filter(log -> "成功".equals(log.getResult()) || "大成功".equals(log.getResult()))
                .collect(Collectors.groupingBy(SimpleMissionLog::getName, Collectors.counting())));
        return collect;
    }

    private static QuestCollect battleCollect(AppQuest quest, AppQuestCondition condition) {
        QuestCollect collect = new QuestCollect();
        if (condition.getConditions().stream().anyMatch(AppQuestCondition.Condition::isArrival)) {
            collectArrivals(quest, condition, collect);
        }
        List<SimpleBattleLog> logs = AppQuestDuration.get().getCondition(quest)
                .orElse(Collections.emptyList());
        Collection<MapinfoMst> mapinfo = MapinfoMstCollection.get().getMapinfo().values();
        for (SimpleBattleLog log : logs) {
            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
            // 海域
            String map = mapinfo.stream().filter(i -> log.getArea().equals(i.getName()))
                    .map(i -> i.getMapareaId() + "-" + i.getNo())
                    .findFirst()
                    .orElse(null);

            // 戦闘ログ
            BattleLog battleLog = null;
            PhaseState p = null;

            // フィルター条件
            FilterCondition filter = condition.getFilter();
            if (filter != null) {
                if (filter.getArea() != null && !filter.getArea().contains(map)) {
                    continue;
                }
                if (QuestBattleRequirements.needsLog(filter) || filter.getFleet() != null) {
                    battleLog = BattleLogs.read(log.getDateString());
                    if (battleLog == null || !QuestBattleRequirements.matches(filter, battleLog)) {
                        continue;
                    }
                }
                if (filter.getFleet() != null) {
                    p = new PhaseState(battleLog);

                    List<ShipMst> ships = recordedFleet(p.getAfterFriend());
                    if (ships == null || !filter.getFleet().test(ships)) {
                        continue;
                    }
                }
            }

            if (condition.isCollectStypeInternal()) {
                if (battleLog == null)
                    battleLog = BattleLogs.read(log.getDateString());
                if (p == null)
                    p = new PhaseState(battleLog);

                p.apply(battleLog.getBattle());
                p.apply(battleLog.getMidnight());

                List<Enemy> e = new ArrayList<>();
                e.addAll(p.getAfterEnemy());
                e.addAll(p.getAfterEnemyCombined());
                e.stream()
                        .filter(Objects::nonNull)
                        // 撃沈したか？
                        .filter(o -> o.getNowhp() <= 0)
                        // 艦種に変換
                        .map(Ships::stype)
                        .map(o -> o.orElse(null))
                        .filter(Objects::nonNull)
                        // 艦種の名前を取り出す
                        .map(Stype::getName)
                        // 艦種ごとにカウント
                        .forEach(key -> {
                            collect.getStype().merge(key, 1, (a, b) -> a + b);
                        });
            }
            if (map != null) {
                // 海域ごとのカウント
                count(map, log, collect.getArea().computeIfAbsent(map, k -> new Count()));
            }
            // 合計
            count(map, log, collect.getTotal());

            if (condition.test(collect)) {
                break;
            }
        }
        return collect;
    }

    private static void collectArrivals(AppQuest quest, AppQuestCondition condition, QuestCollect collect) {
        try {
            for (PortArrival arrival : AppQuestDuration.get().getArrivalCondition(quest)) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.IOException("Arrival query interrupted");
                if (condition.getStartDate() != null && arrival.startDate().compareTo(condition.getStartDate()) < 0) continue;
                FilterCondition filter = condition.getFilter();
                if (filter != null) {
                    if (filter.getArea() != null && !filter.getArea().contains(arrival.map())) continue;
                    if (filter.getDeckIds() != null && !filter.getDeckIds().contains(arrival.deckId())) continue;
                    if (Boolean.TRUE.equals(filter.getNoRetreat()) && !arrival.noRetreat()) continue;
                    if (filter.getFleet() != null) {
                        if (arrival.noRetreat()) {
                            List<ShipMst> ships = arrival.shipMasterIds().stream()
                                    .map(id -> logbook.bean.ShipMstCollection.get().getShipMap().get(id)).toList();
                            if (!filter.getFleet().test(ships)) continue;
                        } else {
                            // Legacy records alone do not prove which departure slots survived.
                            // Only a separately validated, matching observation may supply them.
                            var fleet = PortArrivalRuntime.readFleet(arrival);
                            if (fleet.isEmpty() || !fleet.get().matches(filter.getFleet())) continue;
                        }
                    }
                }
                collect.getArea().computeIfAbsent(arrival.map(), key -> new Count()).arrivals++;
                collect.getTotal().arrivals++;
            }
        } catch (Exception error) {
            collect.arrivalEvidenceUnavailable = true;
            LoggerHolder.get().warn("港到達記録を集計できません。戦闘回数で代用しません。", error);
        }
    }

    /** Resolve the recorded positions without promoting an escort into a missing slot. */
    private static List<ShipMst> recordedFleet(List<Ship> recorded) {
        if (recorded == null) return null;
        int size = recorded.size();
        // Saved fleets may contain trailing unused slots, but an interior gap is not padding.
        while (size > 0 && recorded.get(size - 1) == null) size--;
        if (size == 0) return null;
        List<ShipMst> resolved = new ArrayList<>(size);
        for (int slot = 0; slot < size; slot++) {
            Ship member = recorded.get(slot);
            if (member == null) return null;
            ShipMst master = Ships.shipMst(member).orElse(null);
            if (master == null) return null;
            resolved.add(master);
        }
        return resolved;
    }

    private static void count(String map, SimpleBattleLog log, Count count) {
        boolean isStart = log.getBoss().contains("出撃");
        boolean isBoss = log.getBoss().contains("ボス");
        String cell = log.getCell();
        String rank = log.getRank();

        if (isStart) {
            count.start++;
        }
        if (isBoss) {
            rank(map, rank, count.getBoss());
        }
        rank(map, rank, count.getCell().computeIfAbsent(map + "-" + cell, i -> new Rank()));
        rank(map, rank, count.getAll());
    }

    private static void rank(String map, String rank, Rank rankCount) {
        if (rank.equals("S")) {
            rankCount.s++;
        }
        if (rank.equals("A")) {
            rankCount.a++;
        }
        if (rank.equals("B")) {
            rankCount.b++;
        }
        if (rank.equals("C")) {
            rankCount.c++;
        }
        if (rank.equals("D")) {
            rankCount.d++;
        }
        if (rank.equals("E")) {
            rankCount.e++;
        }
    }
}
