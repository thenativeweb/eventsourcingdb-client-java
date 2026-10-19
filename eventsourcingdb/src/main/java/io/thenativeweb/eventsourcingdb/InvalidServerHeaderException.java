package io.thenativeweb.eventsourcingdb;

/**
 * Reports that the server is not an EventSourcingDB, because its response lacks the {@code Server} header that starts
 * with {@code EventSourcingDB/}. This happens, e.g., if the URL points to another service, or to a proxy that answers
 * in place of the database. Every method of the client that sends a request throws it in that case.
 */
public final class InvalidServerHeaderException extends EventSourcingDbException {
    private static final long serialVersionUID = 1L;

    /** The HTTP status code the server answered with. */
    private final int status;

    InvalidServerHeaderException(int status) {
        super("server must be EventSourcingDB");
        this.status = status;
    }

    // Lets the client tell from the status code whether the server offers the
    // v2 API, which a 404 or a 405 tells whatever server sent it.
    int status() {
        return status;
    }
}
