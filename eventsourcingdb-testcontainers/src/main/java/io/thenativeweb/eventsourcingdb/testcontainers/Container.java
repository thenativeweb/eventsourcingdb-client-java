package io.thenativeweb.eventsourcingdb.testcontainers;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.thenativeweb.eventsourcingdb.Client;
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
 */
public final class Container {
    private static final String IMAGE_NAME = "thenativeweb/eventsourcingdb";
    private static final String SIGNING_KEY_PATH = "/etc/esdb/signing-key.pem";

    // How many containers start() starts at most, as it replaces each
    // container whose database does not become reachable.
    private static final int MAX_START_ATTEMPTS = 3;

    private String imageTag = "latest";
    private int internalPort = 3000;
    private String apiToken = "secret";
    private @Nullable KeyPair signingKeyPair;
    private @Nullable GenericContainer<?> container;

    // Starts a container. It is a field only so that tests can replace it.
    Consumer<GenericContainer<?>> starter = GenericContainer::start;

    /**
     * Creates a container that uses the {@code latest} tag of the official EventSourcingDB image, port 3000 inside the
     * container, and the API token {@code secret}, and does not sign events.
     */
    public Container() {}

    /**
     * Sets the tag of the image to use, e.g. a specific version of EventSourcingDB.
     *
     * @param tag the tag of the image
     * @return this container
     */
    public Container withImageTag(String tag) {
        imageTag = tag;
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
    public void start() {
        for (var attempt = 1; ; attempt++) {
            var container = newContainer();

            try {
                starter.accept(container);
                this.container = container;
                return;
            } catch (RuntimeException ex) {
                // Docker Desktop sometimes does not make the port it maps a
                // container to reachable from the host. The container keeps
                // running, but the database in it can never be reached, so it
                // is replaced by a new one. Every other failure is thrown at
                // once. Either way, the container that failed to start is
                // removed, so that it is not left behind.
                var isRunning = container.isRunning();
                container.stop();

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

    private GenericContainer<?> newContainer() {
        var command = new ArrayList<>(List.of(
                "run",
                "--api-token",
                apiToken,
                "--data-directory-temporary",
                "--http-enabled",
                "--https-enabled=false",
                "--http-port",
                String.valueOf(internalPort)));

        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(IMAGE_NAME + ":" + imageTag))
                .withExposedPorts(internalPort)
                .waitingFor(
                        Wait.forHttp("/api/v1/ping").forPort(internalPort).withStartupTimeout(Duration.ofSeconds(10)));

        if (signingKeyPair != null) {
            container.withCopyToContainer(Transferable.of(pem(signingKeyPair.getPrivate()), 0777), SIGNING_KEY_PATH);
            command.addAll(List.of("--signing-key-file", SIGNING_KEY_PATH));
        }

        container.withCommand(command.toArray(String[]::new));

        return container;
    }

    private static byte[] pem(PrivateKey privateKey) {
        var encoder = Base64.getMimeEncoder(64, new byte[] {'\n'});

        return ("-----BEGIN PRIVATE KEY-----\n"
                        + encoder.encodeToString(privateKey.getEncoded())
                        + "\n-----END PRIVATE KEY-----\n")
                .getBytes(UTF_8);
    }

    /**
     * {@return the host name to reach the container at}
     *
     * @throws IllegalStateException if the container is not running
     */
    public String getHost() {
        return running().getHost();
    }

    /**
     * {@return the port on the host that Docker maps the port of the instance to}
     *
     * @throws IllegalStateException if the container is not running
     */
    public int getMappedPort() {
        return running().getMappedPort(internalPort);
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

    /** {@return whether the container is running} */
    public boolean isRunning() {
        return container != null;
    }

    /**
     * Stops and removes the container. If it is not running, this does nothing.
     */
    public void stop() {
        if (container == null) {
            return;
        }

        container.stop();
        container = null;
    }

    /**
     * {@return a client for the instance}
     *
     * @throws IllegalStateException if the container is not running
     */
    public Client getClient() {
        return new Client(getBaseUrl(), apiToken);
    }

    private GenericContainer<?> running() {
        if (container == null) {
            throw new IllegalStateException("container must be running");
        }

        return container;
    }

    private KeyPair signingKeyPair() {
        if (signingKeyPair == null) {
            throw new IllegalStateException("signing key not set");
        }

        return signingKeyPair;
    }
}
