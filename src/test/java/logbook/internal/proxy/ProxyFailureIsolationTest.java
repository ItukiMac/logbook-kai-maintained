package logbook.internal.proxy;

import static org.junit.jupiter.api.Assertions.*;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.eclipse.jetty.ee8.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ConnectHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import logbook.bean.AppConfig;
import logbook.listener.ContentListenerSpi;
import logbook.net.RequestMetaData;
import logbook.net.ResponseMetaData;

@Timeout(15)
class ProxyFailureIsolationTest {
    private boolean oldUseProxy;
    private String oldHost;
    private int oldPort;

    @BeforeEach
    void saveConfiguration() {
        var config = AppConfig.get();
        oldUseProxy = config.isUseProxy();
        oldHost = config.getProxyHost();
        oldPort = config.getProxyPort();
        config.setUseProxy(false);
    }

    @AfterEach
    void restoreConfiguration() {
        var config = AppConfig.get();
        config.setUseProxy(oldUseProxy);
        config.setProxyHost(oldHost);
        config.setProxyPort(oldPort);
    }

    private record Proxy(Server server, HttpClient client) implements AutoCloseable {
        public void close() throws Exception {
            client.close();
            server.stop();
        }
    }

    private static Proxy proxy(List<ContentListenerSpi> listeners, int timeout) throws Exception {
        var servlet = new ReverseProxyServlet();
        var field = ReverseProxyServlet.class.getDeclaredField("listeners");
        field.setAccessible(true);
        field.set(servlet, listeners);
        var server = new Server();
        var connector = new ServerConnector(server);
        connector.setHost("127.0.0.1");
        connector.setPort(0);
        server.addConnector(connector);
        var context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        var holder = new ServletHolder(servlet);
        holder.setInitParameter("maxThreads", "8");
        holder.setInitParameter("timeout", Integer.toString(timeout));
        holder.setInitParameter("connectTimeout", "500");
        context.addServlet(holder, "/*");
        server.setHandler(new ConnectHandler(context.get()));
        try {
            server.start();
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                    .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", connector.getLocalPort())))
                    .build();
            return new Proxy(server, client);
        } catch (Exception e) {
            server.stop();
            throw e;
        }
    }

    private static HttpServer origin() throws Exception {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    private static HttpRequest.Builder request(String target) {
        return HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofSeconds(8));
    }

    @Test
    void forwardsViaConfiguredUpstreamWithoutResolvingOrigin() throws Exception {
        HttpServer upstream = origin();
        AtomicReference<String> target = new AtomicReference<>();
        upstream.createContext("/", exchange -> {
            target.set(exchange.getRequestURI().toString());
            String text = exchange.getRequestMethod() + ":" + exchange.getRequestHeaders().getFirst("X-Check")
                    + ":" + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] output = text.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, output.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(output);
            }
        });
        upstream.start();
        var config = AppConfig.get();
        config.setUseProxy(true);
        config.setProxyHost("127.0.0.1");
        config.setProxyPort(upstream.getAddress().getPort());
        try (Proxy proxy = proxy(List.of(), 3000)) {
            var response = proxy.client().send(request("http://origin.invalid/through-upstream")
                    .header("X-Check", "preserved").POST(HttpRequest.BodyPublishers.ofString("hello")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("POST:preserved:hello", response.body());
            assertTrue(target.get().contains("/through-upstream"));
        } finally {
            upstream.stop(0);
        }
    }

    @Test
    void refusedOriginBecomesAnHttpFailureInsteadOfHanging() throws Exception {
        int unused;
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            unused = socket.getLocalPort();
        }
        try (Proxy proxy = proxy(List.of(), 500)) {
            var response = proxy.client().send(request("http://127.0.0.1:" + unused + "/").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(response.statusCode() >= 500 && response.statusCode() <= 599);
        }
    }

    @Test
    void predicateFailureDoesNotSuppressLaterListener() throws Exception {
        assertListenerIsolation(true);
    }

    @Test
    void acceptFailureDoesNotSuppressLaterListener() throws Exception {
        assertListenerIsolation(false);
    }

    private static void assertListenerIsolation(boolean failInTest) throws Exception {
        HttpServer upstream = origin();
        upstream.createContext("/", exchange -> {
            byte[] body = "complete-response".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        upstream.start();
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<String> content = new AtomicReference<>();
        ContentListenerSpi broken = new ContentListenerSpi() {
            public boolean test(RequestMetaData request) {
                if (failInTest)
                    throw new IllegalStateException("isolated predicate fixture");
                return true;
            }

            public void accept(RequestMetaData request, ResponseMetaData response) {
                throw new IllegalStateException("isolated accept fixture");
            }
        };
        ContentListenerSpi healthy = new ContentListenerSpi() {
            public boolean test(RequestMetaData request) {
                return true;
            }

            public void accept(RequestMetaData request, ResponseMetaData response) {
                try (var in = response.getResponseBody().orElseThrow()) {
                    content.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (Exception ex) {
                    content.set(ex.toString());
                } finally {
                    received.countDown();
                }
            }
        };
        try (Proxy proxy = proxy(List.of(broken, healthy), 3000)) {
            var response = proxy.client().send(
                    request("http://127.0.0.1:" + upstream.getAddress().getPort() + "/").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(received.await(5, TimeUnit.SECONDS));
            assertEquals("complete-response", content.get());
        } finally {
            upstream.stop(0);
        }
    }
}
