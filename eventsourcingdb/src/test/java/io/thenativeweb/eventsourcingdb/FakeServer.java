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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

// FakeServer stands in for EventSourcingDB where a real instance can not
// produce the behavior under test, e.g. a missing Server header, a broken
// response, or a stream that stalls.
final class FakeServer implements AutoCloseable {
    static final String SERVER_HEADER = "EventSourcingDB/fake";

    private final HttpServer server;
    private final CountDownLatch released = new CountDownLatch(1);
    private final CountDownLatch requested = new CountDownLatch(1);
    private final AtomicInteger requestCount = new AtomicInteger();

    private FakeServer(Handler handler) {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }

        HttpHandler httpHandler = exchange -> {
            requestCount.incrementAndGet();
            requested.countDown();
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
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

    static FakeServer start(Handler handler) {
        return new FakeServer(handler);
    }

    static FakeServer respondingWith(int statusCode, String body) {
        return start((exchange, server) -> respond(exchange, statusCode, body));
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

    void awaitRequest() throws InterruptedException {
        requested.await();
    }

    URI url() {
        return URI.create("http://localhost:" + server.getAddress().getPort());
    }

    int requestCount() {
        return requestCount.get();
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
