package logbook.internal.kancolle;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import logbook.internal.util.DateUtil;

/** Minimal arrival evidence, not a battle, raw API body or reconstructed historical record. */
public record PortArrival(String format, String sortieId, long startedAt, long arrivedAt,
        String map, int deckId, List<Integer> shipMasterIds, boolean noRetreat) {
    public static final String FORMAT = "logbook-port-arrival-v1";

    public PortArrival {
        if (!FORMAT.equals(format) || sortieId == null
                || !UUID.fromString(sortieId).toString().equals(sortieId)
                || startedAt <= 0 || arrivedAt < startedAt
                || arrivedAt - startedAt > 86_400_000L || !"1-6".equals(map)
                || deckId < 1 || deckId > 4 || shipMasterIds == null
                || shipMasterIds.isEmpty() || shipMasterIds.size() > 7
                || shipMasterIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("Invalid observed port arrival");
        }
        shipMasterIds = List.copyOf(shipMasterIds);
    }

    public String startDate() {
        return date(startedAt);
    }

    public String date() {
        return date(arrivedAt);
    }

    private static String date(long epoch) {
        return DateUtil.DATE_FORMAT.format(Instant.ofEpochMilli(epoch).atZone(ZoneId.of("Asia/Tokyo")));
    }
}
