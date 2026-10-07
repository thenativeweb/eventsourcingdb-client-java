package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;

class HttpTest {
    @Test
    void closeReportsAFailureAsAnUncheckedException() {
        var cause = new IOException("failed to close");

        var exception = assertThrows(
                UncheckedIOException.class,
                () -> Http.close(() -> {
                    throw cause;
                }));

        assertSame(cause, exception.getCause());
    }
}
