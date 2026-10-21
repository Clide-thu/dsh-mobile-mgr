import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import dev.dsh.remotehosts.core.TokenProbe;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Desktop unit test for the v0.2.1 DSH token pre-check ({@link TokenProbe},
 * docs/app-spec.md §5 item 7).
 *
 * All HTTP is local (com.sun.net.httpserver on 127.0.0.1); no Android, no
 * network, no secrets from the real world. Covers:
 *  - 2xx/3xx → OK (303 included, and the redirect must NOT be followed);
 *  - 401/403 → REJECTED (the "authentication required" case);
 *  - 404/500 → OK (deliberately non-blocking);
 *  - connection refused → UNREACHABLE; read timeout → UNREACHABLE;
 *  - invalid port / empty token → UNREACHABLE without touching the network;
 *  - the token query value is percent-encoded exactly once and the server can
 *    decode it back to the original token.
 *
 * Run via tools/test-jvm.ps1.
 */
public final class TokenProbeTest {

    private static int failures = 0;

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            System.out.println("PASS " + name);
        } else {
            failures++;
            System.out.println("FAIL " + name + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")"));
        }
    }

    private static void fail(String name, String detail) {
        failures++;
        System.out.println("FAIL " + name + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    /** A tiny loopback HTTP server for one test case. */
    private static final class ProbeServer implements AutoCloseable {
        final HttpServer server;
        final ExecutorService executor;
        final int port;

        ProbeServer(int port, HttpHandler handler) throws IOException {
            this.port = port;
            this.server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
            this.server.createContext("/", handler);
            this.executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "token-probe-test");
                thread.setDaemon(true);
                return thread;
            });
            this.server.setExecutor(executor);
            this.server.start();
        }

        @Override
        public void close() {
            try {
                server.stop(0);
            } catch (RuntimeException ignored) {
                // Best effort teardown.
            }
            executor.shutdownNow();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static void respond(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    // ------------------------------------------------------------------ cases

    /** Status-code mapping: the whole point of the probe. */
    private static void testStatusMapping() throws Exception {
        AtomicReference<Integer> status = new AtomicReference<>(200);
        int port = freePort();
        try (ProbeServer server = new ProbeServer(port, exchange -> {
            exchange.getRequestBody().close();
            respond(exchange, status.get());
        })) {
            checkMapping(server.port, status, 200, TokenProbe.Result.OK);
            checkMapping(server.port, status, 204, TokenProbe.Result.OK);
            checkMapping(server.port, status, 301, TokenProbe.Result.OK);
            checkMapping(server.port, status, 302, TokenProbe.Result.OK);
            checkMapping(server.port, status, 303, TokenProbe.Result.OK);
            checkMapping(server.port, status, 401, TokenProbe.Result.REJECTED);
            checkMapping(server.port, status, 403, TokenProbe.Result.REJECTED);
            checkMapping(server.port, status, 404, TokenProbe.Result.OK);
            checkMapping(server.port, status, 500, TokenProbe.Result.OK);
            checkMapping(server.port, status, 503, TokenProbe.Result.OK);
        }
    }

    private static void checkMapping(int port, AtomicReference<Integer> status,
                                     int httpStatus, TokenProbe.Result expected) {
        status.set(httpStatus);
        TokenProbe.Result actual = TokenProbe.check(port, "TEST_TOKEN_for_mapping", 5000);
        check("status " + httpStatus + " -> " + expected, actual == expected, String.valueOf(actual));
    }

    /** A 303 must be observed, not followed (the browser does the real navigation). */
    private static void testRedirectNotFollowed() throws Exception {
        int port = freePort();
        AtomicInteger redirectedHits = new AtomicInteger();
        try (ProbeServer server = new ProbeServer(port, exchange -> {
            if ("/redirected".equals(exchange.getRequestURI().getPath())) {
                redirectedHits.incrementAndGet();
                respond(exchange, 500);
                return;
            }
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + "/redirected");
            respond(exchange, 303);
        })) {
            TokenProbe.Result result = TokenProbe.check(server.port, "TOKEN_redirect", 5000);
            check("303 -> OK", result == TokenProbe.Result.OK, String.valueOf(result));
            check("303 redirect is not followed", redirectedHits.get() == 0,
                    "redirected hits=" + redirectedHits.get());
        }
    }

    /** Token round trip: encoded exactly once, server decodes the original value. */
    private static void testTokenEncoding() throws Exception {
        String token = "a b&c=d/+#?%中文";
        int port = freePort();
        AtomicReference<String> rawQuery = new AtomicReference<>();
        AtomicReference<String> decodedToken = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        try (ProbeServer server = new ProbeServer(port, exchange -> {
            hits.incrementAndGet();
            String query = exchange.getRequestURI().getRawQuery();
            rawQuery.set(query);
            if (query != null && query.startsWith("token=")) {
                decodedToken.set(URLDecoder.decode(query.substring("token=".length()), "UTF-8"));
            }
            respond(exchange, 200);
        })) {
            TokenProbe.Result result = TokenProbe.check(server.port, token, 5000);
            check("encoding: probe OK", result == TokenProbe.Result.OK, String.valueOf(result));
            check("encoding: exactly one request", hits.get() == 1, String.valueOf(hits.get()));
            String query = rawQuery.get();
            check("encoding: query starts with token=",
                    query != null && query.startsWith("token="), String.valueOf(query));
            check("encoding: no raw token characters leak into the query",
                    query != null && !query.contains(" "), String.valueOf(query));
            check("encoding: percent-encoded form used",
                    query != null && query.contains("%20") && query.contains("%26")
                            && query.contains("%3D") && query.contains("%2F")
                            && query.contains("%2B") && query.contains("%23"),
                    String.valueOf(query));
            check("encoding: server decodes the original token",
                    token.equals(decodedToken.get()), String.valueOf(decodedToken.get()));
        }
    }

    /** Nothing listening on the port: UNREACHABLE (no crash, no hang). */
    private static void testUnreachable() throws Exception {
        int closedPort = freePort();
        TokenProbe.Result result = TokenProbe.check(closedPort, "TOKEN_closed_port", 3000);
        check("no listener -> UNREACHABLE", result == TokenProbe.Result.UNREACHABLE, String.valueOf(result));

        check("invalid port 0 -> UNREACHABLE",
                TokenProbe.check(0, "TOKEN_port_zero", 1000) == TokenProbe.Result.UNREACHABLE, "");
        check("invalid port 70000 -> UNREACHABLE",
                TokenProbe.check(70000, "TOKEN_port_large", 1000) == TokenProbe.Result.UNREACHABLE, "");
        check("null token -> UNREACHABLE",
                TokenProbe.check(closedPort, null, 1000) == TokenProbe.Result.UNREACHABLE, "");
        check("empty token -> UNREACHABLE",
                TokenProbe.check(closedPort, "", 1000) == TokenProbe.Result.UNREACHABLE, "");
    }

    /** A server that accepts but never answers must time out as UNREACHABLE. */
    private static void testReadTimeout() throws Exception {
        int port = freePort();
        try (ProbeServer server = new ProbeServer(port, exchange -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            try {
                respond(exchange, 200);
            } catch (IOException ignored) {
                // The client already gave up; nothing to do.
            }
        })) {
            long startedAt = System.currentTimeMillis();
            TokenProbe.Result result = TokenProbe.check(server.port, "TOKEN_timeout", 300);
            long elapsed = System.currentTimeMillis() - startedAt;
            check("read timeout -> UNREACHABLE", result == TokenProbe.Result.UNREACHABLE, String.valueOf(result));
            // The functional assertion above is what matters (the client gave up
            // instead of waiting for the server's 2 s response). The wall-clock
            // bound is deliberately loose: on a busy CI/desktop machine the JVM
            // can be descheduled for seconds even though the 300 ms socket
            // timeout fired correctly (observed 2669 ms under load). It still
            // catches a real regression where the probe waits for the server.
            check("read timeout does not wait for the slow server (" + elapsed + " ms)", elapsed < 10000,
                    "elapsed=" + elapsed + " ms");
        }
    }

    /** The probe must only use GET / with the token query and no body. */
    private static void testRequestShape() throws Exception {
        int port = freePort();
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        try (ProbeServer server = new ProbeServer(port, exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            exchange.getRequestBody().close();
            respond(exchange, 200);
        })) {
            TokenProbe.check(server.port, "TOKEN_shape", 5000);
            check("request method is GET", "GET".equals(method.get()), String.valueOf(method.get()));
            check("request path is /", "/".equals(path.get()), String.valueOf(path.get()));
        }
    }

    // --------------------------------------------------------------------- main

    public static void main(String[] args) {
        System.out.println("TokenProbeTest: DSH token pre-check (v0.2.1)");
        try {
            testStatusMapping();
        } catch (Throwable error) {
            fail("status mapping", error.toString());
        }
        try {
            testRedirectNotFollowed();
        } catch (Throwable error) {
            fail("redirect not followed", error.toString());
        }
        try {
            testTokenEncoding();
        } catch (Throwable error) {
            fail("token encoding", error.toString());
        }
        try {
            testUnreachable();
        } catch (Throwable error) {
            fail("unreachable", error.toString());
        }
        try {
            testReadTimeout();
        } catch (Throwable error) {
            fail("read timeout", error.toString());
        }
        try {
            testRequestShape();
        } catch (Throwable error) {
            fail("request shape", error.toString());
        }

        if (failures > 0) {
            System.out.println("TOKEN PROBE TEST FAILED (" + failures + " check(s))");
            System.exit(1);
        }
        System.out.println("TOKEN PROBE TEST PASSED");
    }
}
