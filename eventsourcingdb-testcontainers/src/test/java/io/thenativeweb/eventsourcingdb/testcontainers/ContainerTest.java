package io.thenativeweb.eventsourcingdb.testcontainers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.ContainerFetchException;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.ToStringConsumer;
import org.testcontainers.containers.output.WaitingConsumer;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

class ContainerTest {
    private static final String EXITED_PREFIX =
            "failed to start the container, it exited before the database became reachable: ";

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

    // An interrupt while Docker creates the container would cut that call
    // short, so the start lets it finish, and keeps the interrupt for the
    // caller.
    @Test
    void keepsAnInterruptForTheCallerWithoutCuttingTheStartShort() {
        var caller = Thread.currentThread();
        var container = new Container().withImageTag(ImageTag.get());
        var labels = labelsOf(container);
        container.withCreateContainerCmdModifier(command -> {
            caller.interrupt();
            pause(Duration.ofMillis(200));
        });

        boolean wasInterrupted;
        try {
            container.start();
        } finally {
            wasInterrupted = Thread.interrupted();
        }

        try {
            assertTrue(wasInterrupted);
            assertEquals(1, labels.size());
            container.getClient().ping();
        } finally {
            container.stop();
        }

        assertEquals(List.of(), existing(labels));
    }

    @Test
    void takesOnly200OkAsAnAnswerToAPing() throws IOException {
        assertTrue(answersPingWith(200));
        assertFalse(answersPingWith(204));
        assertFalse(answersPingWith(503));
        assertFalse(answersPingWithoutServer());
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

    // Pings a server that answers with the given status code, on the same
    // path as the database.
    private static boolean answersPingWith(int statusCode) throws IOException {
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/v1/ping", exchange -> {
            exchange.sendResponseHeaders(statusCode, -1);
            exchange.close();
        });
        server.start();

        try (var httpClient = HttpClient.newHttpClient()) {
            return Container.answersPing(
                    httpClient,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/ping"));
        } finally {
            server.stop(0);
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
            return Container.answersPing(httpClient, URI.create("http://127.0.0.1:" + port + "/api/v1/ping"));
        }
    }
}
