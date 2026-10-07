package io.eventsourcingdb.testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.eventsourcingdb.EventCandidate;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.ContainerLaunchException;

class ContainerTest {
    @Test
    void startsHandsOutAClientAndStops() {
        var container = new Container().withImageTag(ImageTag.fromDockerfile());
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
                .withImageTag(ImageTag.fromDockerfile())
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
        var container = new Container().withImageTag(ImageTag.fromDockerfile()).withSigningKey();

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

    @Test
    void replacesAContainerWhoseDatabaseDoesNotBecomeReachable() {
        var containerIds = new ArrayList<String>();
        var container = new Container().withImageTag(ImageTag.fromDockerfile());

        // Docker Desktop sometimes does not make the port of a container
        // reachable from the host. The container keeps running, but starting
        // it fails, as it does here twice.
        container.starter = genericContainer -> {
            genericContainer.start();
            containerIds.add(genericContainer.getContainerId());
            if (containerIds.size() < 3) {
                throw new ContainerLaunchException("the database did not become reachable");
            }
        };

        container.start();
        try {
            assertEquals(3, containerIds.size());
            assertEquals(List.of(containerIds.get(2)), existing(containerIds));
            container.getClient().ping();
        } finally {
            container.stop();
        }
    }

    @Test
    void givesUpAfterThreeAttemptsAndSaysWhy() {
        var containerIds = new ArrayList<String>();
        var failures = new ArrayList<ContainerLaunchException>();
        var container = new Container().withImageTag(ImageTag.fromDockerfile());

        container.starter = genericContainer -> {
            genericContainer.start();
            containerIds.add(genericContainer.getContainerId());
            var failure = new ContainerLaunchException("the database did not become reachable");
            failures.add(failure);
            throw failure;
        };

        var exception = assertThrows(IllegalStateException.class, container::start);

        assertEquals(
                "failed to start container, the database did not become reachable in 3 attempts",
                exception.getMessage());
        assertEquals(failures.getLast(), exception.getCause());
        assertEquals(3, containerIds.size());
        assertEquals(List.of(), existing(containerIds));
        assertFalse(container.isRunning());
    }

    @Test
    void throwsAnyOtherFailureAtOnceAndRemovesTheContainer() {
        var containerIds = new ArrayList<String>();

        // Without an API token, EventSourcingDB exits right away.
        var container = new Container().withImageTag(ImageTag.fromDockerfile()).withApiToken("");
        container.starter = genericContainer -> {
            try {
                genericContainer.start();
            } finally {
                containerIds.add(genericContainer.getContainerId());
            }
        };

        assertThrows(ContainerLaunchException.class, container::start);

        assertEquals(1, containerIds.size());
        assertEquals(List.of(), existing(containerIds));
        assertFalse(container.isRunning());
    }
}
