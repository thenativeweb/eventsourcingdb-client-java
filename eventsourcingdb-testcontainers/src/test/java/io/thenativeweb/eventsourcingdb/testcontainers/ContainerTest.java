package io.thenativeweb.eventsourcingdb.testcontainers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.sun.net.httpserver.HttpServer;
import io.thenativeweb.eventsourcingdb.ClientOptions;
import io.thenativeweb.eventsourcingdb.EventCandidate;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.ContainerFetchException;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.ToStringConsumer;
import org.testcontainers.containers.output.WaitingConsumer;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

class ContainerTest {
    // The label that tells the containers of a test of reuse apart, so that
    // they reuse only each other.
    private static final String REUSE_TEST_LABEL = "io.eventsourcingdb.client-java.reuse-test";

    // How long a start may take at most to give up once the caller was
    // interrupted.
    private static final Duration INTERRUPT_BOUND = Duration.ofSeconds(5);

    private static final String EXITED_PREFIX =
            "failed to start the container, it exited before the database became reachable: ";

    private static final String V1_PING_PATH = "/api/v1/ping";
    private static final String V2_PING_PATH = "/api/v2/system/ping";

    // How long a test waits for a fake database, which answers at once,
    // except for a ping it answers too late, so that a wait that never ends
    // fails within seconds.
    private static final Duration FAKE_DATABASE_STARTUP_TIMEOUT = Container.PING_TIMEOUT.multipliedBy(5);

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

    @Test
    void waitsThirtySecondsForEachContainerByDefault() {
        assertEquals(Duration.ofSeconds(30), new Container().startupTimeout);
    }

    // The database listens on another port than the one that Docker
    // publishes, so it runs, but can not be reached, as if Docker did not
    // make the published port reachable from the host.
    @Test
    void replacesAContainerInWhichTheDatabaseDoesNotBecomeReachable() {
        var container = new Container().withImageTag(ImageTag.get()).withStartupTimeout(Duration.ofSeconds(5));
        var labels = labelsOf(container);
        container.withCreateContainerCmdModifier(command -> {
            if (labels.size() == 1) {
                listenOnAnotherPort(command);
            }
        });

        container.start();
        try {
            assertEquals(2, labels.size());
            assertNotEquals(labels.get(0), labels.get(1));
            assertEquals(List.of(), existing(labels.subList(0, 1)));
            assertEquals(1, existing(labels.subList(1, 2)).size());

            container.getClient().ping();
        } finally {
            container.stop();
        }

        assertEquals(List.of(), existing(labels));
    }

    @Test
    void givesUpAfterThreeContainersInWhichTheDatabaseDoesNotBecomeReachable() {
        var container = new Container().withImageTag(ImageTag.get()).withStartupTimeout(Duration.ofMillis(1500));
        var labels = labelsOf(container);
        container.withCreateContainerCmdModifier(ContainerTest::listenOnAnotherPort);

        var startedAt = System.nanoTime();
        var exception = assertThrows(IllegalStateException.class, container::start);
        var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertEquals(IllegalStateException.class, exception.getClass());
        assertEquals(
                "failed to start the container, the database did not become reachable within 1.5s in any of 3"
                        + " containers",
                exception.getMessage());
        assertEquals(3, labels.size());
        assertEquals(List.of(), existing(labels));
        assertFalse(container.isRunning());

        // Each container gets the startup timeout that was set, rather than
        // the default, but not more than that by far.
        assertTrue(elapsed.compareTo(Duration.ofMillis(3 * 1500)) >= 0, elapsed::toString);
        assertTrue(elapsed.compareTo(Duration.ofSeconds(30)) < 0, elapsed::toString);
    }

    // A create that takes longer than the startup timeout, as the first one
    // does here, leaves no time to wait for the database, as the time counts
    // from creating the container on. The container that Docker creates after
    // the time ran out is replaced and removed all the same.
    @Test
    void countsCreatingTheContainerTowardTheStartupTimeout() {
        var container = new Container().withImageTag(ImageTag.get()).withStartupTimeout(Duration.ofSeconds(5));
        var labels = labelsOf(container);
        container.withCreateContainerCmdModifier(command -> {
            if (labels.size() == 1) {
                pause(Duration.ofMillis(5500));
            }
        });

        container.start();
        try {
            assertEquals(2, labels.size());
            assertEquals(List.of(), existing(labels.subList(0, 1)));
            container.getClient().ping();
        } finally {
            container.stop();
        }
    }

    // Pulling the image takes longer than the startup timeout here, which
    // must not count.
    @Test
    void doesNotCountPullingTheImageTowardTheStartupTimeout() {
        var container = new Container().withStartupTimeout(Duration.ofSeconds(5));
        container.setImage(CompletableFuture.supplyAsync(
                () -> "thenativeweb/eventsourcingdb:" + ImageTag.get(),
                CompletableFuture.delayedExecutor(6, TimeUnit.SECONDS)));
        var labels = labelsOf(container);

        container.start();
        try {
            assertEquals(1, labels.size());
            container.getClient().ping();
        } finally {
            container.stop();
        }
    }

    // EventSourcingDB writes its error and its usage to different streams,
    // so where the error ends up in the log varies. The test compares with
    // the whole log, which it follows while the container runs.
    @Test
    void failsAtOnceIfTheContainerExits() throws TimeoutException {
        var log = new ToStringConsumer();
        var logEnd = new WaitingConsumer();
        var container = new Container().withImageTag(ImageTag.get()).withLogConsumer(log.andThen(logEnd));
        var labels = labelsOf(container);
        container.withCreateContainerCmdModifier(ContainerTest::passAnInvalidFlag);

        var exception = assertThrows(ContainerLaunchException.class, container::start);

        logEnd.waitUntilEnd(30, TimeUnit.SECONDS);
        var lines = log.toUtf8String().stripTrailing().lines().toList();
        assertTrue(lines.size() > Container.LOG_TAIL_LINES, log::toUtf8String);
        assertEquals(
                EXITED_PREFIX + String.join("\n", lines.subList(lines.size() - Container.LOG_TAIL_LINES, lines.size())),
                exception.getMessage());

        assertEquals(1, labels.size());
        assertEquals(List.of(), existing(labels));
        assertFalse(container.isRunning());
    }

    // Docker rejects the name of the container when it is asked to create
    // it, without asking a registry.
    @Test
    void failsAtOnceIfDockerFails() {
        var container = new Container().withImageTag(ImageTag.get());
        var labels = labelsOf(container);
        container.withCreateContainerCmdModifier(command -> command.withName("not a valid name"));

        var exception = assertThrows(ContainerLaunchException.class, container::start);

        assertEquals(
                "failed to start the container: "
                        + innermostCauseOf(exception).getMessage().stripTrailing(),
                exception.getMessage());
        assertTrue(exception.getMessage().contains("Invalid container name"), exception::getMessage);
        assertEquals(ContainerLaunchException.class, exception.getClass());
        assertEquals(1, labels.size());
        assertEquals(List.of(), existing(labels));
        assertFalse(container.isRunning());
    }

    @Test
    void failsAtOnceIfTheImageDoesNotExist() {
        var container = new Container().withImageTag("does-not-exist");
        var labels = labelsOf(container);

        var exception = assertThrows(ContainerLaunchException.class, container::start);

        assertEquals(
                "failed to start the container: "
                        + innermostCauseOf(exception).getMessage().stripTrailing(),
                exception.getMessage());
        assertInstanceOf(ContainerFetchException.class, exception.getCause());
        assertEquals(List.of(), labels);
        assertFalse(container.isRunning());
    }

    // A container that carries the label of an attempt, but that
    // Testcontainers does not know of, e.g. one that Docker created for a call
    // that failed, is removed as well.
    @Test
    void removesTheContainersOfAFailedAttemptThatTestcontainersDoesNotKnowOf() {
        var dockerClient = DockerClientFactory.instance().client();
        var container = new Container().withImageTag(ImageTag.get());
        var labels = labelsOf(container);
        container.withCreateContainerCmdModifier(command -> dockerClient
                .createContainerCmd(command.getImage())
                .withLabels(Map.of(Container.START_LABEL, command.getLabels().get(Container.START_LABEL)))
                .exec());
        container.withCreateContainerCmdModifier(ContainerTest::passAnInvalidFlag);

        try {
            assertThrows(ContainerLaunchException.class, container::start);

            assertEquals(1, labels.size());
            assertEquals(List.of(), existing(labels));
        } finally {
            remove(labels);
        }
    }

    @Test
    void reportsAFailedRemovalWithoutChangingTheMessage() {
        var removalFailure = new IllegalStateException("removal failed");
        var container = new Container().withImageTag(ImageTag.get());
        var labels = labelsOf(container);
        container.withCreateContainerCmdModifier(ContainerTest::passAnInvalidFlag);
        container.containerRemover = attemptId -> {
            throw removalFailure;
        };

        var exception = assertThrows(ContainerLaunchException.class, container::start);

        assertTrue(exception.getMessage().startsWith(EXITED_PREFIX), exception::getMessage);
        assertFalse(exception.getMessage().contains("removal failed"), exception::getMessage);
        assertArrayEquals(new Throwable[] {removalFailure}, exception.getSuppressed());

        // Testcontainers removed the container it started on its own.
        assertEquals(List.of(), existing(labels));
    }

    // The database does not become reachable here, so the start is still
    // waiting for it when the caller is interrupted.
    @Test
    void givesUpOnAnInterruptWhileItWaitsForTheDatabase() {
        var caller = Thread.currentThread();
        var interruptedAt = new AtomicLong();
        var container = new Container().withImageTag(ImageTag.get()).withStartupTimeout(Duration.ofMinutes(1));
        var labels = labelsOf(container);
        container.withCreateContainerCmdModifier(ContainerTest::listenOnAnotherPort);
        container.withCreateContainerCmdModifier(command -> CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS)
                .execute(() -> {
                    interruptedAt.set(System.nanoTime());
                    caller.interrupt();
                }));

        IllegalStateException exception;
        boolean wasInterrupted;
        try {
            exception = assertThrows(IllegalStateException.class, container::start);
        } finally {
            wasInterrupted = Thread.interrupted();
        }
        var elapsed = Duration.ofNanos(System.nanoTime() - interruptedAt.get());

        assertEquals("failed to start the container: interrupted", exception.getMessage());
        assertInstanceOf(InterruptedException.class, exception.getCause());
        assertTrue(wasInterrupted);
        assertTrue(elapsed.compareTo(INTERRUPT_BOUND) < 0, elapsed::toString);

        // The start removed the container before it returned, and did not
        // replace it.
        assertEquals(1, labels.size());
        assertEquals(List.of(), existing(labels));
    }

    // The interrupt cuts the call to create the container short. Docker may
    // still create the container afterwards, after the start looked for the
    // containers to remove, which the test does itself here a second later.
    // The start looks for them once more later, and removes it then.
    @Test
    void removesAContainerThatDockerCreatesAfterAnInterruptCutTheCreateShort() {
        var dockerClient = DockerClientFactory.instance().client();
        var lateContainerId = new CompletableFuture<String>();
        var container = new Container().withImageTag(ImageTag.get());
        var labels = labelsOf(container);
        container.withCreateContainerCmdModifier(command -> {
            var label = command.getLabels().get(Container.START_LABEL);
            CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS)
                    .execute(() -> lateContainerId.complete(dockerClient
                            .createContainerCmd(command.getImage())
                            .withLabels(Map.of(Container.START_LABEL, label))
                            .exec()
                            .getId()));
            Thread.currentThread().interrupt();
        });

        try {
            var startedAt = System.nanoTime();
            IllegalStateException exception;
            boolean wasInterrupted;
            try {
                exception = assertThrows(IllegalStateException.class, container::start);
            } finally {
                wasInterrupted = Thread.interrupted();
            }
            var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            assertEquals(IllegalStateException.class, exception.getClass());
            assertEquals("failed to start the container: interrupted", exception.getMessage());
            assertInstanceOf(InterruptedException.class, exception.getCause());
            assertTrue(wasInterrupted);
            assertTrue(elapsed.compareTo(INTERRUPT_BOUND) < 0, elapsed::toString);

            var lateContainer = lateContainerId.orTimeout(30, TimeUnit.SECONDS).join();
            assertEquals(List.of(lateContainer), existing(labels));

            var deadline = System.nanoTime()
                    + Container.LATE_REMOVAL_DELAY.plusSeconds(10).toNanos();
            while (!existing(labels).isEmpty() && System.nanoTime() - deadline < 0) {
                pause(Duration.ofMillis(100));
            }

            assertEquals(1, labels.size());
            assertEquals(List.of(), existing(labels));
        } finally {
            // The test creates the late container itself, so it waits for it
            // before it cleans up, even if an assertion failed earlier.
            lateContainerId
                    .orTimeout(30, TimeUnit.SECONDS)
                    .exceptionally(error -> "")
                    .join();
            remove(labels);
        }
    }

    // The attempt fails here without a call to Docker that would cut the
    // interrupt short, so the interrupt status is still set once the attempt
    // failed. The start removes the containers of the attempt all the same.
    @Test
    void removesTheContainersOfAnAttemptThatAnInterruptEnded() {
        var container = new Container().withImageTag(ImageTag.get());
        var labels = labelsOf(container);
        container.withCreateContainerCmdModifier(command -> {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while configuring the container");
        });

        IllegalStateException exception;
        boolean wasInterrupted;
        try {
            exception = assertThrows(IllegalStateException.class, container::start);
        } finally {
            wasInterrupted = Thread.interrupted();
        }

        assertEquals("failed to start the container: interrupted", exception.getMessage());
        assertTrue(wasInterrupted);
        assertArrayEquals(new Throwable[0], exception.getSuppressed());
        assertEquals(1, labels.size());
        assertEquals(List.of(), existing(labels));
    }

    // Testcontainers waits for the image here, which takes a minute, and
    // reports the interrupt as the cause of its failure.
    @Test
    void givesUpOnAnInterruptWhileItPullsTheImage() {
        var caller = Thread.currentThread();
        var container = new Container();
        container.setImage(CompletableFuture.supplyAsync(
                () -> "thenativeweb/eventsourcingdb:" + ImageTag.get(),
                CompletableFuture.delayedExecutor(1, TimeUnit.MINUTES)));
        var labels = labelsOf(container);
        CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS).execute(caller::interrupt);

        var startedAt = System.nanoTime();
        IllegalStateException exception;
        boolean wasInterrupted;
        try {
            exception = assertThrows(IllegalStateException.class, container::start);
        } finally {
            wasInterrupted = Thread.interrupted();
        }
        var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertEquals("failed to start the container: interrupted", exception.getMessage());
        assertInstanceOf(InterruptedException.class, exception.getCause());
        assertTrue(wasInterrupted);
        assertTrue(elapsed.compareTo(INTERRUPT_BOUND) < 0, elapsed::toString);
        assertEquals(List.of(), labels);
    }

    // Users configure the container with modifiers, which must run where
    // Testcontainers runs them, on the thread that starts the container.
    @Test
    void runsModifiersOnTheThreadOfTheCaller() {
        var modifierThreads = new CopyOnWriteArrayList<Thread>();
        var container = new Container()
                .withImageTag(ImageTag.get())
                .withCreateContainerCmdModifier(command -> modifierThreads.add(Thread.currentThread()));

        container.start();
        try {
            assertEquals(List.of(Thread.currentThread()), modifierThreads);
        } finally {
            container.stop();
        }
    }

    // The container resolved its image already, so the start needs no call to
    // Docker that the interrupt could cut short before it asks Docker to
    // create a container.
    @Test
    void givesUpAtOnceIfTheThreadIsInterruptedAlready() {
        var container = new Container().withImageTag(ImageTag.get());
        container.getDockerImageName();
        var labels = labelsOf(container);

        Thread.currentThread().interrupt();
        var startedAt = System.nanoTime();
        IllegalStateException exception;
        boolean wasInterrupted;
        try {
            exception = assertThrows(IllegalStateException.class, container::start);
        } finally {
            wasInterrupted = Thread.interrupted();
        }
        var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertEquals("failed to start the container: interrupted", exception.getMessage());
        assertInstanceOf(InterruptedException.class, exception.getCause());
        assertTrue(wasInterrupted);
        assertTrue(elapsed.compareTo(Duration.ofSeconds(1)) < 0, elapsed::toString);
        assertEquals(List.of(), labels);
    }

    // Testcontainers finds a container to reuse by the configuration of the
    // container, which a label of each attempt would make differ every time.
    @Test
    void leavesAContainerThatTestcontainersReusesToTestcontainers() {
        var reuseId = UUID.randomUUID().toString();
        var first = reusableContainer(reuseId);
        var second = reusableContainer(reuseId);
        var labels = labelsOf(second);

        first.start();
        try {
            second.start();

            assertEquals(first.getContainerId(), second.getContainerId());
            assertEquals(Collections.singletonList(null), labels);
            assertFalse(DockerClientFactory.instance()
                    .client()
                    .inspectContainerCmd(first.getContainerId())
                    .exec()
                    .getConfig()
                    .getLabels()
                    .containsKey(Container.START_LABEL));
            second.getClient().ping();
        } finally {
            first.stop();
            second.stop();
        }

        assertEquals(List.of(), reusableContainers(reuseId));
    }

    // Testcontainers removes a reused container whose start fails on its own,
    // and does not replace it.
    @Test
    void neitherReplacesNorRemovesAContainerThatTestcontainersReuses() {
        var reuseId = UUID.randomUUID().toString();
        var container = reusableContainer(reuseId).withStartupTimeout(Duration.ofMillis(1500));
        var labels = labelsOf(container);
        container.withCreateContainerCmdModifier(ContainerTest::listenOnAnotherPort);

        var exception = assertThrows(ContainerLaunchException.class, container::start);

        assertEquals(ContainerLaunchException.class, exception.getClass());
        assertEquals(
                "failed to start the container: the database did not become reachable in time", exception.getMessage());
        assertEquals(Collections.singletonList(null), labels);
        assertEquals(List.of(), reusableContainers(reuseId));
    }

    // Asked for reuse, Testcontainers does not reuse containers unless the
    // environment allows it, so the start labels, replaces, and removes them
    // as usual.
    @Test
    void labelsAContainerThatAsksForReuseIfTheEnvironmentDoesNotAllowIt() {
        var reuseId = UUID.randomUUID().toString();
        var container = reusableContainer(reuseId);
        container.environmentSupportsReuse = () -> false;
        var labels = labelsOf(container);

        container.start();
        try {
            assertEquals(1, labels.size());
            assertNotNull(labels.getFirst());
            assertEquals(1, existing(labels).size());
        } finally {
            container.stop();
        }

        assertEquals(List.of(), reusableContainers(reuseId));
    }

    @Test
    void rejectsAStartupTimeoutThatIsNotPositive() {
        var container = new Container();

        var zeroException =
                assertThrows(IllegalArgumentException.class, () -> container.withStartupTimeout(Duration.ZERO));
        assertEquals("startup timeout must be positive", zeroException.getMessage());

        var negativeException = assertThrows(
                IllegalArgumentException.class, () -> container.withStartupTimeout(Duration.ofSeconds(-1)));
        assertEquals("startup timeout must be positive", negativeException.getMessage());
    }

    // An error is no failure that the start handles, so it passes it on as it
    // is.
    @Test
    void passesOnAnErrorThatEndsTheStart() {
        var error = new AssertionError("broken");
        var container = new Container().withImageTag(ImageTag.get());
        var labels = labelsOf(container);
        container.withCreateContainerCmdModifier(command -> {
            throw error;
        });

        var exception = assertThrows(AssertionError.class, container::start);

        assertSame(error, exception);
        assertEquals(1, labels.size());
        assertEquals(List.of(), existing(labels));
    }

    @Test
    void takesOnly200OkAsAnAnswerToAPing() throws IOException {
        assertTrue(answersPingWith(Map.of(V2_PING_PATH, answers(200))));
        assertFalse(answersPingWith(Map.of(V2_PING_PATH, answers(204))));
        assertFalse(answersPingWith(Map.of(V2_PING_PATH, answers(404), V1_PING_PATH, answers(204))));
        assertFalse(answersPingWithoutServer());
    }

    // A server without the v2 API, such as EventSourcingDB 1.2, answers its
    // ping with 404 or 405.
    @ParameterizedTest
    @ValueSource(ints = {404, 405})
    void waitsThroughTheV1PingIfTheServerHasNoV2Api(int statusCode) throws IOException {
        var pingedPaths = pingUntilReachable(Map.of(V2_PING_PATH, answers(statusCode), V1_PING_PATH, answers(200)));

        assertEquals(List.of(V2_PING_PATH, V1_PING_PATH), pingedPaths);
    }

    @Test
    void waitsThroughTheV2PingWithoutAskingTheV1Ping() throws IOException {
        var pingedPaths = pingUntilReachable(Map.of(V2_PING_PATH, answers(200), V1_PING_PATH, answers(200)));

        assertEquals(List.of(V2_PING_PATH), pingedPaths);
    }

    // A server without the v1 API answers its ping with 404.
    @Test
    void waitsThroughTheV2PingIfTheServerHasNoV1Api() throws IOException {
        var pingedPaths = pingUntilReachable(Map.of(V2_PING_PATH, answers(200), V1_PING_PATH, answers(404)));

        assertEquals(List.of(V2_PING_PATH), pingedPaths);
    }

    // Only 404 and 405 tell that the server has no v2 API. Any other answer
    // to the v2 ping tells only that the database is not reachable yet.
    @Test
    void pingsTheV2ApiAgainWithoutAskingTheV1PingIfTheV2PingFails() throws IOException {
        var pingedPaths = pingUntilReachable(Map.of(V2_PING_PATH, answers(503, 200), V1_PING_PATH, answers(200)));

        assertEquals(List.of(V2_PING_PATH, V2_PING_PATH), pingedPaths);
    }

    // The fake database answers the first v2 ping with 404 once the ping
    // gave up on it, which would lead to the v1 ping if the wait took it.
    @Test
    void pingsTheV2ApiAgainWithoutAskingTheV1PingIfTheV2PingIsNotAnsweredInTime() throws IOException {
        var lateAnswer = new Answer(404, Container.PING_TIMEOUT.multipliedBy(2));

        var pingedPaths = pingUntilReachable(
                Map.of(V2_PING_PATH, List.of(lateAnswer, new Answer(200, Duration.ZERO)), V1_PING_PATH, answers(200)));

        assertEquals(List.of(V2_PING_PATH, V2_PING_PATH), pingedPaths);
    }

    // Each call of answersPing asks the v2 API first, rather than
    // remembering that the server did not offer it.
    @Test
    void startsEachCallWithTheV2Ping() throws IOException {
        var pingedPaths = pingUntilReachable(Map.of(V2_PING_PATH, answers(404), V1_PING_PATH, answers(503, 200)));

        assertEquals(List.of(V2_PING_PATH, V1_PING_PATH, V2_PING_PATH, V1_PING_PATH), pingedPaths);
    }

    @Test
    void describesDurationsInSeconds() {
        assertEquals("30", Container.seconds(Duration.ofSeconds(30)));
        assertEquals("1.5", Container.seconds(Duration.ofMillis(1500)));
        assertEquals("0.25", Container.seconds(Duration.ofMillis(250)));
        assertEquals("0", Container.seconds(Duration.ZERO));
    }

    @Test
    void takesTheLastLinesOfALog() {
        var lines = new ArrayList<String>();
        for (var number = 1; number <= Container.LOG_TAIL_LINES + 5; number++) {
            lines.add("line " + number);
        }

        assertEquals(
                String.join("\n", lines.subList(5, lines.size())),
                Container.lastLines(String.join("\n", lines) + "\n\n\n"));
        assertEquals("line 1\nline 2", Container.lastLines("line 1\r\nline 2\n"));
        assertEquals("", Container.lastLines(""));
    }

    // Notes the label of each attempt as Testcontainers asks Docker to create
    // its container.
    private static List<String> labelsOf(Container container) {
        var labels = new CopyOnWriteArrayList<String>();
        container.withCreateContainerCmdModifier(
                command -> labels.add(command.getLabels().get(Container.START_LABEL)));
        return labels;
    }

    // A container that asks Testcontainers for reuse, and that only containers
    // with the same id of reuse match.
    private static Container reusableContainer(String reuseId) {
        return new Container().withImageTag(ImageTag.get()).withReuse(true).withLabel(REUSE_TEST_LABEL, reuseId);
    }

    private static List<String> reusableContainers(String reuseId) {
        return DockerClientFactory.instance()
                .client()
                .listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(Map.of(REUSE_TEST_LABEL, reuseId))
                .exec()
                .stream()
                .map(container -> container.getId())
                .toList();
    }

    private static void listenOnAnotherPort(CreateContainerCmd command) {
        var arguments = command.getCmd().clone();
        for (var index = 0; index < arguments.length - 1; index++) {
            if (arguments[index].equals("--http-port")) {
                arguments[index + 1] = String.valueOf(Integer.parseInt(arguments[index + 1]) + 1);
            }
        }
        command.withCmd(arguments);
    }

    private static void passAnInvalidFlag(CreateContainerCmd command) {
        var arguments = new ArrayList<>(List.of(command.getCmd()));
        arguments.add("--no-such-flag");
        command.withCmd(arguments);
    }

    private static void pause(Duration duration) {
        var deadline = System.nanoTime() + duration.toNanos();
        for (var remaining = duration.toNanos(); remaining > 0; remaining = deadline - System.nanoTime()) {
            LockSupport.parkNanos(remaining);
        }
    }

    // Lists the ids of the containers that carry the label of one of the
    // given attempts, running or not. It matches the exact id of each
    // attempt, since the tests of other SDKs use the same label on the same
    // Docker.
    private static List<String> existing(List<String> labels) {
        var dockerClient = DockerClientFactory.instance().client();

        return labels.stream()
                .flatMap(label -> dockerClient
                        .listContainersCmd()
                        .withShowAll(true)
                        .withLabelFilter(Map.of(Container.START_LABEL, label))
                        .exec()
                        .stream())
                .map(container -> container.getId())
                .toList();
    }

    private static void remove(List<String> labels) {
        var dockerClient = DockerClientFactory.instance().client();
        for (var containerId : existing(labels)) {
            dockerClient
                    .removeContainerCmd(containerId)
                    .withForce(true)
                    .withRemoveVolumes(true)
                    .exec();
        }
    }

    private static Throwable innermostCauseOf(Throwable exception) {
        var innermostCause = exception;
        while (innermostCause.getCause() != null) {
            innermostCause = innermostCause.getCause();
        }
        return innermostCause;
    }

    // Asks a fake database with the given answers whether it answers a
    // ping, once.
    private static boolean answersPingWith(Map<String, List<Answer>> answers) throws IOException {
        try (var database = new FakeDatabase(answers);
                var httpClient = HttpClient.newHttpClient()) {
            return Container.answersPing(httpClient, URI.create("http://127.0.0.1:" + database.port()));
        }
    }

    // Pings a port that nothing listens on, as a port that was free a moment
    // ago is.
    private static boolean answersPingWithoutServer() throws IOException {
        int port;
        try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }

        try (var httpClient = HttpClient.newHttpClient()) {
            return Container.answersPing(httpClient, URI.create("http://127.0.0.1:" + port));
        }
    }

    // Waits for a fake database with the given answers, as the start waits
    // for the database in a container, and returns the paths of the pings in
    // the order they arrived.
    private static List<String> pingUntilReachable(Map<String, List<Answer>> answers) throws IOException {
        try (var database = new FakeDatabase(answers);
                var httpClient = HttpClient.newHttpClient()) {
            new Attempt(FAKE_DATABASE_STARTUP_TIMEOUT)
                    .waitUntilReachable(
                            () -> new Attempt.Observation(false, database.port()),
                            port -> Container.answersPing(httpClient, URI.create("http://127.0.0.1:" + port)));

            return List.copyOf(database.pingedPaths);
        }
    }

    private static List<Answer> answers(int... statusCodes) {
        return Arrays.stream(statusCodes)
                .mapToObj(statusCode -> new Answer(statusCode, Duration.ZERO))
                .toList();
    }

    // An answer of the fake database to a ping, which it sends after the
    // given delay.
    private record Answer(int statusCode, Duration delay) {}

    // A server in place of the database, which gives the answers of a path
    // to its pings in turn, the last one to every ping once the others ran
    // out, and which notes the path of each ping as it arrives. Each ping
    // runs on a thread of its own, so that a late answer neither holds up
    // the next ping nor holds up stopping the server.
    private static final class FakeDatabase implements AutoCloseable {
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final HttpServer server;
        final List<String> pingedPaths = new CopyOnWriteArrayList<>();

        FakeDatabase(Map<String, List<Answer>> answers) throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            answers.forEach((path, pathAnswers) -> {
                var remainingAnswers = new ConcurrentLinkedDeque<>(pathAnswers);
                server.createContext(path, exchange -> {
                    pingedPaths.add(exchange.getRequestURI().getPath());

                    var answer =
                            remainingAnswers.size() > 1 ? remainingAnswers.removeFirst() : remainingAnswers.getFirst();
                    pause(answer.delay());
                    exchange.sendResponseHeaders(answer.statusCode(), -1);
                    exchange.close();
                });
            });
            server.setExecutor(executor);
            server.start();
        }

        int port() {
            return server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdown();
        }
    }
}
