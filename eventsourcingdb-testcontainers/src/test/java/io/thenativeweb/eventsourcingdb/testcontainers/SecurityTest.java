package io.thenativeweb.eventsourcingdb.testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.NoSuchAlgorithmException;
import org.junit.jupiter.api.Test;

class SecurityTest {
    @Test
    void returnsTheResultOfTheAction() {
        assertEquals("result", Security.call(() -> "result"));
    }

    @Test
    void turnsACheckedExceptionIntoAnUncheckedOne() {
        var cause = new NoSuchAlgorithmException("Ed25519 is missing");

        var exception = assertThrows(
                IllegalStateException.class,
                () -> Security.call(() -> {
                    throw cause;
                }));
        assertSame(cause, exception.getCause());
    }
}
