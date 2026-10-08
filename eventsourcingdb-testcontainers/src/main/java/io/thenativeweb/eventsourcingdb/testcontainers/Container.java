package io.thenativeweb.eventsourcingdb.testcontainers;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.thenativeweb.eventsourcingdb.Client;
import io.thenativeweb.eventsourcingdb.ClientOptions;
import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

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

    // How many times start() starts the container at most, as it replaces each
    // container whose database does not become reachable.
    private static final int MAX_START_ATTEMPTS = 3;

    private int internalPort = 3000;
    private String apiToken = "secret";
    private @Nullable KeyPair signingKeyPair;

    // Starts the container once. It is a field only so that tests can replace
    // it.
    Consumer<Container> starter = Container::startOnce;

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
        waitingFor(Wait.forHttp("/api/v1/ping").forPort(internalPort).withStartupTimeout(Duration.ofSeconds(10)));

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
     * Starts the container, and waits until the instance answers pings.
     *
     * <p>Docker Desktop sometimes does not make the port it maps a container to reachable from the host. If the
     * database in a running container does not become reachable, the container is replaced by a new one, up to three
     * times. A container that fails to start is removed either way.
     *
     * @throws IllegalStateException if the database did not become reachable in any of three containers
     * @throws RuntimeException if the container does not start for another reason, e.g. because the image does not
     *     exist
     */
    @Override
    public void start() {
        for (var attempt = 1; ; attempt++) {
            try {
                starter.accept(this);
                return;
            } catch (RuntimeException ex) {
                // A container that keeps running although starting it failed
                // is one whose database can not be reached, so it is replaced
                // by a new one. Every other failure is thrown at once. Either
                // way, the container that failed to start is removed, so that
                // it is not left behind.
                var isRunning = isRunning();
                stop();

                if (!isRunning) {
                    throw ex;
                }
                if (attempt == MAX_START_ATTEMPTS) {
                    throw new IllegalStateException(
                            "failed to start container, the database did not become reachable in "
                                    + MAX_START_ATTEMPTS
                                    + " attempts",
                            ex);
                }
            }
        }
    }

    void startOnce() {
        super.start();
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
