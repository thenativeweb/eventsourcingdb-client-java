package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;

// The first request of a client asks the server whether it offers the v2 API,
// by pinging the v2 API. The client keeps a definitive answer, and asks again
// after any other.
@Timeout(10)
class ProbeTest {
    private static final Duration STALL_TIMEOUT = Duration.ofMillis(300);

    private static final String V1_PING_RECEIVED = "{\"type\":\"io.eventsourcingdb.api.ping-received\"}";

    private static final String V1_API_TOKEN_VERIFIED = "{\"type\":\"io.eventsourcingdb.api.api-token-verified\"}";

    // A server that answers each probe with the next of the given answers,
    // and every other request as EventSourcingDB 1.2.0 answers a ping or the
    // verification of an API token.
    private static final class ProbedServer implements AutoCloseable {
        private final FakeServer server;
        private final AtomicInteger probeCount = new AtomicInteger();

        @FunctionalInterface
        interface Answer {
            void answer(HttpExchange exchange, FakeServer server) throws IOException, InterruptedException;
        }

        ProbedServer(Answer... answers) {
            server = FakeServer.start((exchange, fakeServer) -> {
                if (exchange.getRequestURI().getPath().equals(FakeServer.PROBE_PATH)) {
                    var index = probeCount.getAndIncrement();
                    answers[Math.min(index, answers.length - 1)].answer(exchange, fakeServer);
                    return;
                }

                FakeServer.respond(
                        exchange,
                        200,
                        exchange.getRequestURI().getPath().equals("/api/v1/verify-api-token")
                                ? V1_API_TOKEN_VERIFIED
                                : V1_PING_RECEIVED);
            });
        }

        Client client() {
            return new Client(server.url(), "secret", new ClientOptions().withStallTimeout(STALL_TIMEOUT));
        }

        int probeCount() {
            return probeCount.get();
        }

        List<String> paths() {
            return server.requests().stream().map(FakeServer.Request::path).toList();
        }

        @Override
        public void close() {
            server.close();
        }
    }

    private static ProbedServer.Answer status(int status) {
        return (exchange, server) -> FakeServer.respond(exchange, status, "status " + status);
    }

    private static final ProbedServer.Answer V1 = status(404);

    private static final ProbedServer.Answer V2 =
            (exchange, server) -> FakeServer.respond(exchange, 200, ApiVersion.PING_RECEIVED);

    @ParameterizedTest
    @ValueSource(ints = {404, 405})
    void usesTheV1ApiIfTheServerDoesNotKnowThePathOfThePing(int status) {
        try (var server = new ProbedServer(status(status))) {
            server.client().ping();

            assertEquals(List.of(FakeServer.PROBE_PATH, "/api/v1/ping"), server.paths());
        }
    }

    @Test
    void usesTheV2ApiIfTheServerAnswersThePing() {
        try (var server = new ProbedServer(V2)) {
            var client = server.client();

            try (var subjects = client.readSubjects("/", new ReadSubjectsOptions(true))) {
                assertThrows(EventSourcingDbException.class, subjects::toList);
            }

            assertEquals(List.of(FakeServer.PROBE_PATH, "/api/v2/data/read-subjects"), server.paths());
        }
    }

    @Test
    void asksOnlyOnceIfTheAnswerIsDefinitive() {
        try (var server = new ProbedServer(V1)) {
            var client = server.client();

            client.ping();
            client.ping();

            assertEquals(1, server.probeCount());
            assertEquals(List.of(FakeServer.PROBE_PATH, "/api/v1/ping", "/api/v1/ping"), server.paths());
        }
    }

    @Test
    void sendsNoRequestWhenTheClientIsCreated() {
        try (var server = new ProbedServer(V1)) {
            server.client();

            assertEquals(List.of(), server.paths());
        }
    }

    @Test
    void failsTheRequestAndAsksAgainIfTheServerFails() {
        try (var server = new ProbedServer(status(500), V1)) {
            var client = server.client();

            var exception = assertThrows(DbApiException.class, client::ping);
            assertDoesNotThrow(client::ping);

            assertEquals(
                    "failed to ping, got HTTP status code '500', expected '200': status 500", exception.getMessage());
            assertEquals(500, exception.status());
            assertEquals("", exception.code());
            assertEquals(2, server.probeCount());
        }
    }

    @Test
    void reportsHowLongToWaitIfTheProbeIsOneRequestTooMany() {
        try (var server = new ProbedServer((exchange, fakeServer) -> {
            exchange.getResponseHeaders().set("Retry-After", "3");
            FakeServer.respond(exchange, 429, "too many requests");
        })) {
            var exception = assertThrows(DbApiException.class, server.client()::ping);

            assertEquals(429, exception.status());
            assertEquals(Optional.of(Duration.ofSeconds(3)), exception.retryAfter());
        }
    }

    @Test
    void failsTheRequestAndAsksAgainIfTheProbeStalls() {
        var hasStalled = new AtomicInteger();

        try (var server = new ProbedServer(
                (exchange, fakeServer) -> {
                    hasStalled.incrementAndGet();
                    fakeServer.hang();
                },
                V1)) {
            var client = server.client();

            var exception = assertThrows(ConnectionStalledException.class, client::ping);
            assertDoesNotThrow(client::ping);

            assertEquals(
                    "failed to ping, nothing arrived from the server for 300 milliseconds", exception.getMessage());
            assertInstanceOf(HttpTimeoutException.class, exception.getCause());
            assertEquals(2, server.probeCount());
        }
    }

    @Test
    void failsTheRequestIfTheServerCanNotBeReached() {
        var client = new Client(FakeServer.unusedUrl(), "secret");

        var exception = assertThrows(EventSourcingDbException.class, client::verifyApiToken);

        assertEquals("failed to verify API token", exception.getMessage());
        assertInstanceOf(ConnectException.class, exception.getCause());
    }

    // A proxy that passes on the v1 API only answers the probe itself. The
    // requests to the v1 API afterwards check that the server is an
    // EventSourcingDB, so the probe does not need to.
    @ParameterizedTest
    @ValueSource(ints = {404, 405})
    void usesTheV1ApiIfAnotherServerDoesNotKnowThePathOfThePing(int status) {
        try (var server = new ProbedServer((exchange, fakeServer) -> {
            exchange.getResponseHeaders().set("Server", "nginx");
            exchange.sendResponseHeaders(status, -1);
        })) {
            var client = server.client();

            assertDoesNotThrow(client::ping);
            assertEquals(List.of(FakeServer.PROBE_PATH, "/api/v1/ping"), server.paths());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 500})
    void failsTheRequestIfAnotherServerAnswersTheProbeOtherwise(int status) {
        try (var server = new ProbedServer(
                (exchange, fakeServer) -> {
                    exchange.getResponseHeaders().set("Server", "nginx");
                    exchange.sendResponseHeaders(status, -1);
                },
                V1)) {
            var client = server.client();

            assertThrows(InvalidServerHeaderException.class, client::ping);
            assertDoesNotThrow(client::ping);
            assertEquals(2, server.probeCount());
        }
    }

    @Test
    void failsTheRequestIfTheServerAnswersThePingWithSomethingElse() {
        try (var server = new ProbedServer(
                (exchange, fakeServer) -> FakeServer.respond(exchange, 200, V1_PING_RECEIVED),
                (exchange, fakeServer) -> FakeServer.respond(exchange, 200, "<html>"),
                V1)) {
            var client = server.client();

            var exception = assertThrows(EventSourcingDbException.class, () -> {
                try (var eventTypes = client.readEventTypes()) {
                    eventTypes.toList();
                }
            });
            var malformed = assertThrows(EventSourcingDbException.class, client::ping);
            assertDoesNotThrow(client::ping);

            assertEquals(
                    "failed to read event types, the server answered /api/v2/system/ping unexpectedly",
                    exception.getMessage());
            assertNull(exception.getCause());
            assertEquals(
                    "failed to ping, the server answered /api/v2/system/ping unexpectedly", malformed.getMessage());
            assertInstanceOf(JacksonException.class, malformed.getCause());
        }
    }

    // A write whose probe fails was never sent, so it was certainly not
    // stored.
    @Test
    void reportsAWriteWhoseProbeFailsAsNotStored() {
        try (var server = new ProbedServer(status(500))) {
            var client = server.client();

            var exception = assertThrows(DbApiException.class, () -> client.writeEvents(List.of()));

            assertEquals("write events", exception.action());
        }
    }

    // Waits until each of the threads waits, which they do only once they
    // wait for the probe.
    private static void awaitWaiting(List<Thread> threads) {
        while (threads.stream().anyMatch(thread -> thread.getState() != Thread.State.WAITING)) {
            Thread.onSpinWait();
        }
    }

    // Runs each of the requests in a thread of its own, releases the probe
    // once all of them wait for it, and returns what each of them threw, or
    // null for those that succeeded.
    private static Map<Integer, Optional<Throwable>> runWhileTheProbeWaits(
            CountDownLatch release, Consumer<List<Thread>> beforeRelease, List<Runnable> requests)
            throws InterruptedException {
        var outcomes = new ConcurrentHashMap<Integer, Optional<Throwable>>();
        var threads = new ArrayList<Thread>();

        for (var index = 0; index < requests.size(); index++) {
            var request = requests.get(index);
            var requestIndex = index;
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    request.run();
                    outcomes.put(requestIndex, Optional.empty());
                } catch (RuntimeException ex) {
                    outcomes.put(requestIndex, Optional.of(ex));
                }
            }));
        }

        awaitWaiting(threads);
        beforeRelease.accept(threads);
        release.countDown();
        for (var thread : threads) {
            thread.join();
        }

        return outcomes;
    }

    @Test
    void sharesOneProbeBetweenRequestsThatAreMadeAtTheSameTime() throws InterruptedException {
        var release = new CountDownLatch(1);

        try (var server = new ProbedServer((exchange, fakeServer) -> {
            release.await();
            FakeServer.respond(exchange, 404, "404 page not found");
        })) {
            var client = server.client();

            var outcomes = runWhileTheProbeWaits(
                    release, threads -> {}, List.of(client::ping, client::ping, client::verifyApiToken));

            assertEquals(Map.of(0, Optional.empty(), 1, Optional.empty(), 2, Optional.empty()), outcomes);
            assertEquals(1, server.probeCount());
        }
    }

    @Test
    void failsEachRequestThatSharesAFailedProbeWithItsOwnAction() throws InterruptedException {
        var release = new CountDownLatch(1);

        try (var server = new ProbedServer(
                (exchange, fakeServer) -> {
                    release.await();
                    FakeServer.respond(exchange, 503, "unavailable");
                },
                V1)) {
            var client = server.client();

            var outcomes = runWhileTheProbeWaits(release, threads -> {}, List.of(client::ping, client::verifyApiToken));

            assertEquals(
                    "failed to ping, got HTTP status code '503', expected '200': unavailable",
                    outcomes.get(0).orElseThrow().getMessage());
            assertEquals(
                    "failed to verify API token, got HTTP status code '503', expected '200': unavailable",
                    outcomes.get(1).orElseThrow().getMessage());
            assertEquals(1, server.probeCount());
        }
    }

    // A request that is aborted while it waits for the probe ends its own
    // wait only, since other requests may wait for the same probe.
    @Test
    void anInterruptWhileWaitingForTheProbeEndsThatRequestOnly() throws InterruptedException {
        var release = new CountDownLatch(1);

        try (var server = new ProbedServer((exchange, fakeServer) -> {
            release.await();
            FakeServer.respond(exchange, 404, "404 page not found");
        })) {
            var client = server.client();

            var outcomes = runWhileTheProbeWaits(
                    release,
                    threads -> {
                        threads.getFirst().interrupt();
                        while (threads.getFirst().isAlive()) {
                            Thread.onSpinWait();
                        }
                    },
                    List.of(() -> client.writeEvents(List.of()), client::ping));

            var cancellation = assertInstanceOf(
                    CancellationException.class, outcomes.get(0).orElseThrow());
            assertEquals("failed to write events, the request was canceled", cancellation.getMessage());
            assertEquals(Optional.empty(), outcomes.get(1));
            assertEquals(1, server.probeCount());
        }
    }

    @Test
    void aCloseEndsTheRequestsThatWaitForTheProbe() throws InterruptedException {
        try (var server = new ProbedServer((exchange, fakeServer) -> fakeServer.hang())) {
            var client = new Client(server.server.url(), "secret");
            var release = new CountDownLatch(1);

            var outcomes = runWhileTheProbeWaits(release, threads -> client.close(), List.of(client::ping));

            assertInstanceOf(CancellationException.class, outcomes.get(0).orElseThrow());
        }
    }

    @Test
    void aCloseEndsAStreamThatWaitsForTheProbe() throws InterruptedException {
        var probed = new CountDownLatch(1);

        try (var server = new ProbedServer((exchange, fakeServer) -> {
            probed.countDown();
            fakeServer.hang();
        })) {
            var subjects = new Client(server.server.url(), "secret").readSubjects("/", new ReadSubjectsOptions(true));
            var consumer = Thread.currentThread();
            var closing = Thread.ofPlatform().start(() -> {
                try {
                    probed.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                awaitWaiting(List.of(consumer));
                subjects.close();
            });

            assertThrows(CancellationException.class, subjects::toList);
            closing.join();
        }
    }
}
