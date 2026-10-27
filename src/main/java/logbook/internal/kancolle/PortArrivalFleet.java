package logbook.internal.kancolle;

import java.util.*;
import logbook.bean.AppQuestCondition.FleetCondition;
import logbook.bean.ShipMst;
import logbook.bean.ShipMstCollection;

/** Additional evidence only: legacy arrival records retain their original departure master IDs. */
public record PortArrivalFleet(String format, PortArrival arrival, List<Member> members) {
    public static final String FORMAT = "logbook-port-arrival-fleet-v1";
    public record Member(int slot, int instanceId, int masterId, boolean retreated) {
        public Member {
            if (slot < 1 || slot > 7 || instanceId <= 0 || masterId <= 0)
                throw new IllegalArgumentException("Invalid observed arrival member");
        }
    }
    public PortArrivalFleet {
        if (!FORMAT.equals(format) || arrival == null || members == null || members.isEmpty()
                || members.size() != arrival.shipMasterIds().size() || members.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("Invalid arrival fleet evidence");
        Set<Integer> instances = new HashSet<>(); int retired = 0;
        for (int index = 0; index < members.size(); index++) {
            Member member = members.get(index);
            if (member.slot() != index + 1 || member.masterId() != arrival.shipMasterIds().get(index)
                    || !instances.add(member.instanceId())) throw new IllegalArgumentException("Inconsistent arrival slots");
            if (member.retreated()) retired++;
        }
        if (retired == members.size() || arrival.noRetreat() != (retired == 0))
            throw new IllegalArgumentException("Inconsistent arrival retreat evidence");
        members = List.copyOf(members);
    }
    public boolean matches(FleetCondition condition) {
        if (condition == null) return false;
        List<ShipMst> ships = new ArrayList<>(); Set<Integer> absent = new HashSet<>();
        for (Member member : members) {
            if (member.retreated()) { ships.add(null); absent.add(member.slot()); }
            else {
                ShipMst ship = ShipMstCollection.get().getShipMap().get(member.masterId());
                if (ship == null) return false;
                ships.add(ship);
            }
        }
        return condition.testObservedSlots(ships, absent);
    }
}
