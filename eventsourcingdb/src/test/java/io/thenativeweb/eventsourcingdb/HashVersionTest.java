package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

// The hash of an event names its version, except for version 1, which
// predates the prefix. These tests verify events as EventSourcingDB wrote
// them, so that they do not depend on which version the server under test
// writes.
class HashVersionTest {
    private static final String PAYLOAD_PREFIX = "{\"type\":\"event\",\"payload\":";

    // Takes the event out of a line of a stream, leaving its data exactly as
    // the server wrote it.
    private static Event eventFrom(String line) {
        assertEquals(PAYLOAD_PREFIX, line.substring(0, PAYLOAD_PREFIX.length()));
        return Event.parse(line.substring(PAYLOAD_PREFIX.length(), line.length() - 1), Json.MAPPER);
    }

    private static String replaceOnce(String line, String target, String replacement) {
        assertEquals(line.indexOf(target), line.lastIndexOf(target));
        return line.replace(target, replacement);
    }

    @Test
    void verifiesAHashOfVersion1() {
        var event = eventFrom(Lines.EVENT);

        assertDoesNotThrow(event::verifyHash);
    }

    @Test
    void verifiesAHashOfVersion2() {
        var event = eventFrom(Lines.EVENT_WITH_HASH_OF_VERSION_2);

        assertDoesNotThrow(event::verifyHash);
    }

    @Test
    void verifiesAHashOfVersion2OfAnEventWithoutATraceContext() {
        var event = eventFrom(Lines.EVENT_WITH_HASH_OF_VERSION_2_WITHOUT_TRACE_CONTEXT);

        assertDoesNotThrow(event::verifyHash);
    }

    @Test
    void throwsIfAHashOfVersion1DoesNotMatchTheData() {
        var event = eventFrom(replaceOnce(Lines.EVENT, "\"b\":100", "\"b\":101"));

        var exception = assertThrows(HashVerificationFailedException.class, event::verifyHash);
        assertEquals("hash verification failed", exception.getMessage());
    }

    @Test
    void throwsIfAHashOfVersion2DoesNotMatchTheTraceContext() {
        var event = eventFrom(replaceOnce(Lines.EVENT_WITH_HASH_OF_VERSION_2, "vendor=value", "vendor=other"));

        var exception = assertThrows(HashVerificationFailedException.class, event::verifyHash);
        assertEquals("hash verification failed", exception.getMessage());
    }

    @Test
    void throwsIfTheHashIsOfAnUnsupportedVersion() {
        var event = eventFrom(replaceOnce(
                Lines.EVENT_WITH_HASH_OF_VERSION_2, "\"hash\":\"esdb:hash:v2:", "\"hash\":\"esdb:hash:v3:"));

        var exception = assertThrows(UnsupportedHashVersionException.class, event::verifyHash);
        assertEquals("hash version 'v3' is not supported", exception.getMessage());
    }
}
