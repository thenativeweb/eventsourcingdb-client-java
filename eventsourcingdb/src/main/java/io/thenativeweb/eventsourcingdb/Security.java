package io.thenativeweb.eventsourcingdb;

import java.security.GeneralSecurityException;

// Security turns the checked exceptions of the JDK's security API into an
// EventSourcingDbException that describes what failed.
final class Security {
    private Security() {}

    static <T> T call(String failure, Action<T> action) {
        try {
            return action.run();
        } catch (GeneralSecurityException ex) {
            throw new EventSourcingDbException(failure, ex);
        }
    }

    @FunctionalInterface
    interface Action<T> {
        T run() throws GeneralSecurityException;
    }
}
