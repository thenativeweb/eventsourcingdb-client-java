package io.eventsourcingdb;

public class EventSourcingDbException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    EventSourcingDbException(String message) {
        super(message);
    }

    EventSourcingDbException(String message, Throwable cause) {
        super(message, cause);
    }
}
