package logbook.plugin.api;

import java.time.Instant;
import java.util.*;

/** Explicit immutable read model. No raw HTTP, authentication values, or mutable host beans. */
public record GameStateSnapshot(long sequence, Instant observedAt, String sourceApi,
        String definitionGeneration, boolean updateSucceeded,
        List<ShipState> ships, List<FleetState> fleets, List<QuestState> quests, Map<Integer, Integer> materials) {
    public GameStateSnapshot {
        if (sequence < 0)
            throw new IllegalArgumentException("negative sequence");
        Objects.requireNonNull(observedAt);
        Objects.requireNonNull(sourceApi);
        Objects.requireNonNull(definitionGeneration);
        ships = List.copyOf(ships);
        fleets = List.copyOf(fleets);
        quests = List.copyOf(quests);
        materials = Map.copyOf(materials);
    }

    public record ShipState(Integer id, Integer masterId, Integer level, Integer hp, Integer maxHp, Integer condition,
            List<Integer> equipment) {
        public ShipState {
            equipment = List.copyOf(equipment);
        }
    }

    public record FleetState(Integer id, List<Integer> shipIds, List<Long> mission) {
        public FleetState {
            shipIds = List.copyOf(shipIds);
            mission = List.copyOf(mission);
        }
    }

    public record QuestState(Integer id, String title, String detail, Integer category, Integer type, Integer state,
            Integer progress,
            List<Integer> materials, Integer bonusFlag, Integer invalidFlag, String resetEstimate) {
        public QuestState {
            materials = List.copyOf(materials);
        }
    }
}
