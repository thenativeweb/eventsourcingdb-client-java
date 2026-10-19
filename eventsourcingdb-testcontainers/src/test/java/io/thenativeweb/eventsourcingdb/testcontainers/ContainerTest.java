package io.thenativeweb.eventsourcingdb.testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.thenativeweb.eventsourcingdb.ClientOptions;
import io.thenativeweb.eventsourcingdb.EventCandidate;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.WaitStrategy;
import org.testcontainers.containers.wait.strategy.WaitStrategyTarget;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

class ContainerTest {
    @Test
    void startsHandsOutAClientAndStops() {
        var container = new Container().withImageTag(ImageTag.get());
        assertFalse(container.isRunning());

        container.start();
        try {
            assertTrue(container.isRunning());

            var client = container.getClient();
            client.ping();
            client.verifyApiToken();
        } finally {
            container.stop();
        }

        assertFalse(container.isRunning());
    }

    @Test
    void usesACustomApiTokenAndPort() {
        var container = new Container()
                .withImageTag(ImageTag.get())
                .withApiToken("my-token")
                .withPort(4000);

        container.start();
        try {
            assertEquals("my-token", container.getApiToken());

            var host = container.getHost();
            var mappedPort = container.getMappedPort();
            assertEquals(URI.create("http://" + host + ":" + mappedPort), container.getBaseUrl());

            container.getClient().verifyApiToken();
        } finally {
            container.stop();
        }
    }

    @Test
    void signsEventsWithItsSigningKey() {
        var container = new Container().withImageTag(ImageTag.get()).withSigningKey();

        container.start();
        try {
            assertEquals("EdDSA", container.getSigningKey().getAlgorithm());

            var event = container
                    .getClient()
                    .writeEvents(List.of(new EventCandidate(
                            "https://www.eventsourcingdb.io", "/test", "io.eventsourcingdb.test", Map.of("value", 42))))
                    .getFirst();

            assertTrue(event.signature().isPresent());
            event.verifySignature(container.getVerificationKey());
        } finally {
            container.stop();
        }
    }

    // Being a Testcontainers container is what frameworks such as Spring Boot
    // build on, e.g. to connect a client to it.
    @Test
    void isATestcontainersContainer() {
        assertInstanceOf(GenericContainer.class, new Container());
    }

    record BookAcquired(String bookTitle) {}

    @Test
    void handsOutAClientWithTheGivenOptions() {
        var container = new Container().withImageTag(ImageTag.get());

        container.start();
        try {
            var client = container.getClient(new ClientOptions()
                    .withDataMapper(JsonMapper.builder()
                            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                            .build()));

            var event = client.writeEvents(List.of(new EventCandidate(
                            "https://www.eventsourcingdb.io",
                            "/test",
                            "io.eventsourcingdb.test",
                            new BookAcquired("2001"))))
                    .getFirst();

            assertEquals("2001", event.data().path("book_title").asString());
        } finally {
            container.stop();
        }
    }

    @Test
    void failsToStartWithAnImageThatDoesNotExist() {
        var container = new Container().withImageTag("does-not-exist");

        assertThrows(RuntimeException.class, container::start);
        assertFalse(container.isRunning());
    }

    @Test
    void reportsWhatItDoesNotHave() {
        var container = new Container();

        var hostException = assertThrows(IllegalStateException.class, container::getHost);
        assertEquals("container must be running", hostException.getMessage());

        var portException = assertThrows(IllegalStateException.class, container::getMappedPort);
        assertEquals("container must be running", portException.getMessage());

        assertThrows(IllegalStateException.class, container::getBaseUrl);
        assertThrows(IllegalStateException.class, container::getClient);

        var signingKeyException = assertThrows(IllegalStateException.class, container::getSigningKey);
        assertEquals("signing key not set", signingKeyException.getMessage());

        var verificationKeyException = assertThrows(IllegalStateException.class, container::getVerificationKey);
        assertEquals("signing key not set", verificationKeyException.getMessage());
    }

    @Test
    void stopsWithoutFailingIfItIsNotRunning() {
        var container = new Container();

        container.stop();

        assertFalse(container.isRunning());
    }

    // Lists which of the given containers still exist in Docker, running or
    // not.
    private static List<String> existing(List<String> containerIds) {
        return DockerClientFactory.instance()
                .client()
                .listContainersCmd()
                .withShowAll(true)
                .withIdFilter(containerIds)
                .exec()
                .stream()
                .map(container -> container.getId())
                .toList();
    }

    // A wait for pings that fails the given number of times while the
    // container runs, as if its port were not reachable from the host, and
    // then waits as the container does. It notes the containers it waited
    // for, and the failures it threw.
    private static final class UnreachableWait implements WaitStrategy {
        private final WaitStrategy ping;
        private final int failureCount;
        final List<String> containerIds = new ArrayList<>();
        final List<ContainerLaunchException> failures = new ArrayList<>();

        UnreachableWait(WaitStrategy ping, int failureCount) {
            this.ping = ping;
            this.failureCount = failureCount;
        }

        @Override
        public void waitUntilReady(WaitStrategyTarget target) {
            containerIds.add(target.getContainerId());
            if (containerIds.size() > failureCount) {
                ping.waitUntilReady(target);
                return;
            }

            assertTrue(target.isRunning());
            var failure = new ContainerLaunchException("the database did not become reachable");
            failures.add(failure);
            throw failure;
        }

        @Override
        public WaitStrategy withStartupTimeout(Duration startupTimeout) {
            return this;
        }
    }

    // Docker Desktop sometimes does not make the port of a container
    // reachable from the host. The container keeps running, but its wait
    // fails, as it does here twice. Testcontainers stops a container whose
    // wait fails before it reports the failure.
    @Test
    void replacesAContainerWhoseDatabaseDoesNotBecomeReachable() {
        var container = new Container().withImageTag(ImageTag.get());
        var wait = new AtomicReference<UnreachableWait>();
        container.pingWait = port -> {
            var unreachableWait = new UnreachableWait(new Container().pingWait.apply(port), 2);
            wait.compareAndSet(null, unreachableWait);
            return wait.get();
        };

        container.start();
        try {
            var containerIds = wait.get().containerIds;
            assertEquals(3, containerIds.size());
            assertEquals(List.of(containerIds.get(2)), existing(containerIds));
            container.getClient().ping();
        } finally {
            container.stop();
        }
    }

    @Test
    void givesUpAfterThreeAttemptsAndSaysWhy() {
        var container = new Container().withImageTag(ImageTag.get());
        var wait = new UnreachableWait(new Container().pingWait.apply(3000), 3);
        container.pingWait = port -> wait;

        var exception = assertThrows(IllegalStateException.class, container::start);

        assertEquals(
                "failed to start container, the database did not become reachable in 3 attempts",
                exception.getMessage());
        assertTrue(causesOf(exception).contains(wait.failures.getLast()));
        assertEquals(3, wait.containerIds.size());
        assertEquals(List.of(), existing(wait.containerIds));
        assertFalse(container.isRunning());
    }

    private static List<Throwable> causesOf(Throwable exception) {
        var causes = new ArrayList<Throwable>();
        for (var cause = exception.getCause(); cause != null; cause = cause.getCause()) {
            causes.add(cause);
        }
        return causes;
    }

    // A startup timeout that is set on the container applies to its wait for
    // pings.
    @Test
    void passesAStartupTimeoutOnToTheWaitForPings() {
        var timeouts = new ArrayList<Duration>();
        var container = new Container();
        var wait = container.new NotingWait(new WaitStrategy() {
            @Override
            public void waitUntilReady(WaitStrategyTarget target) {}

            @Override
            public WaitStrategy withStartupTimeout(Duration startupTimeout) {
                timeouts.add(startupTimeout);
                return this;
            }
        });

        assertEquals(wait, wait.withStartupTimeout(Duration.ofSeconds(30)));
        assertEquals(List.of(Duration.ofSeconds(30)), timeouts);
    }

    @Test
    void throwsAnyOtherFailureAtOnceAndRemovesTheContainer() {
        var containerIds = new ArrayList<String>();

        // Without an API token, EventSourcingDB exits right away.
        var container = new Container().withImageTag(ImageTag.get()).withApiToken("");
        container.starter = self -> {
            try {
                self.startOnce();
            } finally {
                containerIds.add(self.getContainerId());
            }
        };

        assertThrows(ContainerLaunchException.class, container::start);

        assertEquals(1, containerIds.size());
        assertEquals(List.of(), existing(containerIds));
        assertFalse(container.isRunning());
    }
}
