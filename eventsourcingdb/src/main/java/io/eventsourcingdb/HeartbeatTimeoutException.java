package io.eventsourcingdb;

public final class HeartbeatTimeoutException extends EventSourcingDbException {
    private static final long serialVersionUID = 1L;

    HeartbeatTimeoutException() {
        super("no event and no heartbeat arrived for 30 seconds");
    }
}
