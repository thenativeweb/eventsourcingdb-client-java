package io.thenativeweb.eventsourcingdb;

/**
 * Reports that the server does not offer what a request needs, so that the client does not send the request at all.
 * {@link Client#writeEvents(java.util.List, java.util.List)} throws it for a recursive precondition, see
 * {@link PreconditionOptions}, if the server does not offer the v2 API, which a recursive precondition needs, and
 * writes no event then. If the client remembers from an earlier request that the server does not offer the v2 API, it
 * asks the server again before it throws, since the server may have been upgraded meanwhile.
 */
public final class IncompatibleServerException extends EventSourcingDbException {
    private static final long serialVersionUID = 1L;

    IncompatibleServerException() {
        super("the server does not offer the v2 API, which a recursive precondition needs");
    }
}
