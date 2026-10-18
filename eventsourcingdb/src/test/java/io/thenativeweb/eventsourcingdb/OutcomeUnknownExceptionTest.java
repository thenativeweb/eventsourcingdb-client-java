package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.thenativeweb.eventsourcingdb.Operations.Operation;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import javax.net.ssl.SSLHandshakeException;
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

    static List<Operation> writes() {
        return Operations.writes();
    }

    static Stream<Arguments> writesWithV1Refusals() {
        return Operations.writes().stream()
                .flatMap(operation -> Stream.of(400, 401, 404, 409, 413, 429, 503, 507)
                        .map(status -> Arguments.of(operation, status)));
    }

    static Stream<Arguments> writesWithV1StatusesThatLeaveTheOutcomeOpen() {
        return Operations.writes().stream()
                .flatMap(operation -> Stream.of(500, 502, 504).map(status -> Arguments.of(operation, status)));
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

    @ParameterizedTest
    @MethodSource("writes")
    void reportsAWriteThatCouldNotConnectAsNotStored(Operation operation) {
        var client = new Client(FakeServer.unusedUrl(), "secret");

        var exception = assertThrows(
                EventSourcingDbException.class, () -> operation.call().accept(client));

        assertEquals("failed to " + operation.action(), exception.getMessage());
        assertInstanceOf(ConnectException.class, exception.getCause());
    }

    @ParameterizedTest
    @MethodSource("writesWithV1Refusals")
    void reportsAWriteThatTheV1ApiRefusesAsNotStored(Operation operation, int status) {
        try (var server = FakeServer.respondingWith(status, "refused")) {
            var exception =
                    assertThrows(DbApiException.class, () -> operation.call().accept(clientWith(server)));

            assertEquals(status, exception.status());
        }
    }

    @ParameterizedTest
    @MethodSource("writesWithV1StatusesThatLeaveTheOutcomeOpen")
    void reportsAWriteThatTheV1ApiAnswersWithAnotherStatusAsUnknown(Operation operation, int status) {
        try (var server = FakeServer.respondingWith(status, "failed")) {
            var cause = assertOutcomeUnknown(DbApiException.class, operation, clientWith(server));

            assertEquals(status, cause.status());
        }
    }

    @ParameterizedTest
    @MethodSource("writes")
    void reportsAWriteThatStallsAsUnknown(Operation operation) {
        try (var server = FakeServer.start((exchange, fakeServer) -> fakeServer.hang())) {
            assertOutcomeUnknown(ConnectionStalledException.class, operation, clientWith(server));
        }
    }

    @ParameterizedTest
    @MethodSource("writes")
    void reportsAWriteWhoseConnectionBreaksAsUnknown(Operation operation) {
        try (var server = FakeServer.start((exchange, fakeServer) -> exchange.close())) {
            var cause = assertOutcomeUnknown(EventSourcingDbException.class, operation, clientWith(server));

            assertInstanceOf(IOException.class, cause.getCause());
        }
    }

    @ParameterizedTest
    @MethodSource("writes")
    void reportsAWriteThatIsNotAnsweredByAnEventSourcingDbAsUnknown(Operation operation) {
        try (var server = FakeServer.start((exchange, fakeServer) -> exchange.sendResponseHeaders(200, -1))) {
            assertOutcomeUnknown(InvalidServerHeaderException.class, operation, clientWith(server));
        }
    }

    @Test
    void reportsAWriteWhoseAnswerIsMalformedAsUnknown() {
        try (var server = FakeServer.respondingWith(200, "[{")) {
            var client = clientWith(server);

            var exception = assertThrows(OutcomeUnknownException.class, () -> client.writeEvents(List.of()));

            assertInstanceOf(JacksonException.class, exception.getCause());
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
    }

    @Test
    void reportsAnyOtherFailureAsUnknown() {
        var failure = new EventSourcingDbException("failed to write events", new IOException("connection reset"));

        var outcome = Outcomes.ofWrite("write events", failure, Outcomes.V1_REFUSALS);

        assertInstanceOf(OutcomeUnknownException.class, outcome);
        assertSame(failure, outcome.getCause());
    }
}
