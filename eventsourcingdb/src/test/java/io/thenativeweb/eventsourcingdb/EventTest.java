package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.thenativeweb.eventsourcingdb.testcontainers.Container;
import java.security.InvalidKeyException;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class EventTest {
    record EventData(int value) {}

    private Container container;

    @AfterEach
    void stopContainer() {
        container.stop();
    }

    private Event writeEvent(Object data) {
        return container
                .getClient()
                .writeEvents(List.of(
                        new EventCandidate("https://www.eventsourcingdb.io", "/test", "io.eventsourcingdb.test", data)))
                .getFirst();
    }

    // Builds an event like the given one, but with one of its fields changed,
    // as if it had been tampered with.
    private static Event tamper(Event event, String field, String value) {
        ObjectNode cloudEvent = Json.MAPPER.createObjectNode();
        cloudEvent.put("specversion", event.specVersion());
        cloudEvent.put("id", event.id());
        cloudEvent.put("time", rfc3339Nano(event));
        cloudEvent.put("source", event.source());
        cloudEvent.put("subject", event.subject());
        cloudEvent.put("type", event.type());
        cloudEvent.put("datacontenttype", event.dataContentType());
        cloudEvent.set("data", event.data());
        cloudEvent.put("hash", event.hash());
        cloudEvent.put("predecessorhash", event.predecessorHash());
        cloudEvent.put("signature", event.signature().orElse(null));
        cloudEvent.put(field, value);

        return Event.parse(Json.MAPPER.writeValueAsString(cloudEvent), Json.MAPPER);
    }

    // Formats the time of an event the way the server does, i.e. without
    // trailing zeros in the fraction of a second.
    private static String rfc3339Nano(Event event) {
        return event.time().toString().replaceAll("(\\.\\d*?)0+Z$", "$1Z");
    }

    @Nested
    class VerifyHash {
        @Test
        void verifiesTheHashOfAnEvent() {
            container = Database.start();
            var event = writeEvent(new EventData(42));

            assertDoesNotThrow(event::verifyHash);
        }

        @Test
        void verifiesTheHashOfAnEventWhoseDataTheServerEscapes() {
            container = Database.start();
            var event = writeEvent(
                    Map.of("text", Lines.SPECIAL_CHARACTERS, "number", 1.50, "nested", Map.of("b", 1, "a", 2)));

            assertDoesNotThrow(event::verifyHash);
            assertEquals(Lines.SPECIAL_CHARACTERS, event.data().path("text").asString());
        }

        @Test
        void throwsIfTheHashIsInvalid() {
            container = Database.start();
            var event = tamper(writeEvent(new EventData(42)), "hash", "0".repeat(64));

            var exception = assertThrows(EventSourcingDbException.class, event::verifyHash);
            assertEquals("hash verification failed", exception.getMessage());
        }
    }

    @Nested
    class VerifySignature {
        @Test
        void throwsIfTheSignatureIsMissing() throws NoSuchAlgorithmException {
            container = Database.start();
            var event = writeEvent(new EventData(42));
            var verificationKey =
                    KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();

            var exception = assertThrows(EventSourcingDbException.class, () -> event.verifySignature(verificationKey));
            assertEquals("signature must not be null", exception.getMessage());
        }

        @Test
        void throwsIfTheHashVerificationFails() {
            container = Database.startWithSigningKey();
            var event = tamper(writeEvent(new EventData(42)), "hash", "0".repeat(64));

            var exception = assertThrows(
                    EventSourcingDbException.class, () -> event.verifySignature(container.getVerificationKey()));
            assertEquals("hash verification failed", exception.getMessage());
        }

        @Test
        void throwsIfTheSignatureHasNoPrefix() {
            container = Database.startWithSigningKey();
            var event = writeEvent(new EventData(42));
            var tamperedEvent =
                    tamper(event, "signature", event.signature().orElseThrow().replace("esdb:signature:v1:", ""));

            var exception = assertThrows(
                    EventSourcingDbException.class,
                    () -> tamperedEvent.verifySignature(container.getVerificationKey()));
            assertEquals("signature must start with 'esdb:signature:v1:'", exception.getMessage());
        }

        @Test
        void throwsIfTheSignatureVerificationFails() throws NoSuchAlgorithmException {
            container = Database.startWithSigningKey();
            var event = writeEvent(new EventData(42));
            var otherVerificationKey =
                    KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();

            var exception =
                    assertThrows(EventSourcingDbException.class, () -> event.verifySignature(otherVerificationKey));
            assertEquals("signature verification failed", exception.getMessage());
        }

        @Test
        void throwsIfTheVerificationKeyIsNoEd25519Key() throws NoSuchAlgorithmException {
            container = Database.startWithSigningKey();
            var event = writeEvent(new EventData(42));
            var rsaKey = KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic();

            var exception = assertThrows(EventSourcingDbException.class, () -> event.verifySignature(rsaKey));
            assertEquals("failed to verify signature", exception.getMessage());
            assertInstanceOf(InvalidKeyException.class, exception.getCause());
        }

        @Test
        void verifiesTheSignature() {
            container = Database.startWithSigningKey();
            var event = writeEvent(new EventData(42));

            assertDoesNotThrow(() -> event.verifySignature(container.getVerificationKey()));
        }
    }
}
