package io.thenativeweb.eventsourcingdb.testcontainers;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import io.thenativeweb.eventsourcingdb.Client;
import io.thenativeweb.eventsourcingdb.ClientOptions;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.DockerStatus;

/**
 * An EventSourcingDB instance in a Docker container, for integration tests.
 *
 * <p>Configure it with the {@code with} methods, start it, get a client, and stop it eventually:
 *
 * <pre>{@code
 * var container = new Container().withImageTag("1.0.0");
 * container.start();
 *
 * try {
 *     var client = container.getClient();
 *     // ...
 * } finally {
 *     container.stop();
 * }
 * }</pre>
 *
 * <p>It is a Testcontainers {@link GenericContainer}, so it works wherever Testcontainers containers do, e.g. in a
 * {@code try}-with-resources statement, or with the JUnit extension of Testcontainers.
 */
public final class Container extends GenericContainer<Container> {
    private static final String IMAGE_NAME = "thenativeweb/eventsourcingdb";
    private static final String SIGNING_KEY_PATH = "/etc/esdb/signing-key.pem";

    // How many containers start() starts at most, as it replaces each
    // container that runs, but in which the database does not become
    // reachable within the startup timeout.
    static final int MAX_START_ATTEMPTS = 3;

    // The label that start() gives the container of each attempt, with the id
    // of the attempt as its value, so that the containers of an attempt that
    // failed can be found and removed, even one that Testcontainers does not
    // know of.
    static final String START_LABEL = "io.eventsourcingdb.container-start";

    // How many lines from the end of its log the exception names for a
    // container that exited.
    static final int LOG_TAIL_LINES = 20;

    // How long a ping of the database may take before it counts as
    // unanswered.
    private static final Duration PING_TIMEOUT = Duration.ofSeconds(1);

    // How long start() waits at most for the start to give up once the caller
    // was interrupted, before it returns, and leaves the rest to the
    // background, e.g. removing a container that Docker is still creating.
    static final Duration INTERRUPT_GRACE_PERIOD = Duration.ofSeconds(5);

    private int internalPort = 3000;
    private String apiToken = "secret";
    private @Nullable KeyPair signingKeyPair;

    // How long each attempt waits for the database to become reachable. It
    // is not private only so that tests can read its default.
    Duration startupTimeout = Duration.ofSeconds(30);

    // The attempt that start() makes right now, if any. The methods that
    // Testcontainers calls while it starts the container note in it what they
    // learn about the container.
    private @Nullable Attempt attempt;

    // Removes the containers that carry the label of the attempt with the
    // given id. It is a field only so that tests can replace it.
    Consumer<String> containerRemover = this::removeContainersWithLabel;

    /**
     * Creates a container that uses the {@code latest} tag of the official EventSourcingDB image, port 3000 inside the
     * container, and the API token {@code secret}, and does not sign events.
     */
    public Container() {
        super(DockerImageName.parse(IMAGE_NAME + ":latest"));
    }

    /**
     * Sets the tag of the image to use, e.g. a specific version of EventSourcingDB.
     *
     * @param tag the tag of the image
     * @return this container
     */
    public Container withImageTag(String tag) {
        setDockerImageName(IMAGE_NAME + ":" + tag);
        return this;
    }

    /**
     * Sets the API token the instance requires.
     *
     * @param token the API token
     * @return this container
     */
    public Container withApiToken(String token) {
        apiToken = token;
        return this;
    }

    /**
     * Sets the port the instance listens on inside the container. Docker maps it to a free port on the host, which
     * {@link #getMappedPort()} returns.
     *
     * @param port the port inside the container
     * @return this container
     */
    public Container withPort(int port) {
        internalPort = port;
        return this;
    }

    /**
     * Generates a new Ed25519 key pair, and has the instance sign events with its private key.
     *
     * @return this container
     */
    public Container withSigningKey() {
        signingKeyPair =
                Security.call(() -> KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        return this;
    }

    /**
     * Sets how long {@link #start()} waits for the instance in a container to answer pings, from creating the
     * container on. Pulling the image does not count. The default is 30 seconds.
     *
     * @param startupTimeout how long to wait for the instance in a container to answer pings
     * @return this container
     * @throws IllegalArgumentException if the startup timeout is zero or negative
     */
    @Override
    public Container withStartupTimeout(Duration startupTimeout) {
        // A startup timeout of zero or less would fail every attempt at once.
        if (startupTimeout.isZero() || startupTimeout.isNegative()) {
            throw new IllegalArgumentException("startup timeout must be positive");
        }

        this.startupTimeout = startupTimeout;
        return this;
    }

    // Applies the configuration right before the container starts, so that the
    // with methods may be called in any order.
    @Override
    protected void configure() {
        var command = new ArrayList<>(List.of(
                "run",
                "--api-token",
                apiToken,
                "--data-directory-temporary",
                "--http-enabled",
                "--https-enabled=false",
                "--http-port",
                String.valueOf(internalPort)));

        withExposedPorts(internalPort);

        if (signingKeyPair != null) {
            withCopyToContainer(Transferable.of(pem(signingKeyPair.getPrivate()), 0777), SIGNING_KEY_PATH);
            command.addAll(List.of("--signing-key-file", SIGNING_KEY_PATH));
        }

        withCommand(command.toArray(String[]::new));
    }

    private static byte[] pem(PrivateKey privateKey) {
        var encoder = Base64.getMimeEncoder(64, new byte[] {'\n'});

        return ("-----BEGIN PRIVATE KEY-----\n"
                        + encoder.encodeToString(privateKey.getEncoded())
                        + "\n-----END PRIVATE KEY-----\n")
                .getBytes(UTF_8);
    }

    /**
     * Starts the container, and waits until the instance answers pings, for the startup timeout at most.
     *
     * <p>Docker sometimes publishes the port of a container late, or does not make it reachable from the host at all.
     * If the container runs, but the instance does not answer pings within the startup timeout, the container is
     * replaced by a new one, up to three containers in total. If the container exits before the instance answers
     * pings, or if Docker fails, the start fails at once. A container that exits before Docker publishes its port
     * fails only once Testcontainers stops waiting for the port, which takes about five seconds. Either way, the
     * containers of a start that failed are removed.
     *
     * <p>If the calling thread is interrupted before the instance answers pings, the start gives up within five
     * seconds, and throws an {@link IllegalStateException}, and the interrupt status stays set. Containers that the
     * start has not removed by then, e.g. because Docker is still creating one, it removes in the background.
     *
     * @throws IllegalStateException if the database did not become reachable in any of three containers
     * @throws RuntimeException if the container does not start for another reason, e.g. because the image does not
     *     exist
     * @see #withStartupTimeout(Duration)
     */
    @Override
    public void start() {
        // The start runs on a thread of its own, so that an interrupt of the
        // caller does not cut a call to Docker short: Docker might still create
        // a container for a call that was cut short, after the start looked
        // for the containers to remove. Instead, the interrupt cancels the
        // start, and the caller waits for it to give up, which it finishes in
        // the background if that takes longer than the grace period.
        var interruption = new AtomicReference<@Nullable InterruptedException>();
        var started = CompletableFuture.supplyAsync(() -> startAttempts(interruption), Container::runOnThreadOfItsOwn);

        Optional<RuntimeException> failure;
        try {
            failure = started.get();
        } catch (InterruptedException ex) {
            interruption.set(ex);
            failure = started.completeOnTimeout(
                            Optional.of(interrupted(ex)), INTERRUPT_GRACE_PERIOD.toNanos(), TimeUnit.NANOSECONDS)
                    .join();
            Thread.currentThread().interrupt();
        } catch (ExecutionException ex) {
            // The start returns every failure it handles, so only an error
            // that it does not handle ends up here, which is passed on as
            // CompletableFuture.join() would pass it on.
            throw new CompletionException(ex.getCause());
        }

        failure.ifPresent(startFailure -> {
            throw startFailure;
        });
    }

    private static void runOnThreadOfItsOwn(Runnable task) {
        Thread.ofPlatform().daemon().name("eventsourcingdb-container-start").start(task);
    }

    // Starts containers until the database in one of them becomes reachable,
    // and returns the failure otherwise, or once the given interruption of
    // the caller is set. It pulls the image before the first attempt, so that
    // pulling it does not count toward the startup timeout. Testcontainers
    // keeps the image it resolved, so the attempts do not pull it again.
    private Optional<RuntimeException> startAttempts(AtomicReference<@Nullable InterruptedException> interruption) {
        try {
            getDockerImageName();
        } catch (RuntimeException ex) {
            return Optional.of(dockerFailed(ex));
        }

        var removalFailures = new ArrayList<RuntimeException>();
        for (var attemptNumber = 1; ; attemptNumber++) {
            var currentAttempt = new Attempt(startupTimeout, () -> interruption.get() != null);
            var failure = startContainerOf(currentAttempt);
            if (failure.isEmpty()) {
                return Optional.empty();
            }

            removeContainersOf(currentAttempt).ifPresent(removalFailures::add);

            // Once the caller was interrupted, the start gives up, whatever
            // else made the attempt fail.
            var interruptionOfCaller = interruption.get();
            if (interruptionOfCaller != null) {
                return failed(interrupted(interruptionOfCaller), removalFailures);
            }

            var exitLog = currentAttempt.exitLog;
            if (exitLog != null) {
                return failed(exited(exitLog, failure.get()), removalFailures);
            }
            if (!currentAttempt.isUnreachable()) {
                return failed(dockerFailed(failure.get()), removalFailures);
            }
            if (attemptNumber == MAX_START_ATTEMPTS) {
                return failed(unreachable(failure.get()), removalFailures);
            }
        }
    }

    // Starts the container of the given attempt with Testcontainers, and
    // returns the failure if that fails.
    private Optional<RuntimeException> startContainerOf(Attempt currentAttempt) {
        attempt = currentAttempt;
        withLabel(START_LABEL, currentAttempt.id);

        try {
            super.start();
            return Optional.empty();
        } catch (RuntimeException ex) {
            return Optional.of(ex);
        } finally {
            attempt = null;
        }
    }

    // Testcontainers calls this once Docker published the port of the
    // container, to wait until the container is ready.
    @Override
    protected void waitUntilContainerStarted() {
        try (var httpClient =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            Objects.requireNonNull(attempt)
                    .waitUntilReachable(
                            this::observe,
                            port -> answersPing(
                                    httpClient, URI.create("http://" + super.getHost() + ":" + port + "/api/v1/ping")));
        }
    }

    // Looks at the container as Docker sees it right now, rather than as
    // Testcontainers saw it when it started the container.
    private Attempt.Observation observe() {
        var containerInfo = getCurrentContainerInfo();
        var bindings =
                containerInfo.getNetworkSettings().getPorts().getBindings().get(ExposedPort.tcp(internalPort));

        return new Attempt.Observation(
                DockerStatus.isContainerStopped(containerInfo.getState()),
                Stream.ofNullable(bindings)
                        .flatMap(Arrays::stream)
                        .filter(Objects::nonNull)
                        .map(Ports.Binding::getHostPortSpec)
                        .map(Integer::valueOf)
                        .findFirst()
                        .orElse(null));
    }

    // Tells whether the database answers a ping with 200 OK. Any other
    // answer, and no answer at all, mean that it is not reachable yet.
    static boolean answersPing(HttpClient httpClient, URI pingUrl) {
        var request = HttpRequest.newBuilder(pingUrl).timeout(PING_TIMEOUT).build();

        try {
            return httpClient
                            .sendAsync(request, HttpResponse.BodyHandlers.discarding())
                            .join()
                            .statusCode()
                    == 200;
        } catch (CompletionException ex) {
            return false;
        }
    }

    // Testcontainers calls this before it removes a container, also one whose
    // start failed, before it reports the failure. Only until then can it be
    // told whether the container was running or exited, and what it logged.
    @Override
    protected void containerIsStopping(InspectContainerResponse containerInfo) {
        var currentAttempt = attempt;
        if (currentAttempt == null) {
            return;
        }

        var state = getCurrentContainerInfo().getState();
        currentAttempt.wasRunning = Boolean.TRUE.equals(state.getRunning());
        if (DockerStatus.isContainerStopped(state)) {
            currentAttempt.exitLog = lastLines(getLogs());
        }
    }

    // Removes the containers of a failed attempt, and returns the failure if
    // that fails, so that it can be reported along with the failure of the
    // start, without changing its message.
    private Optional<RuntimeException> removeContainersOf(Attempt failedAttempt) {
        // Testcontainers removes a container whose start failed itself, but
        // stopping it releases whatever Testcontainers may still hold for it.
        stop();

        try {
            containerRemover.accept(failedAttempt.id);
            return Optional.empty();
        } catch (RuntimeException ex) {
            return Optional.of(ex);
        }
    }

    private void removeContainersWithLabel(String attemptId) {
        var dockerClient = getDockerClient();

        dockerClient
                .listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(Map.of(START_LABEL, attemptId))
                .exec()
                .forEach(container -> dockerClient
                        .removeContainerCmd(container.getId())
                        .withForce(true)
                        .withRemoveVolumes(true)
                        .exec());
    }

    private static Optional<RuntimeException> failed(RuntimeException failure, List<RuntimeException> removalFailures) {
        removalFailures.forEach(failure::addSuppressed);
        return Optional.of(failure);
    }

    private IllegalStateException unreachable(RuntimeException cause) {
        return new IllegalStateException(
                "failed to start the container, the database did not become reachable within "
                        + seconds(startupTimeout)
                        + "s in any of "
                        + MAX_START_ATTEMPTS
                        + " containers",
                cause);
    }

    private static IllegalStateException interrupted(InterruptedException cause) {
        return new IllegalStateException("failed to start the container: interrupted", cause);
    }

    private static ContainerLaunchException exited(String exitLog, RuntimeException cause) {
        return new ContainerLaunchException(
                "failed to start the container, it exited before the database became reachable: " + exitLog, cause);
    }

    // Names the error of Docker, which Testcontainers wraps in exceptions of
    // its own, as the innermost cause. Docker ends its errors with a line
    // break, which the message leaves out.
    private static ContainerLaunchException dockerFailed(RuntimeException failure) {
        Throwable dockerError = failure;
        for (var cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            dockerError = cause;
        }

        return new ContainerLaunchException(
                "failed to start the container: "
                        + Objects.toString(dockerError.getMessage()).stripTrailing(),
                failure);
    }

    // Describes a duration in seconds, as a whole number if it is one, and
    // with as few decimals as needed otherwise, e.g. 1.5.
    static String seconds(Duration duration) {
        return BigDecimal.valueOf(duration.getSeconds())
                .add(BigDecimal.valueOf(duration.getNano(), 9))
                .stripTrailingZeros()
                .toPlainString();
    }

    static String lastLines(String log) {
        var lines = log.stripTrailing().lines().toList();
        return String.join("\n", lines.subList(Math.max(0, lines.size() - LOG_TAIL_LINES), lines.size()));
    }

    /**
     * {@return the host name to reach the container at}
     *
     * @throws IllegalStateException if the container is not running
     */
    @Override
    public String getHost() {
        throwIfNotRunning();
        return super.getHost();
    }

    /**
     * {@return the port on the host that Docker maps the port of the instance to}
     *
     * @throws IllegalStateException if the container is not running
     */
    public int getMappedPort() {
        throwIfNotRunning();
        return getMappedPort(internalPort);
    }

    /**
     * {@return the URL of the instance, e.g. to set up a client yourself}
     *
     * @throws IllegalStateException if the container is not running
     */
    public URI getBaseUrl() {
        return URI.create("http://" + getHost() + ":" + getMappedPort());
    }

    /** {@return the API token the instance requires} */
    public String getApiToken() {
        return apiToken;
    }

    /**
     * {@return the private key the instance signs events with}
     *
     * @throws IllegalStateException if no signing key was set with {@link #withSigningKey()}
     */
    public PrivateKey getSigningKey() {
        return signingKeyPair().getPrivate();
    }

    /**
     * {@return the public key that matches the signing key, which verifies the signatures of events}
     *
     * <p>Pass it to {@link io.thenativeweb.eventsourcingdb.Event#verifySignature(PublicKey)}.
     *
     * @throws IllegalStateException if no signing key was set with {@link #withSigningKey()}
     */
    public PublicKey getVerificationKey() {
        return signingKeyPair().getPublic();
    }

    /**
     * {@return a client for the instance}
     *
     * @throws IllegalStateException if the container is not running
     */
    public Client getClient() {
        return getClient(new ClientOptions());
    }

    /**
     * {@return a client for the instance, with the given options}
     *
     * <p>Use this to test with the same options as in production, e.g. the data mapper of your application.
     *
     * @param options the options of the client
     * @throws IllegalStateException if the container is not running
     */
    public Client getClient(ClientOptions options) {
        return new Client(getBaseUrl(), apiToken, options);
    }

    private void throwIfNotRunning() {
        if (!isRunning()) {
            throw new IllegalStateException("container must be running");
        }
    }

    private KeyPair signingKeyPair() {
        if (signingKeyPair == null) {
            throw new IllegalStateException("signing key not set");
        }

        return signingKeyPair;
    }
}
