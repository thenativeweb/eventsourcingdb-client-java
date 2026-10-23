package io.thenativeweb.eventsourcingdb;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.sun.net.httpserver.HttpExchange;
import io.thenativeweb.eventsourcingdb.Operations.Operation;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;

// The first request of a client asks the server whether it offers the v2 API,
// by pinging the v2 API. The client keeps a definitive answer, and asks again
// after any other, and once the server answers a request through the v2 API
// with 404 or 405.
@Timeout(10)
class ProbeTest {
    private static final Duration STALL_TIMEOUT = Duration.ofMillis(300);

    private static final String V1_PING_RECEIVED = "{\"type\":\"io.eventsourcingdb.api.ping-received\"}";

    private static final String V1_API_TOKEN_VERIFIED = "{\"type\":\"io.eventsourcingdb.api.api-token-verified\"}";

    // A server that answers each probe with the next of the given answers,
    // and every other request as EventSourcingDB 1.2.0 answers a ping or the
    // verification of an API token, unless it is given an answer for them.
    private static final class ProbedServer implements AutoCloseable {
        private final FakeServer server;
        private final AtomicInteger probeCount = new AtomicInteger();

        @FunctionalInterface
        interface Answer {
            void answer(HttpExchange exchange, FakeServer server) throws IOException, InterruptedException;
        }

        ProbedServer(Answer... answers) {
            this(ProbedServer::answerAsV1, List.of(answers));
        }

        ProbedServer(Answer others, List<Answer> answers) {
            server = FakeServer.start((exchange, fakeServer) -> {
                if (exchange.getRequestURI().getPath().equals(FakeServer.PROBE_PATH)) {
                    var index = probeCount.getAndIncrement();
                    answers.get(Math.min(index, answers.size() - 1)).answer(exchange, fakeServer);
                    return;
                }

                others.answer(exchange, fakeServer);
            });
        }

        static void answerAsV1(HttpExchange exchange, FakeServer server) throws IOException {
            FakeServer.respond(
                    exchange,
                    200,
                    exchange.getRequestURI().getPath().equals("/api/v1/verify-api-token")
                            ? V1_API_TOKEN_VERIFIED
                            : V1_PING_RECEIVED);
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

    // Answers with the given status as another server does, e.g. a proxy in
    // front of the database, which sends a Server header of its own.
    private static ProbedServer.Answer fromAnotherServer(int status) {
        return (exchange, server) -> {
            exchange.getResponseHeaders().set("Server", "nginx");
            exchange.sendResponseHeaders(status, -1);
        };
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
        try (var server = new ProbedServer(fromAnotherServer(status))) {
            var client = server.client();

            assertDoesNotThrow(client::ping);
            assertEquals(List.of(FakeServer.PROBE_PATH, "/api/v1/ping"), server.paths());
        }
    }

    // A proxy answers a path it does not pass on with a page of its own.
    @ParameterizedTest
    @ValueSource(ints = {404, 405})
    void usesTheV1ApiIfAnotherServerAnswersThePingWithAPage(int status) {
        try (var server = new ProbedServer((exchange, fakeServer) -> {
            var page = "<html><body><h1>" + status + "</h1><hr><center>nginx</center></body></html>";
            exchange.getResponseHeaders().set("Server", "nginx");
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(status, page.length());
            exchange.getResponseBody().write(page.getBytes(UTF_8));
        })) {
            var client = server.client();

            assertDoesNotThrow(client::ping);
            assertEquals(List.of(FakeServer.PROBE_PATH, "/api/v1/ping"), server.paths());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 500})
    void failsTheRequestIfAnotherServerAnswersTheProbeOtherwise(int status) {
        try (var server = new ProbedServer(fromAnotherServer(status), V1)) {
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
    // wait for the probe. A thread that ended instead never waited for it, and
    // would leave the loop spinning, which the timeout of the test can not
    // interrupt, so that fails the test at once.
    private static void awaitWaiting(List<Thread> threads) {
        while (threads.stream().anyMatch(thread -> thread.getState() != Thread.State.WAITING)) {
            if (threads.stream().anyMatch(thread -> thread.getState() == Thread.State.TERMINATED)) {
                fail("a request ended without waiting for the probe");
            }
            Thread.onSpinWait();
        }
    }

    // Runs the request in a thread of its own, which puts what the request
    // threw into the outcomes under the given key, or nothing if it succeeded.
    private static <K> Thread start(K key, Runnable request, Map<K, Optional<Throwable>> outcomes) {
        return Thread.ofPlatform().start(() -> {
            try {
                request.run();
                outcomes.put(key, Optional.empty());
            } catch (RuntimeException ex) {
                outcomes.put(key, Optional.of(ex));
            }
        });
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
            threads.add(start(index, requests.get(index), outcomes));
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

    // Answers every path of the v2 API with the given answer, and the v1 API
    // as EventSourcingDB 1.2.0 does.
    private static ProbedServer.Answer withoutTheV2Api(ProbedServer.Answer v2Answer) {
        return (exchange, server) -> {
            if (exchange.getRequestURI().getPath().startsWith("/api/v2/")) {
                v2Answer.answer(exchange, server);
                return;
            }

            ProbedServer.answerAsV1(exchange, server);
        };
    }

    // A server that was rolled back to a version without the v2 API answers
    // every path of the v2 API with the given status, as EventSourcingDB
    // 1.2.0 does, and the v1 API as it did before.
    private static ProbedServer.Answer rolledBack(int status) {
        return withoutTheV2Api(status(status));
    }

    // The ping of the v2 API goes to the path of the probe, which the server
    // answers as a probe, so only the other requests are made here.
    static Stream<Arguments> everyAuthenticatedRequestWith404Or405() {
        return Stream.of(404, 405)
                .flatMap(status ->
                        Operations.authenticated().stream().map(operation -> Arguments.of(status, operation)));
    }

    // A server that offered the v2 API when the client asked, and was rolled
    // back to a version without it afterwards, answers the next request
    // through the v2 API with 404 or 405. That request fails as any other
    // that the server refuses, a write with an unknown outcome, and the next
    // request asks again which API the server offers.
    @ParameterizedTest
    @MethodSource("everyAuthenticatedRequestWith404Or405")
    void failsTheRequestAndAsksAgainIfTheServerNoLongerOffersTheV2Api(int status, Operation operation) {
        try (var server = new ProbedServer(rolledBack(status), List.of(V2, V1))) {
            var client = server.client();

            var exception = Operations.assertFailsAfterSending(DbApiException.class, operation, client);
            assertDoesNotThrow(client::verifyApiToken);

            assertEquals(status, exception.status());
            assertEquals(2, server.probeCount());
            assertEquals("/api/v1/verify-api-token", server.paths().getLast());
        }
    }

    // A proxy in front of the database that passes on the v1 API only, e.g.
    // one set up again along with a rollback, answers the paths of the v2 API
    // itself, without the Server header of EventSourcingDB. The request fails
    // for the missing header as before, a write with an unknown outcome, and
    // the next request asks again, which the proxy answers itself, too.
    @ParameterizedTest
    @MethodSource("everyAuthenticatedRequestWith404Or405")
    void failsTheRequestAndAsksAgainIfAProxyNoLongerPassesOnTheV2Api(int status, Operation operation) {
        try (var server =
                new ProbedServer(withoutTheV2Api(fromAnotherServer(status)), List.of(V2, fromAnotherServer(status)))) {
            var client = server.client();

            Operations.assertFailsAfterSending(InvalidServerHeaderException.class, operation, client);
            assertDoesNotThrow(client::verifyApiToken);

            assertEquals(2, server.probeCount());
            assertEquals("/api/v1/verify-api-token", server.paths().getLast());
        }
    }

    // An error that the v2 API reports in its answer has the status code 200,
    // and tells nothing about the API, so the client keeps the v2 API.
    @Test
    void keepsTheV2ApiIfTheServerReportsAnErrorInItsAnswer() {
        var error = ApiVersion.V2.item(
                "error",
                "{\"code\":\"" + ErrorCode.EVENT_TYPE_UNKNOWN + "\",\"description\":\"event type is unknown\"}");

        try (var server =
                new ProbedServer((exchange, fakeServer) -> FakeServer.respond(exchange, 200, error), List.of(V2, V1))) {
            var client = server.client();

            var exception = assertThrows(DbApiException.class, () -> client.readEventType("io.x.y"));
            assertThrows(DbApiException.class, () -> client.readEventType("io.x.y"));

            assertEquals(200, exception.status());
            assertEquals(
                    List.of(FakeServer.PROBE_PATH, "/api/v2/data/read-event-type", "/api/v2/data/read-event-type"),
                    server.paths());
        }
    }

    // Through the v1 API, a 404 may be an error of its own, e.g. an event type
    // that does not exist, so the client keeps the v1 API.
    @Test
    void keepsTheV1ApiIfTheServerAnswersARequestWith404() {
        try (var server = new ProbedServer(
                (exchange, fakeServer) -> FakeServer.respond(exchange, 404, "event type 'io.x.y' not found"),
                List.of(V1, V2))) {
            var client = server.client();

            var exception = assertThrows(DbApiException.class, () -> client.readEventType("io.x.y"));
            assertThrows(DbApiException.class, () -> client.readEventType("io.x.y"));

            assertEquals(404, exception.status());
            assertEquals(
                    List.of(FakeServer.PROBE_PATH, "/api/v1/read-event-type", "/api/v1/read-event-type"),
                    server.paths());
        }
    }

    // A gate holds an answer of the server until the test opens it, and tells
    // the test once an answer waits at it.
    private record Gate(CountDownLatch reached, CountDownLatch opened) {
        Gate() {
            this(new CountDownLatch(1), new CountDownLatch(1));
        }

        void pass() throws InterruptedException {
            reached.countDown();
            opened.await();
        }

        void awaitReached() throws InterruptedException {
            reached.await();
        }

        void open() {
            opened.countDown();
        }
    }

    // Requests through the v2 API that were sent before the server was rolled
    // back may be answered with 404 only after another one made the client ask
    // again, while the new probe runs, or once it has its answer. Neither
    // disturbs the probe: the requests that wait for it, and those that start
    // to wait afterwards, share it, and its answer is kept.
    @Test
    void aLate404LeavesTheNewProbeAndItsAnswerAlone() throws InterruptedException {
        var duringProbe = new Gate();
        var afterProbe = new Gate();
        var newProbe = new Gate();
        var heldRequests = List.of(duringProbe, afterProbe);
        var v2RequestCount = new AtomicInteger();

        ProbedServer.Answer others = (exchange, fakeServer) -> {
            if (exchange.getRequestURI().getPath().startsWith("/api/v2/")) {
                var index = v2RequestCount.getAndIncrement();
                if (index < heldRequests.size()) {
                    heldRequests.get(index).pass();
                }
            }

            rolledBack(404).answer(exchange, fakeServer);
        };
        ProbedServer.Answer heldProbe = (exchange, fakeServer) -> {
            newProbe.pass();
            V1.answer(exchange, fakeServer);
        };

        try (var server = new ProbedServer(others, List.of(V2, heldProbe, V1))) {
            // The client's own stall timeout leaves the gates all the time
            // they need.
            var client = new Client(server.server.url(), "secret");
            var outcomes = new ConcurrentHashMap<String, Optional<Throwable>>();

            var lateDuringProbe = start("late during probe", client::verifyApiToken, outcomes);
            duringProbe.awaitReached();
            var lateAfterProbe = start("late after probe", client::verifyApiToken, outcomes);
            afterProbe.awaitReached();

            assertThrows(DbApiException.class, client::verifyApiToken);
            assertNull(client.chosenTransport());

            var waiting = start("waiting", client::verifyApiToken, outcomes);
            newProbe.awaitReached();
            duringProbe.open();
            lateDuringProbe.join();

            var joining = start("joining", client::verifyApiToken, outcomes);
            while (joining.isAlive() && joining.getState() != Thread.State.WAITING) {
                Thread.onSpinWait();
            }
            newProbe.open();
            waiting.join();
            joining.join();

            afterProbe.open();
            lateAfterProbe.join();
            assertDoesNotThrow(client::verifyApiToken);

            for (var late : List.of("late during probe", "late after probe")) {
                var exception = assertInstanceOf(
                        DbApiException.class, outcomes.get(late).orElseThrow());
                assertEquals(404, exception.status());
            }
            assertEquals(Optional.empty(), outcomes.get("waiting"));
            assertEquals(Optional.empty(), outcomes.get("joining"));
            assertEquals(2, server.probeCount());
            assertEquals("/api/v1/verify-api-token", server.paths().getLast());
        }
    }

    // A request through the v2 API that was sent before the server was rolled
    // back may be answered with 404 only once a new probe found out the v2 API
    // again, e.g. because the server was rolled forward meanwhile. That 404
    // belongs to the answer that the request was sent under, so the answer of
    // the new probe is kept.
    @Test
    void aLate404LeavesTheV2ApiThatANewProbeFoundOutAlone() throws InterruptedException {
        var late = new Gate();
        var v2RequestCount = new AtomicInteger();
        var tokenVerified = ApiVersion.V2.item("event", "{\"type\":\"io.eventsourcingdb.api.token-verified\"}");

        ProbedServer.Answer others = (exchange, fakeServer) -> {
            var index = v2RequestCount.getAndIncrement();
            if (index == 0) {
                late.pass();
            }
            if (index < 2) {
                status(404).answer(exchange, fakeServer);
                return;
            }

            FakeServer.respond(exchange, 200, tokenVerified);
        };

        try (var server = new ProbedServer(others, List.of(V2))) {
            // The client's own stall timeout leaves the gate all the time it
            // needs.
            var client = new Client(server.server.url(), "secret");
            var outcomes = new ConcurrentHashMap<String, Optional<Throwable>>();

            var lateRequest = start("late", client::verifyApiToken, outcomes);
            late.awaitReached();
            assertThrows(DbApiException.class, client::verifyApiToken);
            assertDoesNotThrow(client::verifyApiToken);

            late.open();
            lateRequest.join();
            assertDoesNotThrow(client::verifyApiToken);

            var exception =
                    assertInstanceOf(DbApiException.class, outcomes.get("late").orElseThrow());
            assertEquals(404, exception.status());
            assertEquals(2, server.probeCount());
        }
    }

    private static final List<EventCandidate> EVENTS =
            List.of(new EventCandidate("https://x", "/a", "io.x.y", Map.of()));

    private static final List<Precondition> RECURSIVE_PRECONDITIONS =
            List.of(new IsSubjectPristinePrecondition("/a", new PreconditionOptions(true)));

    private static final String WRITE_PATH_OF_THE_V2_API = "/api/v2/data/write-events";

    // A server that was upgraded to a version with the v2 API answers a write
    // and the verification of an API token through the v2 API, and the v1 API
    // as it did before.
    private static final ProbedServer.Answer UPGRADED = (exchange, server) -> {
        switch (exchange.getRequestURI().getPath()) {
            case WRITE_PATH_OF_THE_V2_API ->
                FakeServer.respond(exchange, 200, Lines.event(ApiVersion.V2) + "\n" + ApiVersion.V2.end() + "\n");
            case "/api/v2/system/verify-token" ->
                FakeServer.respond(
                        exchange,
                        200,
                        ApiVersion.V2.item("event", "{\"type\":\"io.eventsourcingdb.api.token-verified\"}"));
            default -> ProbedServer.answerAsV1(exchange, server);
        }
    };

    private static long writeCountOfTheV2Api(ProbedServer server) {
        return server.paths().stream()
                .filter(path -> path.equals(WRITE_PATH_OF_THE_V2_API))
                .count();
    }

    // A server that did not offer the v2 API when the client asked, and was
    // upgraded to a version with it afterwards, can check a recursive
    // precondition. So before the client refuses a write with one, it asks
    // again, sends the write through the v2 API if the server offers it now,
    // and keeps the v2 API for the requests that follow.
    @Test
    void asksAgainBeforeRefusingARecursivePreconditionAndWritesThroughTheV2ApiIfTheServerOffersItNow() {
        try (var server = new ProbedServer(UPGRADED, List.of(V1, V2))) {
            var client = server.client();
            client.ping();

            var events = client.writeEvents(EVENTS, RECURSIVE_PRECONDITIONS);
            client.verifyApiToken();

            assertEquals(1, events.size());
            assertEquals(
                    List.of(
                            FakeServer.PROBE_PATH,
                            "/api/v1/ping",
                            FakeServer.PROBE_PATH,
                            WRITE_PATH_OF_THE_V2_API,
                            "/api/v2/system/verify-token"),
                    server.paths());

            var write = server.server.requests().stream()
                    .filter(request -> request.path().equals(WRITE_PATH_OF_THE_V2_API))
                    .findFirst()
                    .orElseThrow();
            assertTrue(Json.MAPPER
                    .readTree(write.body())
                    .path("preconditions")
                    .path(0)
                    .path("payload")
                    .path("options")
                    .path("recursive")
                    .asBoolean());
        }
    }

    @Test
    void refusesARecursivePreconditionIfTheServerStillDoesNotOfferTheV2ApiWhenAskedAgain() {
        try (var server = new ProbedServer(V1)) {
            var client = server.client();
            client.ping();

            assertThrows(IncompatibleServerException.class, () -> client.writeEvents(EVENTS, RECURSIVE_PRECONDITIONS));

            assertEquals(List.of(FakeServer.PROBE_PATH, "/api/v1/ping", FakeServer.PROBE_PATH), server.paths());
        }
    }

    // The first request of a client has just asked which API the server
    // offers, so a write with a recursive precondition does not ask twice.
    @Test
    void doesNotAskAgainForARecursivePreconditionOnTheFirstRequest() {
        try (var server = new ProbedServer(V1)) {
            var client = server.client();

            assertThrows(IncompatibleServerException.class, () -> client.writeEvents(EVENTS, RECURSIVE_PRECONDITIONS));

            assertEquals(List.of(FakeServer.PROBE_PATH), server.paths());
        }
    }

    // Writes with a recursive precondition that are made at the same time
    // share the probe that asks again, as other requests share a probe, and
    // each of them is sent once at most.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sharesTheProbeThatAsksAgainBetweenWritesWithARecursivePrecondition(boolean isUpgraded)
            throws InterruptedException {
        var release = new CountDownLatch(1);
        var answerAfterTheUpgrade = isUpgraded ? V2 : V1;

        try (var server = new ProbedServer(UPGRADED, List.of(V1, (exchange, fakeServer) -> {
            release.await();
            answerAfterTheUpgrade.answer(exchange, fakeServer);
        }))) {
            var client = server.client();
            client.ping();

            Runnable write = () -> client.writeEvents(EVENTS, RECURSIVE_PRECONDITIONS);
            var outcomes = runWhileTheProbeWaits(release, threads -> {}, List.of(write, write));

            assertEquals(2, server.probeCount());
            if (isUpgraded) {
                assertEquals(Map.of(0, Optional.empty(), 1, Optional.empty()), outcomes);
                assertEquals(2, writeCountOfTheV2Api(server));
            } else {
                assertInstanceOf(
                        IncompatibleServerException.class, outcomes.get(0).orElseThrow());
                assertInstanceOf(
                        IncompatibleServerException.class, outcomes.get(1).orElseThrow());
                assertEquals(0, writeCountOfTheV2Api(server));
            }
        }
    }
}
