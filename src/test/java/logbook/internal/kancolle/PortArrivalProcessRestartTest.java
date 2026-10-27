package logbook.internal.kancolle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import jakarta.json.*;
import logbook.bean.*;
import logbook.net.RequestMetaData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Distinct JVMs share only the synthetic on-disk journal; no process-local tracker survives. */
class PortArrivalProcessRestartTest {
    @TempDir Path temp;
    private static final long T = 1_700_000_000_000L;
    @Test void departureResumeAndReplayInThreeDifferentJvmsCountOneArrival() throws Exception {
        for (String phase : List.of("depart", "resume", "replay")) {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String agent = Path.of(org.mockito.Mockito.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            var output = temp.resolve(phase + ".log");
            var process = new ProcessBuilder(java, "-javaagent:" + agent, "-cp",
                    System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    Probe.class.getName(), phase, temp.resolve("arrivals").toString())
                    .directory(temp.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            boolean ended = process.waitFor(45, TimeUnit.SECONDS);
            if (!ended) process.destroyForcibly();
            assertTrue(ended, phase + " child JVM timed out");
            assertEquals(0, process.exitValue(), Files.readString(output));
            assertTrue(Files.readString(output).contains("ARRIVAL_PROCESS_" + phase + "_OK"));
        }
        var records = new PortArrivalStore(temp.resolve("arrivals")).readRange(T, T + 5000);
        assertEquals(1, records.size()); assertEquals(T, records.getFirst().startedAt());
        assertEquals(T + 2000, records.getFirst().arrivedAt());
    }
    public static class Probe {
        public static void main(String[] args) throws Exception {
            String phase = args[0]; long now = T + (phase.equals("depart") ? 0 : phase.equals("resume") ? 2000 : 3000);
            var store = new PortArrivalStore(Path.of(args[1]));
            var runtime = new PortArrivalRuntime(() -> store, Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC));
            var state = new AppCondition(); state.setMapStart(true); state.setDeckId(1);
            var decks = new DeckPortCollection(); var deck = new DeckPort(); deck.setId(1);
            deck.setShip(List.of(11, 12, -1, -1, -1, -1)); decks.getDeckPortMap().put(1, deck);
            var ships = new ShipCollection();
            for (int id : List.of(11, 12)) { var ship = new Ship(); ship.setId(id); ship.setShipId(id + 90); ships.getShipMap().put(id, ship); }
            try (var a = mockStatic(AppCondition.class); var d = mockStatic(DeckPortCollection.class); var s = mockStatic(ShipCollection.class)) {
                a.when(AppCondition::get).thenReturn(state); d.when(DeckPortCollection::get).thenReturn(decks); s.when(ShipCollection::get).thenReturn(ships);
                if (phase.equals("depart") || phase.equals("replay"))
                    runtime.accept("/kcsapi/api_req_map/start", node(2, 4, null), req(T), true);
                if (phase.equals("resume")) {
                    var data = Json.createObjectBuilder().add("api_result", 1).add("api_data", Json.createObjectBuilder()
                            .add("api_ship_data", Json.createArrayBuilder()
                                    .add(Json.createObjectBuilder().add("api_id", 11).add("api_ship_id", 101))
                                    .add(Json.createObjectBuilder().add("api_id", 12).add("api_ship_id", 102)))
                            .add("api_deck_data", Json.createArrayBuilder().add(Json.createObjectBuilder().add("api_id", 1)
                                    .add("api_ship", Json.createArrayBuilder().add(11).add(12).add(-1).add(-1).add(-1).add(-1))))).build();
                    runtime.accept("/kcsapi/api_get_member/ship_deck", data, req(T + 1000), true);
                }
                if (!phase.equals("depart")) runtime.accept("/kcsapi/api_req_map/next", node(0, 9, 2), req(T + 2000), true);
            }
            assertEquals(phase.equals("depart") ? 0 : 1, store.readRange(T, T + 5000).size(), runtime.statusReason());
            System.out.println("ARRIVAL_PROCESS_" + phase + "_OK");
        }
        private static RequestMetaData req(long captured) {
            var req = mock(RequestMetaData.class);
            when(req.getParameter("api_deck_id")).thenReturn("1");
            when(req.getHeader("x-koukainissikai-requestat")).thenReturn(Optional.of(Long.toString(captured)));
            return req;
        }
        private static JsonObject node(int cell, int event, Integer from) {
            var data = Json.createObjectBuilder().add("api_maparea_id", 1).add("api_mapinfo_no", 6).add("api_no", cell).add("api_event_id", event);
            if (from != null) data.add("api_from_no", from);
            return Json.createObjectBuilder().add("api_result", 1).add("api_data", data).build();
        }
    }
}
