package io.thenativeweb.eventsourcingdb;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

// StallGuard bounds how long a read of the body of a response waits for the
// server. If a read receives nothing for the stall timeout, the guard closes
// the body, which ends the read, and the read throws the exception that stall
// returns for the IOException that ended the read. Every read that returns
// starts the time over, so a body that keeps arriving, if only byte by byte,
// is never cut off. Only the time spent within a read counts, so the time a
// caller spends between two reads, e.g. on an event, does not.
//
// A timer per read is the simplest way to tell a read that waits from one
// that returns at once. The readers of the client read in buffers rather than
// byte by byte, so a single read usually covers many lines of a stream.
final class StallGuard extends FilterInputStream {
    private static final ScheduledThreadPoolExecutor STALL_TIMERS = stallTimers();

    private final long stallTimeoutNanos;
    private final Function<IOException, ? extends RuntimeException> stall;
    private volatile boolean hasStalled;

    StallGuard(InputStream body, Duration stallTimeout, Function<IOException, ? extends RuntimeException> stall) {
        super(body);
        // TimeUnit.convert saturates instead of overflowing, unlike
        // Duration.toNanos, so that a stall timeout of hundreds of years still
        // works.
        stallTimeoutNanos = TimeUnit.NANOSECONDS.convert(stallTimeout);
        this.stall = stall;
    }

    private static ScheduledThreadPoolExecutor stallTimers() {
        var executor = new ScheduledThreadPoolExecutor(
                1,
                Thread.ofPlatform()
                        .daemon()
                        .name("eventsourcingdb-stall-timeout")
                        .factory());
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    // Runs the action once the stall timeout has passed, unless the returned
    // future is canceled before, on the thread of the guards, which mostly
    // waits.
    static ScheduledFuture<?> afterStallTimeout(Duration stallTimeout, Runnable action) {
        return STALL_TIMERS.schedule(action, TimeUnit.NANOSECONDS.convert(stallTimeout), TimeUnit.NANOSECONDS);
    }

    @Override
    public int read() throws IOException {
        return guard(super::read);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        return guard(() -> super.read(buffer, offset, length));
    }

    private int guard(Read read) throws IOException {
        var timer = STALL_TIMERS.schedule(this::stall, stallTimeoutNanos, TimeUnit.NANOSECONDS);
        try {
            return read.run();
        } catch (IOException ex) {
            if (hasStalled) {
                throw stall.apply(ex);
            }
            throw ex;
        } finally {
            timer.cancel(false);
        }
    }

    private void stall() {
        hasStalled = true;
        Http.close(in);
    }

    @FunctionalInterface
    private interface Read {
        int run() throws IOException;
    }
}
