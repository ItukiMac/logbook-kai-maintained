package logbook.internal.proxy;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.sun.net.httpserver.HttpServer;
import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.eclipse.jetty.ee8.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ConnectHandler;
import org.junit.jupiter.api.Test;

/**
 * Real loopback HTTP and CONNECT smoke tests, independent of GUI/game traffic.
 */
class Jetty12Ee8ProxyIntegrationTest {
    @Test
    void forwardHttpGetAndPostPreservesHeadersAndPayload() throws Exception {
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/proxy-check", exchange -> {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            byte[] output = (exchange.getRequestMethod() + ":" +
                exchange.getRequestHeaders().getFirst("X-Test-Header") + ":" +
                new String(requestBody, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, output.length);
            try (OutputStream stream = exchange.getResponseBody()) {
                stream.write(output);
            }
        });
        upstream.start();

        Server proxy = proxyServer();
        try {
            int port = ((ServerConnector) proxy.getConnectors()[0]).getLocalPort();
            int upstreamPort = upstream.getAddress().getPort();
            String base = "http://127.0.0.1:" + upstreamPort + "/proxy-check";

            String get = request(port, "GET " + base + " HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:" + upstreamPort + "\r\n" +
                    "X-Test-Header: smoke\r\nConnection: close\r\n\r\n");
            assertTrue(get.startsWith("HTTP/1.1 200"), get);
            assertTrue(get.contains("GET:smoke:"), get);

            String payload = "hello-jetty12";
            String post = request(port, "POST " + base + " HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:" + upstreamPort + "\r\n" +
                    "X-Test-Header: smoke\r\nContent-Length: " + payload.length() +
                    "\r\nConnection: close\r\n\r\n" + payload);
            assertTrue(post.startsWith("HTTP/1.1 200"), post);
            assertTrue(post.contains("POST:smoke:" + payload), post);
        } finally {
            proxy.stop();
            upstream.stop(0);
        }
    }

    @Test
    void tunnelConnectForwardsBytes() throws Exception {
        try (ServerSocket echoServer = new ServerSocket(0, 1,
                java.net.InetAddress.getByName("127.0.0.1"));
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> echo = executor.submit(() -> {
                try (Socket peer = echoServer.accept()) {
                    byte[] bytes = peer.getInputStream().readNBytes(4);
                    peer.getOutputStream().write(bytes);
                    peer.getOutputStream().flush();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            Server proxy = proxyServer();
            try {
                int port = ((ServerConnector) proxy.getConnectors()[0]).getLocalPort();
                try (Socket client = new Socket("127.0.0.1", port)) {
                    client.setSoTimeout(6000);
                    String target = "127.0.0.1:" + echoServer.getLocalPort();
                    client.getOutputStream().write(("CONNECT " + target + " HTTP/1.1\r\n" +
                            "Host: " + target + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    InputStream in = client.getInputStream();
                    ByteArrayOutputStream headers = new ByteArrayOutputStream();
                    while (!headers.toString(StandardCharsets.US_ASCII).contains("\r\n\r\n")) {
                        int next = in.read();
                        assertNotEquals(-1, next);
                        headers.write(next);
                        assertTrue(headers.size() < 4096);
                    }
                    assertTrue(headers.toString(StandardCharsets.US_ASCII).contains(" 200 "),
                        headers.toString(StandardCharsets.US_ASCII));
                    client.getOutputStream().write("PING".getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().flush();
                    assertArrayEquals("PING".getBytes(StandardCharsets.US_ASCII), in.readNBytes(4));
                }
                echo.get();
            } finally {
                proxy.stop();
            }
        }
    }

    private static Server proxyServer() throws Exception {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("127.0.0.1");
        connector.setPort(0);
        server.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        ServletHolder servlet = new ServletHolder(new ReverseProxyServlet());
        servlet.setInitParameter("maxThreads", "8");
        context.addServlet(servlet, "/*");
        server.setHandler(new ConnectHandler(context.get()));
        server.start();
        return server;
    }

    private static String request(int port, String text) throws Exception {
        try (Socket client = new Socket("127.0.0.1", port)) {
            client.setSoTimeout(8000);
            client.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            client.getInputStream().transferTo(result);
            return result.toString(StandardCharsets.UTF_8);
        }
    }
}
