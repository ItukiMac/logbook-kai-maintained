package logbook.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end HTTPS-independent transport test. Trusted release selection
 * still requires the fixed GitHub path; only the package-private transport
 * seam is replaced with localhost HTTP, never the production index URL.
 */
class DataReleaseTransportIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path temp;

    private static byte[] packageQuest(String condition) throws Exception {
        byte[] data = condition.getBytes(StandardCharsets.UTF_8);
        String checksum = sha(data);
        byte[] manifest = ("""
                {"format":"logbook-data-pack-v1","source_revision":"fixture",
                 "files":[{"path":"definitions/quest/1045.json","sha256":"%s",
                 "source_sha256":"%s","bytes":%d}]}
                """.formatted(checksum, checksum, data.length)).getBytes(StandardCharsets.UTF_8);
        var bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("definitions/quest/1045.json"));
            zip.write(data);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static String sha(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static JsonNode index(String tag, String digest) throws Exception {
        String url = "https://github.com/ItukiMac/logbook-kai-maintained/releases/download/"
                + tag + "/logbook-data-pack.zip";
        return JSON.readTree("""
                [{"tag_name":"%s","draft":false,"prerelease":false,
                  "assets":[{"name":"logbook-data-pack.zip","digest":"sha256:%s",
                    "browser_download_url":"%s"}]}]
                """.formatted(tag, digest, url));
    }

    @Test
    void downloadsAndAtomicallyChangesGenerationWhileFailuresKeepOldData() throws Exception {
        AtomicReference<byte[]> payload = new AtomicReference<>(packageQuest("{\"type\":\"出撃\"}"));
        AtomicInteger status = new AtomicInteger(200);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/asset", exchange -> {
            try {
                if (status.get() != 200) {
                    exchange.sendResponseHeaders(status.get(), -1);
                } else {
                    byte[] bytes = payload.get();
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(bytes);
                    }
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            URI localAsset = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/asset");
            DataPackReleaseClient.ReleaseDownloader loopback = (trustedUrl, destination, digest) -> {
                assertEquals("github.com", trustedUrl.getHost());
                DataPackReleaseClient.downloadLimited(localAsset, destination, digest);
            };
            Path root = temp.resolve("data");
            byte[] first = payload.get();
            JsonNode firstIndex = index("data-v2026.10.25.1", sha(first));
            assertTrue(DataPackReleaseClient.applyRelease(firstIndex, root, loopback));
            assertFalse(DataPackReleaseClient.applyRelease(firstIndex, root, loopback));
            Path firstGeneration = DefinitionPackInstaller.activeDefinitionsRoot(root);
            assertEquals("{\"type\":\"出撃\"}",
                    Files.readString(firstGeneration.resolve("quest/1045.json")));

            // The same server returns a different, correctly hashed Release.
            payload.set(packageQuest("{\"type\":\"遠征\"}"));
            JsonNode nextIndex = index("data-v2026.10.25.2", sha(payload.get()));
            assertTrue(DataPackReleaseClient.applyRelease(nextIndex, root, loopback));
            Path secondGeneration = DefinitionPackInstaller.activeDefinitionsRoot(root);
            assertNotEquals(firstGeneration, secondGeneration);
            assertEquals("{\"type\":\"出撃\"}",
                    Files.readString(firstGeneration.resolve("quest/1045.json")));
            assertEquals("{\"type\":\"遠征\"}",
                    Files.readString(secondGeneration.resolve("quest/1045.json")));

            // Use a genuinely new checksum; otherwise the updater correctly
            // skips the transport because the active generation already matches.
            payload.set(packageQuest("{\"type\":\"after-outage\"}"));
            status.set(503);
            JsonNode downIndex = index("data-v2026.10.25.3", sha(payload.get()));
            assertThrows(IOException.class,
                    () -> DataPackReleaseClient.applyRelease(downIndex, root, loopback));
            assertEquals(secondGeneration, DefinitionPackInstaller.activeDefinitionsRoot(root));
            status.set(200);

            // Corrupt bytes with metadata for an imagined distinct version.
            JsonNode incorrectDigest = index("data-v2026.10.25.4", "0".repeat(64));
            assertThrows(IOException.class,
                    () -> DataPackReleaseClient.applyRelease(incorrectDigest, root, loopback));
            assertEquals(secondGeneration, DefinitionPackInstaller.activeDefinitionsRoot(root));
        } finally {
            server.stop(0);
        }
    }
}
