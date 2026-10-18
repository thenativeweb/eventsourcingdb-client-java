package io.thenativeweb.eventsourcingdb;

/**
 * Reports that the signature of an event does not match its hash, i.e. that the event was not signed with the private
 * key that matches the given verification key, or that its signature was changed or cut short.
 * {@link Event#verifySignature(java.security.PublicKey)} throws it.
 */
public final class SignatureVerificationFailedException extends EventSourcingDbException {
    private static final long serialVersionUID = 1L;

    SignatureVerificationFailedException() {
        super("signature verification failed");
    }

    SignatureVerificationFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
