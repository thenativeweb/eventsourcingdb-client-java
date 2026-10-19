package io.thenativeweb.eventsourcingdb.testcontainers;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.testcontainers.containers.ContainerLaunchException;

// Attempt is one attempt of Container.start() to start a container. Its clock
// for the startup timeout starts once it is created, which is right before
// Testcontainers asks Docker to create the container, so that pulling the
// image does not count. While Testcontainers starts the container, Container
// notes here what it learns about the container, so that an attempt that
// failed can tell whether to replace the container.
final class Attempt {
    // How long the wait for the database pauses between two pings.
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    // The id of the attempt, which the label of its container carries.
    final String id = UUID.randomUUID().toString();

    private final long deadline;

    // Whether the wait for the database began. Testcontainers begins it only
    // once Docker published the port of the container.
    boolean hasWaitBegun;

    // Whether the wait for the database ran out of time.
    boolean hasRunOutOfTime;

    // Whether the container was running when Testcontainers gave up on it.
    boolean wasRunning;

    // The last lines of the log of the container, if it exited.
    @Nullable
    String exitLog;

    Attempt(Duration startupTimeout) {
        // Converting saturates, so that a startup timeout too long to count in
        // nanoseconds does not fail.
        deadline = System.nanoTime() + TimeUnit.NANOSECONDS.convert(startupTimeout);
    }

    // Waits until the database answers a ping, until the container exits, or
    // until the startup timeout runs out. It observes the container anew for
    // every ping, including the port that Docker published for the database,
    // as Docker sometimes publishes it late. A failure to observe the
    // container is one of Docker, which ends the wait at once, and does not
    // count as running out of time. That includes an interrupt, which cuts
    // the call to Docker short.
    void waitUntilReachable(Supplier<Observation> observe, IntPredicate answersPing) {
        hasWaitBegun = true;

        while (true) {
            var observation = observe.get();
            if (observation.hasExited()) {
                throw new ContainerLaunchException("the container exited");
            }
            if (System.nanoTime() - deadline >= 0) {
                hasRunOutOfTime = true;
                throw new ContainerLaunchException("the database did not become reachable in time");
            }

            var mappedPort = observation.mappedPort();
            if (mappedPort != null && answersPing.test(mappedPort)) {
                return;
            }

            LockSupport.parkNanos(POLL_INTERVAL.toNanos());
        }
    }

    // Tells whether the container was running, but the database in it did
    // not become reachable in time. That is the case if the wait for the
    // database ran out of time, and also if the container was running when
    // Testcontainers gave up waiting for Docker to publish its port, which it
    // does after a fixed time of its own.
    boolean isUnreachable() {
        return hasRunOutOfTime || (wasRunning && !hasWaitBegun);
    }

    // Observation is what a look at the container tells: whether it exited,
    // and the port on the host that Docker published for the database, if it
    // did yet.
    record Observation(boolean hasExited, @Nullable Integer mappedPort) {}
}
