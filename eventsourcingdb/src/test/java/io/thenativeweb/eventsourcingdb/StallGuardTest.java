package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

// The readers of the client read in buffers, so the tests of the requests
// never read a single byte.
class StallGuardTest {
    @Test
    void readsASingleByte() throws IOException {
        try (var body = new StallGuard(
                new ByteArrayInputStream(new byte[] {42}), Duration.ofSeconds(1), IllegalStateException::new)) {
            assertEquals(42, body.read());
            assertEquals(-1, body.read());
        }
    }
}
