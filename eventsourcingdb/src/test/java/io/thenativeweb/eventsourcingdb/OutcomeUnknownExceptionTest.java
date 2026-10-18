package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.thenativeweb.eventsourcingdb.Operations.Operation;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import javax.net.ssl.SSLHandshakeException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.JacksonException;

// A write that fails is certainly not stored only if the client could not
// connect at all, or if the server refused it. Every other failure may come
// after the server stored it, and ends with an OutcomeUnknownException.
@Timeout(10)
class OutcomeUnknownExceptionTest {
    private static final Duration STALL_TIMEOUT = Duration.ofMillis(300);

    static List<Arguments> writes() {
        return Operations.onEveryApi(Operations.writes());
    }

    private static Stream<Arguments> writesWith(ApiVersion api, int... statuses) {
        return Operations.writes().stream()
                .flatMap(operation -> Arrays.stream(statuses).mapToObj(status -> Arguments.of(api, operation, status)));
    }

    static Stream<Arguments> refusals() {
        return Stream.concat(
                writesWith(ApiVersion.V1, 400, 401, 404, 409, 413, 429, 503, 507),
                writesWith(ApiVersion.V2, 400, 401, 408, 413, 429, 503));
    }

    static Stream<Arguments> statusesThatLeaveTheOutcomeOpen() {
        return Stream.concat(
                writesWith(ApiVersion.V1, 302, 500, 502, 504),
                writesWith(ApiVersion.V2, 302, 404, 405, 409, 500, 502, 504, 507));
    }

    static Stream<Arguments> v2Writes() {
        return Operations.writes().stream().map(Arguments::of);
    }

    private static Client clientWith(FakeServer server) {
        return new Client(server.url(), "secret", new ClientOptions().withStallTimeout(STALL_TIMEOUT));
    }

    private static <T extends Throwable> T assertOutcomeUnknown(
            Class<T> causeType, Operation operation, Client client) {
        var exception = assertThrows(
                OutcomeUnknownException.class, () -> operation.call().accept(client));

        assertEquals("failed to " + operation.action() + ", the outcome is unknown", exception.getMessage());
        return assertInstanceOf(causeType, exception.getCause());
    }

    // Answers the probe and the ping, and closes every connection, so that
    // the client keeps none open, and has to connect for the write anew.
    private static FakeServer answeringThePingAndClosingConnections(ApiVersion api) {
        return FakeServer.start((exchange, fakeServer) -> {
            exchange.getResponseHeaders().set("Connection", "close");

            var path = exchange.getRequestURI().getPath();
            if (api == ApiVersion.V1 && path.equals(FakeServer.PROBE_PATH)) {
                FakeServer.respond(exchange, 404, "404 page not found");
                return;
            }
            if (api == ApiVersion.V1) {
                FakeServer.respond(exchange, 200, "{\"type\":\"io.eventsourcingdb.api.ping-received\"}");
                return;
            }
            FakeServer.respond(exchange, 200, ApiVersion.PING_RECEIVED);
        });
    }

    @ParameterizedTest
    @MethodSource("writes")
    void reportsAWriteThatCouldNotConnectAsNotStored(ApiVersion api, Operation operation) {
        var server = answeringThePingAndClosingConnections(api);
        var client = clientWith(server);
        client.ping();
        server.close();

        var exception = assertThrows(
                EventSourcingDbException.class, () -> operation.call().accept(client));

        assertEquals("failed to " + operation.action(), exception.getMessage());
        assertInstanceOf(ConnectException.class, exception.getCause());
    }

    @ParameterizedTest
    @MethodSource("refusals")
    void reportsAWriteThatTheServerRefusesWithAStatusAsNotStored(ApiVersion api, Operation operation, int status) {
        try (var server = FakeServer.respondingWith(api, status, "refused")) {
            var exception =
                    assertThrows(DbApiException.class, () -> operation.call().accept(clientWith(server)));

            assertEquals(status, exception.status());
        }
    }

    @ParameterizedTest
    @MethodSource("statusesThatLeaveTheOutcomeOpen")
    void reportsAWriteThatTheServerAnswersWithAnotherStatusAsUnknown(ApiVersion api, Operation operation, int status) {
        try (var server = FakeServer.respondingWith(api, status, "failed")) {
            var cause = assertOutcomeUnknown(DbApiException.class, operation, clientWith(server));

            assertEquals(status, cause.status());
        }
    }

    // The v2 API reports the errors of a write that it accepted within the
    // stream that answers it, after heartbeats, if the write takes long.
    @ParameterizedTest
    @MethodSource("v2Writes")
    void reportsAWriteThatTheV2ApiRefusesWithAnErrorAsNotStored(Operation operation) {
        var error = ApiVersion.V2.item(
                "error",
                "{\"code\":\"" + ErrorCode.PRECONDITION_VIOLATED + "\",\"description\":\"refused\",\"details\":{}}");

        try (var server = FakeServer.streaming(ApiVersion.V2, Lines.heartbeat(ApiVersion.V2), error)) {
            var exception =
                    assertThrows(DbApiException.class, () -> operation.call().accept(clientWith(server)));

            assertEquals(200, exception.status());
            assertEquals(ErrorCode.PRECONDITION_VIOLATED, exception.code());
            assertEquals("refused", exception.description());
        }
    }

    // store-failed reports that storing failed halfway, so it may have been
    // stored nevertheless.
    @ParameterizedTest
    @MethodSource("v2Writes")
    void reportsAWriteThatTheV2ApiFailedToStoreAsUnknown(Operation operation) {
        try (var server = FakeServer.streaming(ApiVersion.V2, Lines.error(ApiVersion.V2))) {
            var cause = assertOutcomeUnknown(DbApiException.class, operation, clientWith(server));

            assertEquals(ErrorCode.STORE_FAILED, cause.code());
        }
    }

    @ParameterizedTest
    @MethodSource("v2Writes")
    void reportsAWriteWhoseAnswerOfTheV2ApiEndsWithoutItsEndItemAsUnknown(Operation operation) {
        try (var server = FakeServer.start(ApiVersion.V2, (exchange, fakeServer) -> {
            FakeServer.startStream(exchange);
            FakeServer.writeLine(exchange, Lines.heartbeat(ApiVersion.V2));
        })) {
            var cause = assertOutcomeUnknown(EventSourcingDbException.class, operation, clientWith(server));

            assertEquals("failed to " + operation.action() + ", the stream ended unexpectedly", cause.getMessage());
        }
    }

    @ParameterizedTest
    @MethodSource("v2Writes")
    void reportsAWriteWhoseAnswerOfTheV2ApiHoldsAnUnsupportedItemAsUnknown(Operation operation) {
        try (var server = FakeServer.streaming(ApiVersion.V2, Lines.subject(ApiVersion.V2))) {
            var cause = assertOutcomeUnknown(EventSourcingDbException.class, operation, clientWith(server));

            assertEquals("failed to handle unsupported line type: subject", cause.getMessage());
        }
    }

    @ParameterizedTest
    @MethodSource("writes")
    void reportsAWriteThatStallsAsUnknown(ApiVersion api, Operation operation) {
        try (var server = FakeServer.start(api, (exchange, fakeServer) -> fakeServer.hang())) {
            assertOutcomeUnknown(ConnectionStalledException.class, operation, clientWith(server));
        }
    }

    @ParameterizedTest
    @MethodSource("writes")
    void reportsAWriteWhoseConnectionBreaksAsUnknown(ApiVersion api, Operation operation) {
        try (var server = FakeServer.start(api, (exchange, fakeServer) -> exchange.close())) {
            var cause = assertOutcomeUnknown(EventSourcingDbException.class, operation, clientWith(server));

            assertInstanceOf(IOException.class, cause.getCause());
        }
    }

    @ParameterizedTest
    @MethodSource("writes")
    void reportsAWriteThatIsNotAnsweredByAnEventSourcingDbAsUnknown(ApiVersion api, Operation operation) {
        try (var server = FakeServer.start(api, (exchange, fakeServer) -> exchange.sendResponseHeaders(200, -1))) {
            assertOutcomeUnknown(InvalidServerHeaderException.class, operation, clientWith(server));
        }
    }

    @Test
    void reportsAWriteWhoseAnswerOfTheV1ApiIsMalformedAsUnknown() {
        try (var server = FakeServer.respondingWith(ApiVersion.V1, 200, "[{")) {
            var client = clientWith(server);

            var exception = assertThrows(OutcomeUnknownException.class, () -> client.writeEvents(List.of()));

            assertInstanceOf(JacksonException.class, exception.getCause());
        }
    }

    @ParameterizedTest
    @MethodSource("v2Writes")
    void reportsAWriteWhoseAnswerOfTheV2ApiIsMalformedAsUnknown(Operation operation) {
        try (var server = FakeServer.respondingWith(ApiVersion.V2, 200, "{\"type\":")) {
            assertOutcomeUnknown(JacksonException.class, operation, clientWith(server));
        }
    }

    // How far a connection got is up to the JDK, so these failures are
    // checked on their own, as the JDK reports them.
    static Stream<Arguments> failuresBeforeSending() {
        return Stream.of(
                Arguments.of(new ConnectException("connection refused")),
                Arguments.of(new HttpConnectTimeoutException("connect timed out")),
                Arguments.of(new SSLHandshakeException("handshake failed")),
                Arguments.of(new IOException("failed", new ConnectException("connection refused"))));
    }

    @ParameterizedTest
    @MethodSource("failuresBeforeSending")
    void reportsAFailureBeforeSendingAsNotStored(IOException cause) {
        var failure = new EventSourcingDbException("failed to write events", cause);

        assertSame(failure, Outcomes.ofWrite("write events", failure, Outcomes.V1_REFUSALS));
        assertSame(failure, Outcomes.ofWrite("write events", failure, Outcomes.V2_REFUSALS));
    }

    private static WriteRetries writeRetries(@Nullable String systemProperty) {
        return new WriteRetries(Path.of("missing", "net.properties"), name -> systemProperty);
    }

    // If the JDK sends writes again, a refused connect may follow on a write
    // that the server stored already, over a connection that closed before
    // the answer arrived.
    @Test
    void reportsAFailureToConnectAsUnknownIfTheJdkSendsWritesAgain() {
        var failure = new EventSourcingDbException("failed to write events", new ConnectException("refused"));

        var outcome = Outcomes.ofWrite("write events", failure, Outcomes.V2_REFUSALS, writeRetries("true"));

        assertInstanceOf(OutcomeUnknownException.class, outcome);
        assertSame(failure, outcome.getCause());
    }

    @Test
    void reportsAFailureToConnectAsNotStoredIfTheJdkDoesNotSendWritesAgain() {
        var failure = new EventSourcingDbException("failed to write events", new ConnectException("refused"));

        var outcome = Outcomes.ofWrite("write events", failure, Outcomes.V2_REFUSALS, writeRetries(null));

        assertSame(failure, outcome);
    }

    @Test
    void reportsAnyOtherFailureAsUnknown() {
        var failure = new EventSourcingDbException("failed to write events", new IOException("connection reset"));

        var outcome = Outcomes.ofWrite("write events", failure, Outcomes.V2_REFUSALS);

        assertInstanceOf(OutcomeUnknownException.class, outcome);
        assertSame(failure, outcome.getCause());
    }
}
