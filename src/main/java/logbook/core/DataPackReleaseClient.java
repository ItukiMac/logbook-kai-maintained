package logbook.core;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * Opt-in retrieval of a game-data-only GitHub Release from the maintained repository.
 *
 * Production is disabled by default. Selection/download authentication relies
 * on HTTPS + the trusted GitHub repository and its asset SHA-256 metadata.
 * Independent release signing and user-facing rollout controls remain future work.
 */
public final class DataPackReleaseClient {
    public static final String AUTO_UPDATE_PROPERTY = "logbook.data.autoUpdate";
    private static final ObjectMapper JSON = logbook.internal.json.JsonMappers.builder().build();
    private static final String OWNER_REPO = "ItukiMac/logbook-kai-maintained";
    private static final String INDEX_URL = "https://api.github.com/repos/" + OWNER_REPO + "/releases?per_page=100";
    private static final String ASSET_NAME = "logbook-data-pack.zip";
    private static final String CAPABILITY_ASSET_NAME = "logbook-data-pack-v2.zip";
    private static final long MAX_DOWNLOAD_BYTES = 128L * 1024 * 1024;

    private DataPackReleaseClient() {
    }

    /**
     * Only call when the user explicitly configured both the data root and
     * logbook.data.autoUpdate=true. This function must run before game state
     * and plugins initialize to avoid mixing different definition generations.
     */
    public static boolean refreshConfiguredData() throws IOException {
        try {
        if (!Boolean.getBoolean(AUTO_UPDATE_PROPERTY)) return false;
        String rootValue = System.getProperty(ExternalQuestDefinitions.ROOT_PROPERTY);
        if (rootValue == null || rootValue.isBlank()) {
            throw new IOException("Data auto-update needs -Dlogbook.data.root");
        }
        Path root = Path.of(rootValue).toAbsolutePath().normalize();
        JsonNode index = JSON.readTree(readLimited(URI.create(INDEX_URL), 8L * 1024 * 1024));
        return applyRelease(index, root, DataPackReleaseClient::downloadLimited);
        } catch (tools.jackson.core.JacksonException jsonCompatibilityFailure) {
            throw new java.io.IOException("Invalid or unreadable JSON", jsonCompatibilityFailure);
        }
    }

    /**
     * Package-private seam for a loopback HTTP integration test. Production
     * always invokes this with the real HTTP downloader and trusted fixed URL.
     */
    static boolean applyRelease(JsonNode releaseIndex, Path root, ReleaseDownloader downloader)
            throws IOException {
        Optional<DataRelease> selected = selectRelease(releaseIndex);
        if (selected.isEmpty()) return false;

        DataRelease release = selected.get();
        Path active;
        try {
            active = DefinitionPackInstaller.activeDefinitionsRoot(root);
        } catch (IOException ignored) {
            active = null;
        }
        if (active != null && active.equals(root.resolve("generations")
                .resolve(release.sha256()).resolve("definitions"))) {
            return false;
        }

        Path downloaded = Files.createTempFile("logbook-data-download-", ".zip");
        try {
            downloader.download(release.download(), downloaded, release.sha256());
            DefinitionPackInstaller.install(root, downloaded, release.sha256());
            return true;
        } finally {
            Files.deleteIfExists(downloaded);
        }
    }

    static Optional<DataRelease> selectRelease(JsonNode releases) throws IOException {
        if (releases == null || !releases.isArray()) {
            throw new IOException("GitHub Release list is missing");
        }
        for (JsonNode release : releases) {
            String tag = release.path("tag_name").asText("");
            if (release.path("draft").asBoolean(false)
                    || release.path("prerelease").asBoolean(false)
                    || !tag.matches("data-v[A-Za-z0-9._-]{1,80}")) {
                continue;
            }
            JsonNode assets = release.path("assets");
            if (!assets.isArray()) continue;
            for (String assetName : new String[] {CAPABILITY_ASSET_NAME, ASSET_NAME}) {
              for (JsonNode asset : assets) {
                if (!assetName.equals(asset.path("name").asText())) continue;
                String digest = asset.path("digest").asText("");
                String url = asset.path("browser_download_url").asText("");
                if (!digest.matches("sha256:[0-9a-fA-F]{64}")) {
                    throw new IOException("Data Release SHA-256 digest is invalid");
                }
                URI uri;
                try {
                    uri = URI.create(url);
                } catch (IllegalArgumentException e) {
                    throw new IOException("Malformed data Release URL", e);
                }
                if (!isTrustedAssetUri(uri, tag, assetName)) {
                    throw new IOException("Data Release asset is outside maintained GitHub repository");
                }
                return Optional.of(new DataRelease(tag,
                        digest.substring(7).toLowerCase(Locale.ROOT), uri));
              }
            }
        }
        return Optional.empty();
    }

    private static boolean isTrustedAssetUri(URI uri, String tag, String assetName) {
        return "https".equalsIgnoreCase(uri.getScheme())
                && "github.com".equalsIgnoreCase(uri.getHost())
                && uri.getPort() == -1
                && uri.getRawUserInfo() == null
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null
                && ("/" + OWNER_REPO + "/releases/download/" + tag + "/" + assetName)
                    .equals(uri.getRawPath());
    }

    private static byte[] readLimited(URI uri, long maximum) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "logbook-kai-maintained");
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub HTTP " + connection.getResponseCode());
            }
            try (InputStream in = connection.getInputStream();
                    var out = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > maximum) {
                        throw new IOException("Release index exceeds size limit");
                    }
                    out.write(buffer, 0, n);
                }
                return out.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    @FunctionalInterface
    interface ReleaseDownloader {
        void download(URI uri, Path destination, String sha256) throws IOException;
    }

    /** Package-private for offline loopback transport integration tests. */
    static void downloadLimited(URI uri, Path out, String expectedDigest) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "logbook-kai-maintained");
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("Data archive HTTP " + connection.getResponseCode());
            }
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IOException("SHA-256 is unavailable", e);
            }
            try (InputStream in = connection.getInputStream();
                    var stream = Files.newOutputStream(out)) {
                long total = 0;
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    total += n;
                    if (total > MAX_DOWNLOAD_BYTES) {
                        throw new IOException("Definition download exceeds size limit");
                    }
                    stream.write(buffer, 0, n);
                    digest.update(buffer, 0, n);
                }
                if (total == 0) throw new IOException("Empty definition archive");
            }
            if (!expectedDigest.equals(HexFormat.of().formatHex(digest.digest()))) {
                throw new IOException("Data Release SHA-256 mismatch");
            }
        } finally {
            connection.disconnect();
        }
    }

    record DataRelease(String tag, String sha256, URI download) {}
}
