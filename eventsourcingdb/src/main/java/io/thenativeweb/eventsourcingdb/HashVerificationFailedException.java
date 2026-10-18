package io.thenativeweb.eventsourcingdb;

/**
 * Reports that the hash of an event does not match the hash that the client computes from its fields and its data,
 * i.e. that the event was changed after the server wrote it. {@link Event#verifyHash()} throws it, and so does
 * {@link Event#verifySignature(java.security.PublicKey)}, which verifies the hash first.
 */
public final class HashVerificationFailedException extends EventSourcingDbException {
    private static final long serialVersionUID = 1L;

    HashVerificationFailedException() {
        super("hash verification failed");
    }
}
