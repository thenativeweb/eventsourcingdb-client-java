package io.eventsourcingdb;

/**
 * The base class of the exceptions the client throws itself, e.g. if a request fails on its way, if the server reports
 * an error within a stream, or if the verification of an event fails. Like all exceptions of the client, it is
 * unchecked.
 */
public class EventSourcingDbException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    EventSourcingDbException(String message) {
        super(message);
    }

    EventSourcingDbException(String message, Throwable cause) {
        super(message, cause);
    }
}
