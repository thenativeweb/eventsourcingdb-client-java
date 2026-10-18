package io.thenativeweb.eventsourcingdb;

import java.time.Duration;

/**
 * Reports that a request failed because nothing arrived from the server for the stall timeout, 30 seconds by default,
 * e.g. because the server can not be reached, or because a proxy keeps the connection open but no longer passes
 * anything on. This covers connecting, waiting for the response, and reading it, but not the time you spend on an item
 * of a stream. The client then closes the connection. Set the stall timeout with
 * {@link ClientOptions#withStallTimeout(Duration)}.
 *
 * <p>Once the stream of {@link Client#observeEvents(String, ObserveEventsOptions)} or
 * {@link Client#runEventQlQuery(String)} has started, a stall ends it with a {@link HeartbeatTimeoutException}
 * instead.
 */
public final class ConnectionStalledException extends EventSourcingDbException {
    private static final long serialVersionUID = 1L;

    ConnectionStalledException(String action, Duration stallTimeout) {
        super("failed to " + action + ", nothing arrived from the server for " + Durations.describe(stallTimeout));
    }
}
