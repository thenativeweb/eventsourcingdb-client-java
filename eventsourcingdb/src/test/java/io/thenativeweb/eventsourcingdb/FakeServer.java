package io.thenativeweb.eventsourcingdb;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

// FakeServer stands in for EventSourcingDB where a real instance can not
// produce the behavior under test, e.g. a missing Server header, a broken
// response, or a stream that stalls. It offers the v1 or the v2 API, and
// answers the probe of the client accordingly, so that its handler sees the
// requests of the operations only.
final class FakeServer implements AutoCloseable {
    static final String SERVER_HEADER = "EventSourcingDB/fake";

    static final String PROBE_PATH = "/api/v2/system/ping";

    private final HttpServer server;
    private final CountDownLatch released = new CountDownLatch(1);
    private final CountDownLatch requested = new CountDownLatch(1);
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicBoolean hasBeenProbed = new AtomicBoolean();
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    // A request as the server received it.
    record Request(String method, String path, String body) {}

    private FakeServer(@Nullable ApiVersion api, Handler handler) {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }

        HttpHandler httpHandler = exchange -> {
            try (exchange) {
                var body = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
                var request = new Request(
                        exchange.getRequestMethod(), exchange.getRequestURI().getPath(), body);
                requests.add(request);

                // The probe comes first. A ping to the v2 API afterwards is
                // a request of an operation.
                if (api != null && isProbe(api, request)) {
                    api.answerProbe(exchange);
                    return;
                }

                requestCount.incrementAndGet();
                requested.countDown();
                handler.handle(exchange, this);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (IOException ex) {
                // The client may close the connection while the handler is still
                // writing, which is what some of the tests do on purpose.
            }
        };

        server.createContext("/", httpHandler);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    private boolean isProbe(ApiVersion api, Request request) {
        if (!request.path().equals(PROBE_PATH)) {
            return false;
        }

        return api == ApiVersion.V1 || hasBeenProbed.compareAndSet(false, true);
    }

    // Starts a server that offers the given API.
    static FakeServer start(ApiVersion api, Handler handler) {
        return new FakeServer(api, handler);
    }

    // Starts a server that leaves the probe to the handler, too.
    static FakeServer start(Handler handler) {
        return new FakeServer(null, handler);
    }

    static FakeServer respondingWith(ApiVersion api, int statusCode, String body) {
        return start(api, (exchange, server) -> respond(exchange, statusCode, body));
    }

    // Starts a server that answers with a stream of the given items, which
    // ends as a stream that is complete.
    static FakeServer streaming(ApiVersion api, String... items) {
        return start(api, (exchange, server) -> {
            startStream(exchange);
            for (var item : items) {
                writeLine(exchange, item);
            }
            writeLine(exchange, api.end());
        });
    }

    static URI unusedUrl() {
        try (var socket = new ServerSocket(0)) {
            return URI.create("http://localhost:" + socket.getLocalPort());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    static void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        var bytes = body.getBytes(UTF_8);

        exchange.getResponseHeaders().set("Server", SERVER_HEADER);
        exchange.sendResponseHeaders(statusCode, bytes.length == 0 ? -1 : bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    static void startStream(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Server", SERVER_HEADER);
        exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
        exchange.sendResponseHeaders(200, 0);
    }

    static void writeLine(HttpExchange exchange, String line) throws IOException {
        var body = exchange.getResponseBody();
        body.write((line + "\n").getBytes(UTF_8));
        body.flush();
    }

    // Blocks until the test closes the server, so that a request or a stream
    // gets no further answer.
    void hang() throws InterruptedException {
        released.await();
    }

    // Waits for the first request of an operation, i.e. one that is not the
    // probe.
    void awaitRequest() throws InterruptedException {
        requested.await();
    }

    URI url() {
        return URI.create("http://localhost:" + server.getAddress().getPort());
    }

    // The number of requests of operations, i.e. without the probe.
    int requestCount() {
        return requestCount.get();
    }

    List<Request> requests() {
        return List.copyOf(requests);
    }

    @Override
    public void close() {
        released.countDown();
        server.stop(0);
    }

    @FunctionalInterface
    interface Handler {
        void handle(HttpExchange exchange, FakeServer server) throws IOException, InterruptedException;
    }
}
