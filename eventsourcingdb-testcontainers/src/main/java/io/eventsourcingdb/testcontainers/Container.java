package io.eventsourcingdb.testcontainers;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.eventsourcingdb.Client;
import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
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

    private String imageTag = "latest";
    private int internalPort = 3000;
    private String apiToken = "secret";
    private KeyPair signingKeyPair;
    private GenericContainer<?> container;

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
     * @throws RuntimeException if the container does not start, e.g. because the image does not exist
     */
    public void start() {
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
        container.start();

        this.container = container;
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
     * <p>Pass it to {@link io.eventsourcingdb.Event#verifySignature(PublicKey)}.
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
