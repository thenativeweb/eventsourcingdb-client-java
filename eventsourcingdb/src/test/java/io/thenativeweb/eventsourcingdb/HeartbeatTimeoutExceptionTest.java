package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Timeout(10)
class HeartbeatTimeoutExceptionTest {
    private static final Duration TIMEOUT = Duration.ofMillis(300);

    record HeartbeatStream(String name, Function<Client, Stream<?>> open, String item) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<HeartbeatStream> streams() {
        return List.of(
                new HeartbeatStream(
                        "observe events",
                        client -> client.observeEvents("/", new ObserveEventsOptions(true)),
                        Lines.EVENT),
                new HeartbeatStream(
                        "run EventQL query",
                        client -> client.runEventQlQuery("FROM e IN events PROJECT INTO e"),
                        Lines.ROW));
    }

    private static Client clientWith(FakeServer server, Duration stallTimeout) {
        return new Client(server.url(), "secret", new ClientOptions().withStallTimeout(stallTimeout));
    }

    @ParameterizedTest
    @MethodSource("streams")
    void endsAStreamIfNothingArrivesAfterAHeartbeat(HeartbeatStream stream) {
        try (var server = FakeServer.start((exchange, fakeServer) -> {
                    FakeServer.startStream(exchange);
                    FakeServer.writeLine(exchange, Lines.HEARTBEAT);
                    fakeServer.hang();
                });
                var items = stream.open().apply(clientWith(server, TIMEOUT))) {
            var exception = assertThrows(HeartbeatTimeoutException.class, () -> items.forEach(item -> {}));

            assertEquals("no event and no heartbeat arrived for 300 milliseconds", exception.getMessage());
        }
    }

    @Test
    void namesTheDefaultStallTimeoutInItsMessage() {
        var exception = new HeartbeatTimeoutException(new ClientOptions().stallTimeout());

        assertEquals("no event and no heartbeat arrived for 30 seconds", exception.getMessage());
    }

    @ParameterizedTest
    @MethodSource("streams")
    void keepsAStreamAliveWhileHeartbeatsArrive(HeartbeatStream stream) {
        try (var server = FakeServer.start((exchange, fakeServer) -> {
                    FakeServer.startStream(exchange);
                    for (var i = 0; i < 6; i++) {
                        FakeServer.writeLine(exchange, Lines.HEARTBEAT);
                        Thread.sleep(100);
                    }
                    FakeServer.writeLine(exchange, stream.item());
                    fakeServer.hang();
                });
                var items = stream.open().apply(clientWith(server, TIMEOUT))) {
            assertEquals(1, items.limit(1).count());
        }
    }

    @ParameterizedTest
    @MethodSource("streams")
    void handsOutItemsThatArriveInTime(HeartbeatStream stream) {
        try (var server = FakeServer.start((exchange, fakeServer) -> {
                    FakeServer.startStream(exchange);
                    FakeServer.writeLine(exchange, stream.item());
                    Thread.sleep(100);
                    FakeServer.writeLine(exchange, stream.item());
                    fakeServer.hang();
                });
                var items = stream.open().apply(clientWith(server, TIMEOUT))) {
            assertEquals(2, items.limit(2).count());
        }
    }

    @ParameterizedTest
    @MethodSource("streams")
    void doesNotCountTheTimeTheCallerSpendsOnAnItem(HeartbeatStream stream) {
        // The second item arrives while the caller is still busy with the
        // first, so the client has to read again after the caller's time.
        try (var server = FakeServer.start((exchange, fakeServer) -> {
                    FakeServer.startStream(exchange);
                    FakeServer.writeLine(exchange, stream.item());
                    Thread.sleep(TIMEOUT.dividedBy(3));
                    FakeServer.writeLine(exchange, stream.item());
                    fakeServer.hang();
                });
                var items = stream.open().apply(clientWith(server, TIMEOUT))) {
            var count =
                    items.limit(2).peek(item -> sleep(TIMEOUT.multipliedBy(2))).count();

            assertEquals(2, count);
        }
    }

    @ParameterizedTest
    @MethodSource("streams")
    void endsAStreamThatIsClosedWithACancellationRatherThanATimeout(HeartbeatStream stream) {
        try (var server = FakeServer.start((exchange, fakeServer) -> {
                    FakeServer.startStream(exchange);
                    FakeServer.writeLine(exchange, Lines.HEARTBEAT);
                    fakeServer.hang();
                });
                var items = stream.open().apply(clientWith(server, Duration.ofSeconds(5)));
                var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            scheduler.schedule(items::close, 300, TimeUnit.MILLISECONDS);

            assertThrows(CancellationException.class, () -> items.forEach(item -> {}));
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
