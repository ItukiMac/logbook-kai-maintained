package logbook.internal.kancolle;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import tools.jackson.databind.ObjectMapper;
import jakarta.json.Json;
import logbook.bean.MapStartNext;

/** Synthetic B+D archive fixture. No Launcher/config/network in either mode. */
public final class ArrivalArchiveProbe {
    private static final long T = 1746835260000L;
    private static final ObjectMapper JSON = logbook.internal.json.JsonMappers.builder().build();

    private static PortArrivalRuntime.Observation observation(PortArrivalTracker.Snapshot route, String... keys) {
        return new PortArrivalRuntime.Observation(route, keys[0], List.of(keys), route.observedAt(), true, List.of());
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2)
            throw new IllegalArgumentException("seed/read and isolated runtime path required");
        Path root = Path.of(args[1]).resolve("sortie-arrivals-v1");
        PortArrivalStore store = new PortArrivalStore(root);
        if (args[0].equals("seed")) {
            if (Files.exists(root))
                throw new IllegalArgumentException("Seed only a NEW synthetic journal");
            var tracker = new PortArrivalTracker();
            var node = new MapStartNext();
            node.setMapareaId(1);
            node.setMapinfoNo(6);
            node.setNo(2);
            node.setEventId(4);
            tracker.start(node, 1, List.of(11, 12, 13), List.of(101, 102, 103), T);
            String a = "a".repeat(64), b = "b".repeat(64), c = "c".repeat(64), d = "d".repeat(64);
            var journal = store.journal();
            var last = journal.append(null, "START", T, JSON.valueToTree(observation(tracker.snapshot(), a)),
                    "TRACKING");
            var original = tracker.snapshot();
            var offered = new PortArrivalTracker.Snapshot(original.sortieId(), T, T + 10, 1,
                    original.instances(), original.masters(), List.of(), 2, 4, null);
            tracker.restore(offered);
            var offer = new PortArrivalRuntime.Observation(offered, a, List.of(a, b), T + 10, true, List.of(2));
            last = journal.append(last, "OFFER", T + 10, JSON.valueToTree(offer), "RETREAT_OFFER_OBSERVED");
            if (!tracker.retreat(List.of(2), T + 20))
                throw new AssertionError("Synthetic retreat failed");
            last = journal.append(last, "RETREAT", T + 20, JSON.valueToTree(observation(tracker.snapshot(), a, b, c)),
                    "RETREAT_CONFIRMED");
            node.setNo(0);
            node.setEventId(9);
            node.setFromNo(2);
            var arrival = tracker.next(node, 1, List.of(11, 12, 13), List.of(101, 102, 103), false, T + 30)
                    .orElseThrow();
            journal.append(last, "GOAL", T + 30, JSON.valueToTree(observation(tracker.completed(), a, b, c, d)),
                    "GOAL_CONSUMED");
            store.appendFleet(new PortArrivalFleet(PortArrivalFleet.FORMAT, arrival, List.of(
                    new PortArrivalFleet.Member(1, 11, 101, false), new PortArrivalFleet.Member(2, 12, 102, true),
                    new PortArrivalFleet.Member(3, 13, 103, false))));
            store.append(arrival);
        } else if (!args[0].equals("read"))
            throw new IllegalArgumentException("Unknown probe mode");
        var arrivals = store.readRange(T, T + 100);
        if (arrivals.size() != 1)
            throw new AssertionError("Arrival count changed");
        var evidence = store.readFleet(arrivals.getFirst()).orElseThrow();
        if (!evidence.members().get(1).retreated() || evidence.members().get(2).slot() != 3
                || evidence.arrival().noRetreat())
            throw new AssertionError("Retreat slots lost");
        if (store.journal().readLatest().size() != 4)
            throw new AssertionError("Tracking checkpoints lost");
        // Read through the real semantic runtime validator; GOAL is terminal so
        // unrelated traffic neither appends evidence nor needs game-state mocks.
        var runtime = new PortArrivalRuntime(() -> store, Clock.fixed(Instant.ofEpochMilli(T + 40), ZoneOffset.UTC));
        runtime.accept("/kcsapi/api_get_member/material", Json.createObjectBuilder().add("api_result", 1).build(), null,
                true);
        if (!"GOAL_CONSUMED".equals(runtime.statusReason()))
            throw new AssertionError(runtime.statusReason());
        System.out.println("ARRIVAL_ARCHIVE_OK records=1 checkpoints=4 retired_slots=2 original_positions=true");
    }
}
