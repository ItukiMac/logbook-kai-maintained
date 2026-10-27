package logbook.internal.kancolle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import logbook.bean.*;
import logbook.net.RequestMetaData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Fleet sidecars are synthetic optional evidence; the credited v1 journal stays unchanged. */
class PortArrivalFleetStoreTest {
    @TempDir Path temp;
    private static final long T = Instant.parse("2026-10-27T01:00:00Z").toEpochMilli();
    private static final ObjectMapper JSON = new ObjectMapper();
    private Path root() { return temp.resolve("arrivals"); }
    private PortArrivalStore store() { return new PortArrivalStore(root()); }
    private static PortArrival arrival(boolean noRetreat) {
        return new PortArrival(PortArrival.FORMAT, UUID.randomUUID().toString(), T, T + 100,
                "1-6", 1, List.of(101, 102, 103), noRetreat);
    }
    private static PortArrivalFleet fleet(PortArrival arrival) {
        return new PortArrivalFleet(PortArrivalFleet.FORMAT, arrival, List.of(
                new PortArrivalFleet.Member(1, 11, 101, false),
                new PortArrivalFleet.Member(2, 12, 102, !arrival.noRetreat()),
                new PortArrivalFleet.Member(3, 13, 103, false)));
    }
    private Path sidecar(PortArrival value) { return root().resolve("fleet-evidence-v1/2026-10").resolve(value.sortieId() + ".json"); }
    private static Map<Path, byte[]> bytes(Path root) throws IOException {
        Map<Path, byte[]> result = new TreeMap<>();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) result.put(root.relativize(file), Files.readAllBytes(file));
        }
        return result;
    }
    private void assertBytesRetained(Map<Path, byte[]> before) throws IOException {
        for (var entry : before.entrySet()) assertArrayEquals(entry.getValue(), Files.readAllBytes(root().resolve(entry.getKey())), entry.getKey().toString());
    }
    private void mutate(PortArrival value, Consumer<ObjectNode> mutation) throws Exception {
        ObjectNode node = (ObjectNode) JSON.readTree(sidecar(value).toFile()); mutation.accept(node);
        Files.writeString(sidecar(value), node.toString());
    }

    @Test void additionalEvidencePreservesAllLegacyJsonAndPendingBytes() throws Exception {
        var old = arrival(true); var retired = arrival(false); var store = store();
        store.append(old); store.append(retired); var before = bytes(root());
        assertEquals(4, before.size());
        store.appendFleet(fleet(retired));
        store.journal().append(null, "START", T, JSON.createObjectNode().put("synthetic", true), "fixture");
        assertBytesRetained(before);
        assertEquals(Set.of(old, retired), new HashSet<>(store.readRange(T - 1, T + 101)));
        assertEquals(fleet(retired), store.readFleet(retired).orElseThrow());
        assertTrue(store.readFleet(old).isEmpty());
        assertEquals(PortArrival.FORMAT, JSON.readTree(root().resolve("2026-10").resolve(old.sortieId() + ".json").toFile()).get("format").asText());
    }
    @Test void arrivalReaderIgnoresTrackingAndFleetEvidenceNamespaces() throws Exception {
        var old = arrival(true); store().append(old);
        Files.createDirectories(root().resolve("tracking-v1/2026-10-27"));
        Files.writeString(root().resolve("tracking-v1/2026-10-27/00000001.json"), "{partial");
        Files.createDirectories(root().resolve("fleet-evidence-v1/2026-10"));
        Files.writeString(root().resolve("fleet-evidence-v1/2026-10/broken.json"), "{partial");
        assertEquals(List.of(old), store().readRange(T - 1, T + 101));
    }
    @Test void writingOnlyFleetEvidenceCannotCreditAnArrival() throws Exception {
        var value = arrival(false); store().appendFleet(fleet(value));
        assertEquals(fleet(value), store().readFleet(value).orElseThrow());
        assertTrue(store().readRange(T - 1, T + 101).isEmpty());
    }
    @Test void absentSidecarReadDoesNotCreateDirectories() throws Exception {
        assertTrue(store().readFleet(arrival(true)).isEmpty()); assertFalse(Files.exists(root()));
    }
    @Test void incompletePendingSidecarIsRetainedButNeverPromoted() throws Exception {
        var value = arrival(false); Path month = sidecar(value).getParent(); Files.createDirectories(month);
        Path pending = month.resolve("." + value.sortieId() + "-synthetic.pending"); Files.writeString(pending, "{partial");
        assertTrue(store().readFleet(value).isEmpty()); assertEquals("{partial", Files.readString(pending));
        assertTrue(store().readRange(T - 1, T + 101).isEmpty());
    }
    @Test void duplicatePublicationIsIdempotentAndDoesNotReplaceAnyBytes() throws Exception {
        var value = arrival(false); var evidence = fleet(value); store().appendFleet(evidence);
        var before = bytes(root()); store().appendFleet(evidence);
        assertEquals(before.keySet(), bytes(root()).keySet()); assertBytesRetained(before);
    }
    @Test void conflictingInstanceEvidenceCannotReplaceAnExistingSidecar() throws Exception {
        var value = arrival(false); var evidence = fleet(value); store().appendFleet(evidence); var before = bytes(root());
        var changed = new ArrayList<>(evidence.members()); changed.set(2, new PortArrivalFleet.Member(3, 99, 103, false));
        var conflict = new PortArrivalFleet(PortArrivalFleet.FORMAT, value, changed);
        assertThrows(IOException.class, () -> store().appendFleet(conflict)); assertBytesRetained(before);
        assertEquals(evidence, store().readFleet(value).orElseThrow());
    }
    @Test void sidecarMetadataMustMatchTheExactCreditedArrival() throws Exception {
        var value = arrival(false); store().appendFleet(fleet(value));
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                n -> ((ObjectNode) n.get("arrival")).put("sortieId", UUID.randomUUID().toString()),
                n -> ((ObjectNode) n.get("arrival")).put("startedAt", T + 1),
                n -> ((ObjectNode) n.get("arrival")).put("arrivedAt", T + 101),
                n -> ((ObjectNode) n.get("arrival")).put("deckId", 2))) {
            Files.writeString(sidecar(value), JSON.writeValueAsString(fleet(value))); mutate(value, mutation);
            assertThrows(IOException.class, () -> store().readFleet(value));
        }
    }
    @Test void changedMasterAndRetreatEvidenceCannotMatchAnOldArrival() throws Exception {
        var value = arrival(false); store().appendFleet(fleet(value));
        mutate(value, n -> { ((com.fasterxml.jackson.databind.node.ArrayNode) n.get("arrival").get("shipMasterIds")).set(0, JSON.getNodeFactory().numberNode(201));
            ((ObjectNode) n.get("members").get(0)).put("masterId", 201); });
        assertThrows(IOException.class, () -> store().readFleet(value));
        Files.writeString(sidecar(value), JSON.writeValueAsString(fleet(value)));
        mutate(value, n -> { ((ObjectNode) n.get("arrival")).put("noRetreat", true);
            ((ObjectNode) n.get("members").get(1)).put("retreated", false); });
        assertThrows(IOException.class, () -> store().readFleet(value));
    }
    @Test void slotsMustStayInOriginalOrderWithoutCompacting() {
        var value = arrival(false); var members = new ArrayList<>(fleet(value).members());
        members.set(1, new PortArrivalFleet.Member(3, 12, 102, true));
        assertThrows(IllegalArgumentException.class, () -> new PortArrivalFleet(PortArrivalFleet.FORMAT, value, members));
        assertThrows(IllegalArgumentException.class, () -> new PortArrivalFleet(PortArrivalFleet.FORMAT, value,
                List.of(fleet(value).members().get(0), fleet(value).members().get(2))));
    }
    @Test void individualShipIdentitiesMustBeUnique() {
        var value = arrival(false); var members = new ArrayList<>(fleet(value).members());
        members.set(1, new PortArrivalFleet.Member(2, 11, 102, true));
        assertThrows(IllegalArgumentException.class, () -> new PortArrivalFleet(PortArrivalFleet.FORMAT, value, members));
    }
    @Test void memberMasterIdsMustMatchTheLegacyDeparturePositions() {
        var value = arrival(false); var members = new ArrayList<>(fleet(value).members());
        members.set(1, new PortArrivalFleet.Member(2, 12, 201, true));
        assertThrows(IllegalArgumentException.class, () -> new PortArrivalFleet(PortArrivalFleet.FORMAT, value, members));
    }
    @Test void noRetreatCannotBeTrueWithAKnownRetreatedMember() {
        var value = arrival(true); var members = new ArrayList<>(fleet(value).members());
        members.set(1, new PortArrivalFleet.Member(2, 12, 102, true));
        assertThrows(IllegalArgumentException.class, () -> new PortArrivalFleet(PortArrivalFleet.FORMAT, value, members));
    }
    @Test void noRetreatCannotBeFalseWhenNoMemberRetreated() {
        var value = arrival(false); var members = new ArrayList<>(fleet(value).members());
        members.set(1, new PortArrivalFleet.Member(2, 12, 102, false));
        assertThrows(IllegalArgumentException.class, () -> new PortArrivalFleet(PortArrivalFleet.FORMAT, value, members));
    }
    @Test void allMembersRetreatedCannotBeAnArrivalFleet() {
        var value = arrival(false); var members = fleet(value).members().stream()
                .map(m -> new PortArrivalFleet.Member(m.slot(), m.instanceId(), m.masterId(), true)).toList();
        assertThrows(IllegalArgumentException.class, () -> new PortArrivalFleet(PortArrivalFleet.FORMAT, value, members));
    }
    @Test void emptyNullAndInvalidMembersAreRejected() {
        var value = arrival(true);
        assertThrows(IllegalArgumentException.class, () -> new PortArrivalFleet(PortArrivalFleet.FORMAT, value, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new PortArrivalFleet(PortArrivalFleet.FORMAT, value, null));
        assertThrows(IllegalArgumentException.class, () -> new PortArrivalFleet(PortArrivalFleet.FORMAT, value, Arrays.asList(null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> new PortArrivalFleet.Member(0, 11, 101, false));
        assertThrows(IllegalArgumentException.class, () -> new PortArrivalFleet.Member(8, 11, 101, false));
        assertThrows(IllegalArgumentException.class, () -> new PortArrivalFleet.Member(1, 0, 101, false));
        assertThrows(IllegalArgumentException.class, () -> new PortArrivalFleet.Member(1, 11, 0, false));
    }
    @Test void fleetMembersAreAnImmutableCopy() {
        var value = arrival(false); var members = new ArrayList<>(fleet(value).members());
        var evidence = new PortArrivalFleet(PortArrivalFleet.FORMAT, value, members); members.clear();
        assertEquals(3, evidence.members().size()); assertThrows(UnsupportedOperationException.class, () -> evidence.members().clear());
    }
    @Test void corruptedAndPartialPublishedSidecarsAreRejectedAndRetained() throws Exception {
        var value = arrival(false); store().appendFleet(fleet(value)); Files.writeString(sidecar(value), "{partial");
        assertThrows(IOException.class, () -> store().readFleet(value));
        assertThrows(IOException.class, () -> store().appendFleet(fleet(value)));
        assertEquals("{partial", Files.readString(sidecar(value)));
    }
    @Test void duplicateKeysAreRejected() throws Exception {
        var value = arrival(false); store().appendFleet(fleet(value));
        Files.writeString(sidecar(value), Files.readString(sidecar(value)).replaceFirst("\\{", "{\"format\":\"duplicate\","));
        assertThrows(IOException.class, () -> store().readFleet(value));
    }
    @Test void trailingJsonAndLiteralNullAreRejected() throws Exception {
        var value = arrival(false); store().appendFleet(fleet(value));
        Files.writeString(sidecar(value), Files.readString(sidecar(value)) + " {}");
        assertThrows(IOException.class, () -> store().readFleet(value));
        Files.writeString(sidecar(value), "null"); assertThrows(IOException.class, () -> store().readFleet(value));
    }
    @Test void unknownFormatAndUnknownFieldsAreRejected() throws Exception {
        var value = arrival(false); store().appendFleet(fleet(value)); mutate(value, n -> n.put("format", "future-v2"));
        assertThrows(IOException.class, () -> store().readFleet(value));
        Files.writeString(sidecar(value), JSON.writeValueAsString(fleet(value))); mutate(value, n -> n.put("unknown", true));
        assertThrows(IOException.class, () -> store().readFleet(value));
    }
    @Test void oversizedSidecarIsRejectedBeforeParsing() throws Exception {
        var value = arrival(false); Files.createDirectories(sidecar(value).getParent()); Files.write(sidecar(value), new byte[16_385]);
        assertThrows(IOException.class, () -> store().readFleet(value));
    }
    @Test void symlinkEvidenceRootIsNotReadOrWritten() throws Exception {
        var value = arrival(false); Path actual = temp.resolve("external"); Files.createDirectory(actual); Files.createDirectory(root());
        Files.createSymbolicLink(root().resolve("fleet-evidence-v1"), actual);
        assertThrows(IOException.class, () -> store().appendFleet(fleet(value)));
        assertThrows(IOException.class, () -> store().readFleet(value));
        try (var files = Files.list(actual)) { assertEquals(0, files.count()); }
    }
    @Test void symlinkMonthAndPublishedFileAreRejected() throws Exception {
        var value = arrival(false); Path actual = temp.resolve("external"); Files.createDirectory(actual);
        Files.createDirectories(root().resolve("fleet-evidence-v1")); Files.createSymbolicLink(sidecar(value).getParent(), actual);
        assertThrows(IOException.class, () -> store().readFleet(value));
        assertThrows(IOException.class, () -> store().appendFleet(fleet(value)));
        Path another = temp.resolve("other"); var other = new PortArrivalStore(another); Path month = another.resolve("fleet-evidence-v1/2026-10");
        Files.createDirectories(month); Path source = actual.resolve("sidecar.json"); Files.writeString(source, JSON.writeValueAsString(fleet(value)));
        Files.createSymbolicLink(month.resolve(value.sortieId() + ".json"), source);
        assertThrows(IOException.class, () -> other.readFleet(value)); assertThrows(IOException.class, () -> other.appendFleet(fleet(value)));
    }
    @Test void publishedDirectoryIsNotMistakenForARecord() throws Exception {
        var value = arrival(false); Files.createDirectories(sidecar(value));
        assertThrows(IOException.class, () -> store().readFleet(value)); assertThrows(IOException.class, () -> store().appendFleet(fleet(value)));
    }
    @Test void missingRetreatBooleanIsUnknownInsteadOfFalse() throws Exception {
        var value = arrival(true); store().appendFleet(fleet(value));
        mutate(value, n -> ((ObjectNode) n.get("members").get(1)).remove("retreated"));
        assertThrows(IOException.class, () -> store().readFleet(value));
    }
    @Test void nullRetreatBooleanIsUnknownInsteadOfFalse() throws Exception {
        var value = arrival(true); store().appendFleet(fleet(value));
        mutate(value, n -> ((ObjectNode) n.get("members").get(1)).putNull("retreated"));
        assertThrows(IOException.class, () -> store().readFleet(value));
    }
    @Test void numericAndBooleanStringsAreNotValidObservedTypes() throws Exception {
        var value = arrival(true); store().appendFleet(fleet(value));
        mutate(value, n -> ((ObjectNode) n.get("members").get(0)).put("instanceId", "11"));
        assertThrows(IOException.class, () -> store().readFleet(value));
        Files.writeString(sidecar(value), JSON.writeValueAsString(fleet(value)));
        mutate(value, n -> ((ObjectNode) n.get("members").get(0)).put("retreated", "false"));
        assertThrows(IOException.class, () -> store().readFleet(value));
    }
    @Test void fractionalSlotCannotBeRoundedToAValidPosition() throws Exception {
        var value = arrival(true); store().appendFleet(fleet(value));
        mutate(value, n -> ((ObjectNode) n.get("members").get(0)).put("slot", 1.5));
        assertThrows(IOException.class, () -> store().readFleet(value));
    }
    @Test void fleetPublicationFailureConsumesGoalWithoutCreditingOrReplayingIt() throws Exception {
        var state = new AppCondition(); state.setMapStart(true); state.setDeckId(1);
        var decks = new DeckPortCollection(); var deck = new DeckPort(); deck.setShip(List.of(11, -1, -1, -1, -1, -1)); decks.getDeckPortMap().put(1, deck);
        var ships = new ShipCollection(); var ship = new Ship(); ship.setId(11); ship.setShipId(101); ships.getShipMap().put(11, ship);
        var request = mock(RequestMetaData.class); when(request.getParameter("api_deck_id")).thenReturn("1");
        var store = store(); var clock = Clock.fixed(Instant.ofEpochMilli(T), ZoneOffset.UTC);
        try (var states = mockStatic(AppCondition.class); var ports = mockStatic(DeckPortCollection.class); var members = mockStatic(ShipCollection.class)) {
            states.when(AppCondition::get).thenReturn(state); ports.when(DeckPortCollection::get).thenReturn(decks); members.when(ShipCollection::get).thenReturn(ships);
            var runtime = new PortArrivalRuntime(() -> store, clock);
            runtime.accept("/kcsapi/api_req_map/start", event(2, 4), request, true);
            Path external = temp.resolve("external"); Files.createDirectory(external); Files.createSymbolicLink(root().resolve("fleet-evidence-v1"), external);
            runtime.accept("/kcsapi/api_req_map/next", event(0, 9), request, true);
            assertTrue(store.readRange(T - 1, T + 1).isEmpty());
            var restarted = new PortArrivalRuntime(() -> store, clock);
            restarted.accept("/kcsapi/api_req_map/next", event(0, 9), request, true);
            restarted.accept("/kcsapi/api_req_map/start", event(2, 4), request, true);
            restarted.accept("/kcsapi/api_req_map/next", event(0, 9), request, true);
            assertTrue(store.readRange(T - 1, T + 1).isEmpty());
            assertEquals(1, store.journal().readLatest().stream().filter(e -> e.kind().equals("GOAL")).count());
            try (var files = Files.list(external)) { assertEquals(0, files.count()); }
        }
    }
    private static JsonObject event(int cell, int event) {
        return Json.createObjectBuilder().add("api_result", 1).add("api_data", Json.createObjectBuilder()
                .add("api_maparea_id", 1).add("api_mapinfo_no", 6).add("api_no", cell).add("api_event_id", event)).build();
    }
}
