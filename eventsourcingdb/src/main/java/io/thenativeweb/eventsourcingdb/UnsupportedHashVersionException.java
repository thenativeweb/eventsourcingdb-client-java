package io.thenativeweb.eventsourcingdb;

/**
 * Reports that the hash of an event is of a version that the client does not know, e.g. one that a newer release of
 * EventSourcingDB writes. The client can not verify such a hash, which does not mean that the event was changed: to
 * verify it, update the client. {@link Event#verifyHash()} throws it, and so does
 * {@link Event#verifySignature(java.security.PublicKey)}, which verifies the hash first. The message names the version,
 * e.g. {@code v3}.
 */
public final class UnsupportedHashVersionException extends EventSourcingDbException {
    private static final long serialVersionUID = 1L;

    UnsupportedHashVersionException(String version) {
        super("hash version '" + version + "' is not supported");
    }
}
