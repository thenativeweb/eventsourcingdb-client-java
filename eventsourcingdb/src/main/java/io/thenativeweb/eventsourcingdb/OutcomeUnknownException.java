package io.thenativeweb.eventsourcingdb;

/**
 * Reports that writing events or registering an event schema failed in a way that leaves open whether the server
 * stored it nevertheless. The server never stops a write halfway, so a write whose answer is lost, e.g. because the
 * connection stalled or broke, or because the request was aborted after it was sent, may have been stored all the same.
 * Find out whether it was, e.g. by reading the events of the subject, before you write it again.
 *
 * <p>The cause tells what failed, e.g. a {@link ConnectionStalledException}, or a
 * {@link java.util.concurrent.CancellationException} if the thread was interrupted after the request was sent.
 *
 * <p>A write that certainly was not stored throws the failure itself instead: an {@link EventSourcingDbException} if
 * the client could not connect to the server at all, and a {@link DbApiException} if the server refused the write,
 * e.g. because a precondition does not hold.
 */
public final class OutcomeUnknownException extends EventSourcingDbException {
    private static final long serialVersionUID = 1L;

    OutcomeUnknownException(String action, Throwable cause) {
        super("failed to " + action + ", the outcome is unknown", cause);
    }
}
