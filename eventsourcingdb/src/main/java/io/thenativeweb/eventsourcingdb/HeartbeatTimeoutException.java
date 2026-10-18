package io.thenativeweb.eventsourcingdb;

import java.time.Duration;

/**
 * Reports that a stream ended because neither an item nor a heartbeat arrived for the stall timeout, 30 seconds by
 * default. While there is nothing else to send, the server sends a heartbeat every second on the streams of
 * {@link Client#observeEvents(String, ObserveEventsOptions)} and {@link Client#runEventQlQuery(String)}, so a stream
 * that stays silent for that long has stalled, e.g. behind a proxy that keeps the connection open but no longer passes
 * anything on. The client then closes the connection. Set the stall timeout with
 * {@link ClientOptions#withStallTimeout(Duration)}.
 */
public final class HeartbeatTimeoutException extends EventSourcingDbException {
    private static final long serialVersionUID = 1L;

    HeartbeatTimeoutException(Duration stallTimeout) {
        super("no event and no heartbeat arrived for " + Durations.describe(stallTimeout));
    }
}
