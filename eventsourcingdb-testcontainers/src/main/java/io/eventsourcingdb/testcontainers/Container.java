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
import java.util.function.Consumer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

public final class Container {
    private static final String IMAGE_NAME = "thenativeweb/eventsourcingdb";
    private static final String SIGNING_KEY_PATH = "/etc/esdb/signing-key.pem";

    // How many containers start() starts at most, as it replaces each
    // container whose database does not become reachable.
    private static final int MAX_START_ATTEMPTS = 3;

    private String imageTag = "latest";
    private int internalPort = 3000;
    private String apiToken = "secret";
    private KeyPair signingKeyPair;
    private GenericContainer<?> container;

    // Starts a container. It is a field only so that tests can replace it.
    Consumer<GenericContainer<?>> starter = GenericContainer::start;

    public Container withImageTag(String tag) {
        imageTag = tag;
        return this;
    }

    public Container withApiToken(String token) {
        apiToken = token;
        return this;
    }

    public Container withPort(int port) {
        internalPort = port;
        return this;
    }

    public Container withSigningKey() {
        signingKeyPair =
                Security.call(() -> KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        return this;
    }

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

    public String getHost() {
        return running().getHost();
    }

    public int getMappedPort() {
        return running().getMappedPort(internalPort);
    }

    public URI getBaseUrl() {
        return URI.create("http://" + getHost() + ":" + getMappedPort());
    }

    public String getApiToken() {
        return apiToken;
    }

    public PrivateKey getSigningKey() {
        return signingKeyPair().getPrivate();
    }

    public PublicKey getVerificationKey() {
        return signingKeyPair().getPublic();
    }

    public boolean isRunning() {
        return container != null;
    }

    public void stop() {
        if (container == null) {
            return;
        }

        container.stop();
        container = null;
    }

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
