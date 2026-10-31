package logbook.internal.proxy;

import static org.junit.jupiter.api.Assertions.*;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.eclipse.jetty.ee8.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ConnectHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import logbook.bean.AppConfig;
import logbook.listener.ContentListenerSpi;
import logbook.net.RequestMetaData;
import logbook.net.ResponseMetaData;

@Timeout(20)
class ProxyCaptureLimitsIntegrationTest {
    private static final class Fixture implements AutoCloseable {
        final HttpServer origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final Server server = new Server();
        final CaptureBudget budget;
        final List<String> delivered = new CopyOnWriteArrayList<>();
        final CountDownLatch small = new CountDownLatch(1);
        final HttpClient client;
        final boolean oldProxy = AppConfig.get().isUseProxy();

        Fixture(int timeout) throws Exception {
            this(timeout, 16);
        }

        Fixture(int timeout, long limit) throws Exception {
            budget = new CaptureBudget(limit * 2, 10000);
            AppConfig.get().setUseProxy(false);
            origin.createContext("/small", exchange -> {
                byte[] body = { 1, 2 };
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            var servlet = new ReverseProxyServlet(new CaptureDispatchQueue(2, 4), budget, limit);
            var field = ReverseProxyServlet.class.getDeclaredField("listeners");
            field.setAccessible(true);
            field.set(servlet, List.of(new ContentListenerSpi() {
                public boolean test(RequestMetaData request) {
                    return true;
                }

                public void accept(RequestMetaData request, ResponseMetaData response) {
                    try {
                        response.getResponseBody().orElseThrow().readAllBytes();
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                    delivered.add(request.getRequestURI());
                    if (request.getRequestURI().equals("/small"))
                        small.countDown();
                }
            }));
            var connector = new ServerConnector(server);
            connector.setHost("127.0.0.1");
            connector.setPort(0);
            server.addConnector(connector);
            var context = new ServletContextHandler(ServletContextHandler.SESSIONS);
            context.setContextPath("/");
            var holder = new ServletHolder(servlet);
            holder.setInitParameter("maxThreads", "8");
            holder.setInitParameter("timeout", Integer.toString(timeout));
            context.addServlet(holder, "/*");
            server.setHandler(new ConnectHandler(context.get()));
            server.start();
            origin.start();
            client = HttpClient.newBuilder()
                    .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", connector.getLocalPort())))
                    .connectTimeout(Duration.ofSeconds(3)).build();
        }

        HttpResponse<byte[]> get(String path) throws Exception {
            return client
                    .send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + origin.getAddress().getPort() + path))
                            .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofByteArray());
        }

        void checkOnlySmall() throws Exception {
            assertEquals(200, get("/small").statusCode());
            assertTrue(small.await(3, TimeUnit.SECONDS));
            assertEquals(List.of("/small"), delivered);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (budget.usedBytes() != 0 && System.nanoTime() < end)
                Thread.sleep(5);
            assertEquals(0, budget.usedBytes());
        }

        public void close() throws Exception {
            client.close();
            server.stop();
            origin.stop(0);
            AppConfig.get().setUseProxy(oldProxy);
        }
    }

    @Test
    void inflatedGzipOverflowIsForwardedButNeverNotified() throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var compressed = new java.util.zip.GZIPOutputStream(bytes)) {
            byte[] zeros = new byte[8192];
            for (int n = 0; n <= logbook.internal.net.ResponseMetaDataWrapper.MAX_DECODED_BYTES / zeros.length; n++)
                compressed.write(zeros);
        }
        byte[] compressed = bytes.toByteArray();
        try (var f = new Fixture(3000, 1024 * 1024)) {
            f.origin.createContext("/gzip", exchange -> {
                exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                exchange.sendResponseHeaders(200, compressed.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(compressed);
                }
            });
            var result = f.get("/gzip");
            assertEquals(200, result.statusCode());
            assertArrayEquals(compressed, result.body());
            f.checkOnlySmall();
        }
    }

    @Test
    void captureOverflowDoesNotTruncateForwardedResponseAndNextRequestStillWorks() throws Exception {
        try (var f = new Fixture(2000)) {
            byte[] large = new byte[100];
            java.util.Arrays.fill(large, (byte) 77);
            f.origin.createContext("/large", exchange -> {
                exchange.sendResponseHeaders(200, 0);
                try (var out = exchange.getResponseBody()) {
                    for (int n = 0; n < 10; n++) {
                        out.write(large, n * 10, 10);
                        out.flush();
                    }
                }
            });
            var result = f.get("/large");
            assertEquals(200, result.statusCode());
            assertArrayEquals(large, result.body());
            f.checkOnlySmall();
        }
    }

    @Test
    void prematurelyClosedResponseIsNeverNotifiedAsComplete() throws Exception {
        try (var f = new Fixture(1000)) {
            f.origin.createContext("/broken", exchange -> {
                exchange.sendResponseHeaders(200, 100);
                try (var out = exchange.getResponseBody()) {
                    out.write(new byte[4]);
                    out.flush();
                } catch (java.io.IOException expected) {
                    exchange.close();
                }
            });
            try {
                f.get("/broken");
            } catch (java.io.IOException expected) {
            }
            f.checkOnlySmall();
        }
    }

    @Test
    void timedOutOriginReleasesCaptureAndDoesNotNotifyPartialData() throws Exception {
        var release = new CountDownLatch(1);
        try (var f = new Fixture(200)) {
            f.origin.createContext("/slow", exchange -> {
                exchange.sendResponseHeaders(200, 100);
                try (var out = exchange.getResponseBody()) {
                    out.write(new byte[4]);
                    out.flush();
                    release.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (java.io.IOException expected) {
                    exchange.close();
                }
            });
            try {
                f.get("/slow");
            } catch (java.io.IOException expected) {
            } finally {
                release.countDown();
            }
            f.checkOnlySmall();
        } finally {
            release.countDown();
        }
    }
}
