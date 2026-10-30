package local.kancolle.bridge;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import logbook.core.LogBookCoreServices;
import logbook.listener.ContentListenerSpi;
import logbook.net.RequestMetaData;
import logbook.net.ResponseMetaData;
import logbook.plugin.lifecycle.StartUp;

/**
 * Receives already-captured Kancolle traffic from the Chrome extension on
 * 127.0.0.1:8891 and feeds it into logbook-kai's existing ContentListenerSpi
 * pipeline. It does not make requests to the Kancolle servers.
 */
public final class KancolleBridgeStartUp implements StartUp {
    private static final int PORT = 8891;
    private static final int MAGIC = 0x4B435031; // "KCP1"
    private static final int VERSION_V1 = 1;
    private static final int VERSION_V2 = 2;
    private static final int MAX_PACKET = 32 * 1024 * 1024;

    private static final AtomicLong RECEIVED = new AtomicLong();
    private static final AtomicLong ACCEPTED = new AtomicLong();
    private static final AtomicLong API_COUNT = new AtomicLong();
    private static final AtomicLong IMAGE_COUNT = new AtomicLong();
    private static final AtomicLong JSON_COUNT = new AtomicLong();
    private static final AtomicLong ERRORS = new AtomicLong();

    private static final long HEARTBEAT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final String STATUS_BAR_ID = "kancolle-cdp-bridge-status";
    private static final String STATUS_LABEL_ID = "kancolle-cdp-bridge-status-label";
    private static final AtomicLong LAST_HEARTBEAT_NANOS = new AtomicLong();
    private static volatile ChromeState chromeState = ChromeState.WAITING;
    private static volatile Label statusLabel;

    private volatile boolean running = true;
    private ScheduledExecutorService watchdog;

    private enum ChromeState {
        WAITING,
        CONNECTED,
        LOST
    }

    @Override
    public void run() {
        ThreadPoolExecutor workers = new ThreadPoolExecutor(
            1, 4, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64),
            r -> {
                Thread t = new Thread(r, "kancolle-cdp-bridge-worker");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );

        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 16);
            log("listening on 127.0.0.1:" + PORT);

            this.watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "kancolle-cdp-bridge-watchdog");
                t.setDaemon(true);
                return t;
            });
            this.watchdog.scheduleAtFixedRate(
                KancolleBridgeStartUp::watchdogTick,
                5L, 5L, TimeUnit.SECONDS
            );
            updateMainWindowStatus();

            while (running && !Thread.currentThread().isInterrupted()) {
                Socket socket = ss.accept();
                workers.execute(() -> handleSocket(socket));
            }
        } catch (BindException e) {
            ERRORS.incrementAndGet();
            log("ERROR: port " + PORT + " is already in use: " + e);
            notifyDesktop(
                "艦これ環境",
                "KLB :8891 の待受に失敗しました。航海日誌連携を確認してください。",
                true
            );
        } catch (SocketException e) {
            if (running) {
                ERRORS.incrementAndGet();
                log("ERROR: socket: " + e);
            }
        } catch (Exception e) {
            ERRORS.incrementAndGet();
            log("ERROR: server: " + e);
        } finally {
            if (this.watchdog != null) {
                this.watchdog.shutdownNow();
            }
            workers.shutdownNow();
        }
    }

    private void handleSocket(Socket socket) {
        try (socket) {
            socket.setSoTimeout(5000);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            String requestLine = readAsciiLine(in);
            if (requestLine == null || requestLine.isBlank()) return;

            String[] parts = requestLine.split(" ", 3);
            if (parts.length < 2) {
                sendText(out, 400, "Bad Request", "bad request");
                return;
            }

            String method = parts[0];
            String path = parts[1];

            int contentLength = 0;
            String line;
            while ((line = readAsciiLine(in)) != null && !line.isEmpty()) {
                int p = line.indexOf(':');
                if (p > 0) {
                    String name = line.substring(0, p).trim();
                    String value = line.substring(p + 1).trim();
                    if (name.equalsIgnoreCase("Content-Length")) {
                        try {
                            contentLength = Integer.parseInt(value);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }

            if ("OPTIONS".equals(method)) {
                sendText(out, 204, "No Content", "");
                return;
            }

            if ("GET".equals(method) && "/health".equals(path)) {
                sendText(out, 200, "OK", healthBody());
                return;
            }

            if ("POST".equals(method) && "/heartbeat".equals(path)) {
                if (contentLength <= 0 || contentLength > 1024) {
                    sendText(out, 400, "Bad Request", "invalid heartbeat");
                    return;
                }

                byte[] heartbeatBody = in.readNBytes(contentLength);
                if (heartbeatBody.length != contentLength) {
                    sendText(out, 400, "Bad Request", "truncated heartbeat");
                    return;
                }

                String heartbeat = new String(
                    heartbeatBody, StandardCharsets.UTF_8
                ).trim();

                if ("active=true".equals(heartbeat)) {
                    markHeartbeat(true);
                } else if ("active=false".equals(heartbeat)) {
                    markHeartbeat(false);
                } else {
                    sendText(out, 400, "Bad Request", "invalid heartbeat");
                    return;
                }

                sendText(out, 200, "OK", healthBody());
                return;
            }

            if (!"POST".equals(method) || !"/ingest".equals(path)) {
                sendText(out, 404, "Not Found", "not found");
                return;
            }

            if (contentLength <= 0 || contentLength > MAX_PACKET) {
                ERRORS.incrementAndGet();
                sendText(out, 413, "Payload Too Large", "invalid length");
                return;
            }

            byte[] body = in.readNBytes(contentLength);
            if (body.length != contentLength) {
                ERRORS.incrementAndGet();
                sendText(out, 400, "Bad Request", "truncated");
                return;
            }

            RECEIVED.incrementAndGet();
            Packet packet = decode(body);
            dispatch(packet);
            ACCEPTED.incrementAndGet();
            incrementTypeCounter(packet);
            refreshHeartbeatFromTraffic();

            sendText(out, 200, "OK", "ok");
        } catch (Exception e) {
            ERRORS.incrementAndGet();
            log("ERROR: ingest: " + e);
        }
    }

    private static String healthBody() {
        long heartbeat = LAST_HEARTBEAT_NANOS.get();
        long ageSeconds = heartbeat == 0L
            ? -1L
            : Math.max(
                0L,
                TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - heartbeat)
            );

        return "OK received=" + RECEIVED.get()
            + " accepted=" + ACCEPTED.get()
            + " api=" + API_COUNT.get()
            + " image=" + IMAGE_COUNT.get()
            + " json=" + JSON_COUNT.get()
            + " errors=" + ERRORS.get()
            + " chrome=" + chromeState.name().toLowerCase(Locale.ROOT)
            + " heartbeatAge=" + ageSeconds
            + " timeout=30";
    }

    private static synchronized void markHeartbeat(boolean active) {
        if (!active) {
            chromeState = ChromeState.WAITING;
            LAST_HEARTBEAT_NANOS.set(0L);
            updateMainWindowStatus();
            return;
        }

        ChromeState previous = chromeState;
        LAST_HEARTBEAT_NANOS.set(System.nanoTime());
        chromeState = ChromeState.CONNECTED;

        if (previous == ChromeState.LOST) {
            notifyDesktop(
                "艦これ環境",
                "KLB の接続が復旧しました。",
                false
            );
        }
        updateMainWindowStatus();
    }

    private static synchronized void refreshHeartbeatFromTraffic() {
        // Successful ingest itself proves that Chrome/CDP is alive.
        // This also arms monitoring if a separate heartbeat has not arrived yet.
        ChromeState previous = chromeState;
        LAST_HEARTBEAT_NANOS.set(System.nanoTime());
        chromeState = ChromeState.CONNECTED;

        if (previous == ChromeState.LOST) {
            notifyDesktop(
                "艦これ環境",
                "KLB の接続が復旧しました。",
                false
            );
        }
        updateMainWindowStatus();
    }

    private static synchronized void watchdogTick() {
        if (chromeState == ChromeState.CONNECTED) {
            long heartbeat = LAST_HEARTBEAT_NANOS.get();
            if (heartbeat != 0L) {
                long age = System.nanoTime() - heartbeat;
                if (age >= HEARTBEAT_TIMEOUT_NANOS) {
                    chromeState = ChromeState.LOST;
                    notifyDesktop(
                        "艦これ環境",
                        "KLB から30秒以上 heartbeat がありません。艦これの通信取得を確認してください。",
                        true
                    );
                }
            }
        }
        updateMainWindowStatus();
    }

    private static void updateMainWindowStatus() {
        try {
            if (!Platform.isFxApplicationThread()) {
                Platform.runLater(KancolleBridgeStartUp::updateMainWindowStatus);
                return;
            }

            Label label = statusLabel;
            if (label == null || label.getScene() == null) {
                label = findOrInstallStatusLabel();
                statusLabel = label;
            }
            if (label == null) return;

            long heartbeat = LAST_HEARTBEAT_NANOS.get();
            long ageSeconds = heartbeat == 0L
                ? -1L
                : Math.max(
                    0L,
                    TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - heartbeat)
                );

            String stateText;
            String color;
            switch (chromeState) {
                case CONNECTED -> {
                    stateText = "接続中";
                    color = "#137333";
                }
                case LOST -> {
                    stateText = "接続断";
                    color = "#b3261e";
                }
                default -> {
                    stateText = "待機中";
                    color = "#8a5a00";
                }
            }

            String ageText = ageSeconds < 0L ? "-" : ageSeconds + "秒";
            label.setText(
                stateText
                    + " | HB " + ageText
                    + " | API " + API_COUNT.get()
                    + " / 画像 " + IMAGE_COUNT.get()
                    + " / JSON " + JSON_COUNT.get()
                    + " | エラー " + ERRORS.get()
            );
            label.setStyle("-fx-text-fill:" + color + "; -fx-font-weight:bold;");
        } catch (Throwable t) {
            log("main-window status unavailable: " + t);
        }
    }

    private static Label findOrInstallStatusLabel() {
        for (Window window : Window.getWindows()) {
            Scene scene = window.getScene();
            if (scene == null) continue;

            Parent root = scene.getRoot();
            if (!(root instanceof VBox rootBox)) continue;
            if (!root.getStyleClass().contains("mainWindow")) continue;

            for (Node node : rootBox.getChildren()) {
                if (STATUS_BAR_ID.equals(node.getId()) && node instanceof HBox bar) {
                    for (Node child : bar.getChildren()) {
                        if (STATUS_LABEL_ID.equals(child.getId()) && child instanceof Label l) {
                            return l;
                        }
                    }
                }
            }

            HBox bar = new HBox(8.0);
            bar.setId(STATUS_BAR_ID);
            bar.setStyle(
                "-fx-padding:4 8 4 8;"
                    + "-fx-background-color:#f1f3f4;"
                    + "-fx-border-color:#d0d4d8;"
                    + "-fx-border-width:0 0 1 0;"
            );

            Label title = new Label("KLB:");
            title.setStyle("-fx-font-weight:bold;");

            Label state = new Label("初期化中");
            state.setId(STATUS_LABEL_ID);

            bar.getChildren().addAll(title, state);
            int index = Math.min(1, rootBox.getChildren().size());
            rootBox.getChildren().add(index, bar);
            return state;
        }
        return null;
    }

    private static void notifyDesktop(String title, String message, boolean warning) {
        try {
            new ProcessBuilder(
                "notify-send",
                "--app-name=航海日誌改",
                "--urgency=" + (warning ? "critical" : "normal"),
                "--expire-time=" + (warning ? "0" : "10000"),
                "--icon=" + (warning ? "dialog-warning" : "dialog-information"),
                title,
                message
            ).start();
        } catch (IOException e) {
            log("notify-send unavailable: " + e.getMessage());
        }
    }

    private static Packet decode(byte[] bytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int magic = in.readInt();
            int version = in.readInt();
            long receivedAt = in.readLong();

            if (magic != MAGIC) {
                throw new IOException("unsupported packet magic");
            }

            if (version == VERSION_V1) {
                String method = readString(in);
                String uri = readString(in);
                String queryString = readString(in);
                String postData = readString(in);
                String responseBody = readString(in);

                validateUri(uri);

                return new Packet(
                    method, uri, queryString, postData,
                    "", "text/plain; charset=UTF-8", 200,
                    responseBody.getBytes(StandardCharsets.UTF_8),
                    receivedAt
                );
            }

            if (version == VERSION_V2) {
                String method = readString(in);
                String uri = readString(in);
                String queryString = readString(in);
                String postData = readString(in);
                String responseEncoding = readString(in);
                String contentType = readString(in);
                String statusString = readString(in);
                String responseBody = readString(in);

                validateUri(uri);

                int status = 200;
                try {
                    status = Integer.parseInt(statusString);
                } catch (NumberFormatException ignored) {}

                byte[] responseBytes;
                if ("base64".equalsIgnoreCase(responseEncoding)) {
                    try {
                        responseBytes = Base64.getDecoder().decode(responseBody);
                    } catch (IllegalArgumentException e) {
                        throw new IOException("invalid base64 response body", e);
                    }
                } else {
                    responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
                }

                if (contentType == null || contentType.isBlank()) {
                    contentType = inferContentType(uri, responseEncoding);
                }

                return new Packet(
                    method, uri, queryString, postData,
                    responseEncoding, contentType, status,
                    responseBytes, receivedAt
                );
            }

            throw new IOException("unsupported packet version: " + version);
        }
    }

    private static void validateUri(String uri) throws IOException {
        if (uri.startsWith("/kcsapi/")) return;
        if (isSupportedKcs2Uri(uri)) return;
        throw new IOException("unsupported URI: " + uri);
    }

    private static boolean isSupportedKcs2Uri(String uri) {
        return uri.startsWith("/kcs2/resources/ship/")
            || uri.startsWith("/kcs2/resources/map/")
            || uri.startsWith("/kcs2/resources/gauge/")
            || uri.startsWith("/kcs2/img/common/")
            || uri.startsWith("/kcs2/img/duty/")
            || uri.startsWith("/kcs2/img/sally/");
    }

    private static String inferContentType(String uri, String encoding) {
        if (uri.endsWith(".json")) return "application/json";
        if ("base64".equalsIgnoreCase(encoding)) return "image/png";
        return "text/plain; charset=UTF-8";
    }

    private static void incrementTypeCounter(Packet packet) {
        if (packet.uri().startsWith("/kcsapi/")) {
            API_COUNT.incrementAndGet();
        } else if (packet.uri().endsWith(".json")) {
            JSON_COUNT.incrementAndGet();
        } else {
            IMAGE_COUNT.incrementAndGet();
        }
    }

    private static void dispatch(Packet packet) {
        long requestAt = System.currentTimeMillis();
        RequestMetaData req = new Req(packet, requestAt);
        ResponseMetaData res = new Res(
            packet.responseBytes(),
            packet.status(),
            packet.contentType(),
            System.currentTimeMillis()
        );

        List<ContentListenerSpi> listeners =
            LogBookCoreServices.getServiceProviders(ContentListenerSpi.class).toList();

        int matched = 0;
        for (ContentListenerSpi listener : listeners) {
            try {
                if (listener.test(req)) {
                    matched++;
                    // Req/Res return a fresh InputStream on every call.
                    listener.accept(req, res);
                }
            } catch (Throwable t) {
                ERRORS.incrementAndGet();
                log("ERROR: listener " + listener.getClass().getName()
                    + " for " + packet.uri() + ": " + t);
            }
        }

        log("accepted " + packet.method() + " " + packet.uri()
            + " listeners=" + matched
            + " response=" + packet.responseBytes().length);
    }

    private record Packet(
        String method,
        String uri,
        String queryString,
        String postData,
        String responseEncoding,
        String contentType,
        int status,
        byte[] responseBytes,
        long receivedAt
    ) {}

    private static final class Req implements RequestMetaData {
        private final Packet p;
        private final long requestAt;
        private final Map<String, List<String>> params;
        private final byte[] requestBody;

        Req(Packet p, long requestAt) {
            this.p = p;
            this.requestAt = requestAt;
            this.params = parseParams(p.queryString(), p.postData());
            this.requestBody = p.postData().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String getContentType() {
            if ("POST".equalsIgnoreCase(p.method())) {
                return "application/x-www-form-urlencoded";
            }
            return "";
        }

        @Override
        public String getMethod() {
            return p.method();
        }

        @Override
        public Map<String, List<String>> getParameterMap() {
            return params;
        }

        @Override
        public String getQueryString() {
            return p.queryString();
        }

        @Override
        public String getRequestURI() {
            return p.uri();
        }

        @Override
        public Optional<InputStream> getRequestBody() {
            if (requestBody.length == 0) return Optional.empty();
            return Optional.of(new ByteArrayInputStream(requestBody));
        }

        @Override
        public Optional<String> getHeader(String name) {
            if ("x-koukainissikai-receivedat".equalsIgnoreCase(name)) {
                return Optional.of(Long.toString(p.receivedAt()));
            }
            if ("x-koukainissikai-requestat".equalsIgnoreCase(name)) {
                return Optional.of(Long.toString(requestAt));
            }
            return Optional.empty();
        }
    }

    private static final class Res implements ResponseMetaData {
        private final byte[] body;
        private final int status;
        private final String contentType;
        private final long responseAt;

        Res(byte[] body, int status, String contentType, long responseAt) {
            this.body = body.clone();
            this.status = status;
            this.contentType = contentType;
            this.responseAt = responseAt;
        }

        @Override
        public int getStatus() {
            return status;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public Optional<InputStream> getResponseBody() {
            return Optional.of(new ByteArrayInputStream(body));
        }

        @Override
        public Optional<String> getHeader(String name) {
            if ("x-koukainissikai-responseat".equalsIgnoreCase(name)) {
                return Optional.of(Long.toString(responseAt));
            }
            return Optional.empty();
        }
    }

    private static Map<String, List<String>> parseParams(String query, String post) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        addEncodedParams(map, query);
        addEncodedParams(map, post);
        Map<String, List<String>> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : map.entrySet()) {
            frozen.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return Collections.unmodifiableMap(frozen);
    }

    private static void addEncodedParams(Map<String, List<String>> map, String encoded) {
        if (encoded == null || encoded.isEmpty()) return;
        String s = encoded.startsWith("?") ? encoded.substring(1) : encoded;
        if (s.isEmpty()) return;

        for (String pair : s.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String rawKey = eq >= 0 ? pair.substring(0, eq) : pair;
            String rawVal = eq >= 0 ? pair.substring(eq + 1) : "";
            String key = urlDecode(rawKey);
            String val = urlDecode(rawVal);
            map.computeIfAbsent(key, k -> new ArrayList<>()).add(val);
        }
    }

    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private static String readString(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > MAX_PACKET) {
            throw new IOException("invalid string length: " + len);
        }
        byte[] b = in.readNBytes(len);
        if (b.length != len) {
            throw new EOFException("truncated string");
        }
        return new String(b, StandardCharsets.UTF_8);
    }

    private static String readAsciiLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        boolean gotAny = false;

        while ((c = in.read()) != -1) {
            gotAny = true;
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("header line too long");
        }

        if (!gotAny && b.size() == 0) return null;
        return b.toString(StandardCharsets.ISO_8859_1);
    }

    private static void sendText(OutputStream out, int code, String reason, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String headers =
            "HTTP/1.1 " + code + " " + reason + "\r\n"
            + "Content-Type: text/plain; charset=UTF-8\r\n"
            + "Content-Length: " + bytes.length + "\r\n"
            + "Access-Control-Allow-Origin: *\r\n"
            + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
            + "Access-Control-Allow-Headers: Content-Type\r\n"
            + "Connection: close\r\n"
            + "\r\n";

        out.write(headers.getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    private static void log(String s) {
        System.out.println("[KancolleCDPBridge] " + s);
    }
}
