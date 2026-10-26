package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;

class CapabilityReleaseSelectionTest {
    static final ObjectMapper JSON = new ObjectMapper();
    private Map<String,String> asset(String name) {
        return Map.of("name", name, "digest", "sha256:" + "a".repeat(64), "browser_download_url",
                "https://github.com/ItukiMac/logbook-kai-maintained/releases/download/data-v2026.10.26/" + name);
    }
    @Test void modernHostSelectsV2Asset() throws Exception {
        var index = JSON.valueToTree(List.of(Map.of("tag_name", "data-v2026.10.26", "assets", List.of(asset("logbook-data-pack-v2.zip")))));
        assertTrue(DataPackReleaseClient.selectRelease(index).orElseThrow().download().toString().endsWith("-v2.zip"));
    }
    @Test void modernHostPrefersV2WhenAnOldCompatiblePackAlsoExists() throws Exception {
        var index = JSON.valueToTree(List.of(Map.of("tag_name", "data-v2026.10.26", "assets",
                List.of(asset("logbook-data-pack.zip"), asset("logbook-data-pack-v2.zip")))));
        assertTrue(DataPackReleaseClient.selectRelease(index).orElseThrow().download().toString().endsWith("-v2.zip"));
    }
    @Test void assetNameAndUrlMustMatchExactly() throws Exception {
        var mismatch = new java.util.HashMap<>(asset("logbook-data-pack.zip"));
        mismatch.put("name", "logbook-data-pack-v2.zip");
        var index = JSON.valueToTree(List.of(Map.of("tag_name", "data-v2026.10.26", "assets", List.of(mismatch))));
        assertThrows(java.io.IOException.class, () -> DataPackReleaseClient.selectRelease(index));
    }
    @Test void unknownFutureAssetIsNotDownloaded() throws Exception {
        var index = JSON.valueToTree(List.of(Map.of("tag_name", "data-v2026.10.26", "assets", List.of(asset("logbook-data-pack-v3.zip")))));
        assertTrue(DataPackReleaseClient.selectRelease(index).isEmpty());
    }
}
