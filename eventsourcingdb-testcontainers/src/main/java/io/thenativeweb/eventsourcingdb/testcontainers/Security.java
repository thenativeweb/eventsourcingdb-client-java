package io.thenativeweb.eventsourcingdb.testcontainers;

import java.security.GeneralSecurityException;

// Security turns the checked exceptions of the JDK's security API into
// unchecked ones. Ed25519 is part of every JDK since version 15, so they
// only occur if the JDK is broken.
final class Security {
    private Security() {}

    static <T> T call(Action<T> action) {
        try {
            return action.run();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @FunctionalInterface
    interface Action<T> {
        T run() throws GeneralSecurityException;
    }
}
