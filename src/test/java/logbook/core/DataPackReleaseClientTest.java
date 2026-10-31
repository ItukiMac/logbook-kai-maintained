package logbook.core;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DataPackReleaseClientTest {
    private static final ObjectMapper MAPPER = logbook.internal.json.JsonMappers.builder().build();
    private static final String CHECKSUM = "a".repeat(64);

    @AfterEach
    void reset() {
        System.clearProperty(DataPackReleaseClient.AUTO_UPDATE_PROPERTY);
        System.clearProperty(ExternalQuestDefinitions.ROOT_PROPERTY);
    }

    private String entry(String tag, String url, boolean draft, boolean prerelease) {
        return """
                {"tag_name":"%s","draft":%s,"prerelease":%s,
                  "assets":[{"name":"logbook-data-pack.zip",
                  "digest":"sha256:%s","browser_download_url":"%s"}]}
                """.formatted(tag, draft, prerelease, CHECKSUM, url);
    }

    @Test
    void disabledByDefaultNeverPerformsNetworkOrRequiresRoot() throws Exception {
        assertFalse(DataPackReleaseClient.refreshConfiguredData());
    }

    @Test
    void enabledWithoutDataRootFailsBeforeNetwork() {
        System.setProperty(DataPackReleaseClient.AUTO_UPDATE_PROPERTY, "true");
        assertThrows(IOException.class, DataPackReleaseClient::refreshConfiguredData);
    }

    @Test
    void selectsMaintainedStableReleaseNotApplicationOrDraft() throws Exception {
        String valid = "https://github.com/ItukiMac/logbook-kai-maintained/releases/download/data-v2026.10.25/logbook-data-pack.zip";
        String payload = "[" +
                entry("v25.0.9", valid, false, false) + "," +
                entry("data-v2026.10.26", valid, true, false) + "," +
                entry("data-v2026.10.25", valid, false, false) + "]";
        Optional<DataPackReleaseClient.DataRelease> found = DataPackReleaseClient
                .selectRelease(MAPPER.readTree(payload));
        assertTrue(found.isPresent());
        assertEquals("data-v2026.10.25", found.get().tag());
        assertEquals(CHECKSUM, found.get().sha256());
    }

    @Test
    void noMatchingReleaseIsNoOp() throws Exception {
        assertTrue(DataPackReleaseClient.selectRelease(
                MAPPER.readTree("[{\"tag_name\":\"v25.0.9\",\"assets\":[]}]")).isEmpty());
    }

    @Test
    void rejectsExternalAssetUrlEvenWithValidDigest() throws Exception {
        String payload = "[" +
                entry("data-v2026.10.25", "https://example.invalid/evil.zip", false, false) + "]";
        assertThrows(IOException.class, () -> DataPackReleaseClient.selectRelease(MAPPER.readTree(payload)));
    }

    @Test
    void rejectsUnverifiedMetadata() throws Exception {
        String payload = """
                [{"tag_name":"data-v2026.10.25","draft":false,"prerelease":false,
                  "assets":[{"name":"logbook-data-pack.zip","digest":"sha256:bad",
                  "browser_download_url":"https://github.com/ItukiMac/logbook-kai-maintained/releases/download/data-v2026.10.25/logbook-data-pack.zip"}]}]
                """;
        assertThrows(IOException.class, () -> DataPackReleaseClient.selectRelease(MAPPER.readTree(payload)));
    }
}
