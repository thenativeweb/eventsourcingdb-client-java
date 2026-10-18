package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DurationsTest {
    @Test
    void describesWholeSecondsInSeconds() {
        assertEquals("30 seconds", Durations.describe(Duration.ofSeconds(30)));
        assertEquals("1 second", Durations.describe(Duration.ofSeconds(1)));
    }

    @Test
    void describesEverythingElseInMilliseconds() {
        assertEquals("300 milliseconds", Durations.describe(Duration.ofMillis(300)));
        assertEquals("1500 milliseconds", Durations.describe(Duration.ofMillis(1500)));
        assertEquals("1 millisecond", Durations.describe(Duration.ofMillis(1)));
    }
}
