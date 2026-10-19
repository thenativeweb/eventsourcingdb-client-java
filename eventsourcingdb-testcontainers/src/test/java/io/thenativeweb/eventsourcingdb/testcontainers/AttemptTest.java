package io.thenativeweb.eventsourcingdb.testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ContainerLaunchException;

class AttemptTest {
    private static final Duration ENOUGH_TIME = Duration.ofMinutes(1);

    // Docker publishes the port only for the second observation, and the
    // database answers the ping only on the port of the third, as if Docker
    // had published the port anew.
    @Test
    void readsThePortAnewForEveryPing() {
        var attempt = new Attempt(ENOUGH_TIME, () -> false);
        var observations = new ArrayList<>(List.of(
                new Attempt.Observation(false, null),
                new Attempt.Observation(false, 1001),
                new Attempt.Observation(false, 1002)));
        var pingedPorts = new ArrayList<Integer>();

        attempt.waitUntilReachable(observations::removeFirst, port -> {
            pingedPorts.add(port);
            return port == 1002;
        });

        assertEquals(List.of(1001, 1002), pingedPorts);
        assertTrue(attempt.hasWaitBegun);
        assertFalse(attempt.hasRunOutOfTime);
    }

    @Test
    void endsTheWaitIfTheContainerExits() {
        var attempt = new Attempt(ENOUGH_TIME, () -> false);

        assertThrows(
                ContainerLaunchException.class,
                () -> attempt.waitUntilReachable(() -> new Attempt.Observation(true, 1001), port -> true));

        assertFalse(attempt.hasRunOutOfTime);
    }

    @Test
    void endsTheWaitOnceTheStartupTimeoutRanOut() {
        var attempt = new Attempt(Duration.ofMillis(300), () -> false);
        var pingCount = new int[] {0};

        var startedAt = System.nanoTime();
        assertThrows(
                ContainerLaunchException.class,
                () -> attempt.waitUntilReachable(() -> new Attempt.Observation(false, 1001), port -> {
                    pingCount[0]++;
                    return false;
                }));

        assertTrue(attempt.hasRunOutOfTime);
        assertTrue(Duration.ofNanos(System.nanoTime() - startedAt).compareTo(Duration.ofMillis(300)) >= 0);
        assertNotEquals(0, pingCount[0]);
    }

    // The clock starts once the attempt is created, so time that passes
    // before the wait begins, e.g. while Docker creates the container,
    // counts.
    @Test
    void countsTheTimeBeforeTheWaitBegins() {
        var attempt = new Attempt(Duration.ZERO, () -> false);

        assertThrows(
                ContainerLaunchException.class,
                () -> attempt.waitUntilReachable(() -> new Attempt.Observation(false, 1001), port -> true));

        assertTrue(attempt.hasRunOutOfTime);
    }

    // A failure to look at the container is one of Docker. It ends the wait
    // at once, and does not count as running out of time.
    @Test
    void endsTheWaitAtOnceIfDockerFails() {
        var attempt = new Attempt(ENOUGH_TIME, () -> false);
        var dockerFailure = new IllegalStateException("Docker failed");

        var exception = assertThrows(
                IllegalStateException.class,
                () -> attempt.waitUntilReachable(
                        () -> {
                            throw dockerFailure;
                        },
                        port -> true));

        assertSame(dockerFailure, exception);
        assertFalse(attempt.hasRunOutOfTime);
        assertFalse(attempt.isUnreachable());
    }

    // An interrupt of the caller cancels the start, which ends the wait
    // without another look at the container, and does not count as running
    // out of time.
    @Test
    void endsTheWaitAtOnceIfTheStartIsCanceled() {
        var attempt = new Attempt(ENOUGH_TIME, () -> true);
        var observationCount = new int[] {0};

        assertThrows(
                ContainerLaunchException.class,
                () -> attempt.waitUntilReachable(
                        () -> {
                            observationCount[0]++;
                            return new Attempt.Observation(false, 1001);
                        },
                        port -> true));

        assertEquals(0, observationCount[0]);
        assertFalse(attempt.hasRunOutOfTime);
        assertFalse(attempt.isUnreachable());
    }

    @Test
    void isUnreachableIfTheWaitRanOutOfTime() {
        var attempt = new Attempt(ENOUGH_TIME, () -> false);
        attempt.hasWaitBegun = true;
        attempt.wasRunning = true;
        attempt.hasRunOutOfTime = true;

        assertTrue(attempt.isUnreachable());
    }

    // Testcontainers gives up waiting for Docker to publish the port of the
    // container after a fixed time of its own, before the wait begins.
    @Test
    void isUnreachableIfTheContainerWasRunningWhenTestcontainersGaveUpBeforeTheWait() {
        var attempt = new Attempt(ENOUGH_TIME, () -> false);
        attempt.wasRunning = true;

        assertTrue(attempt.isUnreachable());
    }

    @Test
    void isNotUnreachableIfTheContainerWasNotRunning() {
        var attempt = new Attempt(ENOUGH_TIME, () -> false);

        assertFalse(attempt.isUnreachable());
    }

    // The wait for the database ended before it ran out of time, e.g.
    // because Docker failed while the container was running.
    @Test
    void isNotUnreachableIfTheWaitEndedForAnotherReason() {
        var attempt = new Attempt(ENOUGH_TIME, () -> false);
        attempt.hasWaitBegun = true;
        attempt.wasRunning = true;

        assertFalse(attempt.isUnreachable());
    }

    @Test
    void hasAnIdOfItsOwn() {
        assertNotEquals(new Attempt(ENOUGH_TIME, () -> false).id, new Attempt(ENOUGH_TIME, () -> false).id);
    }
}
