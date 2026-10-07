package io.eventsourcingdb;

public final class InvalidServerHeaderException extends EventSourcingDbException {
    private static final long serialVersionUID = 1L;

    InvalidServerHeaderException() {
        super("server must be EventSourcingDB");
    }
}
