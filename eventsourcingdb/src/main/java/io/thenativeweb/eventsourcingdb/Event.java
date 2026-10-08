package io.thenativeweb.eventsourcingdb;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * An event as stored in EventSourcingDB, in the
 * <a href="https://docs.eventsourcingdb.io/fundamentals/cloud-events/">CloudEvents</a> format.
 *
 * <p>Besides the fields of the event, it keeps its data exactly as the server sent it, so that {@link #verifyHash()}
 * and {@link #verifySignature(PublicKey)} can recompute the hash the server computed.
 */
public final class Event {
    private static final String SIGNATURE_PREFIX = "esdb:signature:v1:";

    private final String specVersion;
    private final String id;
    private final Instant time;
    private final String timeFromServer;
    private final String source;
    private final String subject;
    private final String type;
    private final String dataContentType;
    private final JsonNode data;
    private final String dataFromServer;
    private final String hash;
    private final String predecessorHash;
    private final @Nullable String traceParent;
    private final @Nullable String traceState;
    private final @Nullable String signature;

    private Event(JsonNode cloudEvent, String dataFromServer) {
        specVersion = cloudEvent.path("specversion").asString();
        id = cloudEvent.path("id").asString();
        timeFromServer = cloudEvent.path("time").asString();
        time = Instant.parse(timeFromServer);
        source = cloudEvent.path("source").asString();
        subject = cloudEvent.path("subject").asString();
        type = cloudEvent.path("type").asString();
        dataContentType = cloudEvent.path("datacontenttype").asString();
        data = cloudEvent.path("data");
        this.dataFromServer = dataFromServer;
        hash = cloudEvent.path("hash").asString();
        predecessorHash = cloudEvent.path("predecessorhash").asString();
        traceParent = cloudEvent.path("traceparent").asString(null);
        traceState = cloudEvent.path("tracestate").asString(null);
        signature = cloudEvent.path("signature").asString(null);
    }

    static Event parse(String cloudEvent) {
        return new Event(Json.MAPPER.readTree(cloudEvent), RawJson.property(cloudEvent, "data"));
    }

    /** {@return the version of the CloudEvents specification the event conforms to, e.g. {@code 1.0}} */
    public String specVersion() {
        return specVersion;
    }

    /** {@return the ID of the event, which the server assigns in ascending order} */
    public String id() {
        return id;
    }

    /** {@return the time at which the server wrote the event} */
    public Instant time() {
        return time;
    }

    /** {@return the source of the event, which identifies the system that wrote it} */
    public String source() {
        return source;
    }

    /** {@return the subject the event belongs to, e.g. {@code /books/42}} */
    public String subject() {
        return subject;
    }

    /** {@return the type of the event, e.g. {@code io.eventsourcingdb.library.book-acquired}} */
    public String type() {
        return type;
    }

    /** {@return the content type of the data, i.e. {@code application/json}} */
    public String dataContentType() {
        return dataContentType;
    }

    /**
     * {@return the data of the event as a JSON tree}
     *
     * <p>To get the data as an object of your own type instead, use {@link #data(Class)}.
     */
    public JsonNode data() {
        return data;
    }

    /**
     * Returns the data of the event, deserialized into the given type, e.g. a record.
     *
     * @param <T> the type to deserialize the data into
     * @param dataType the type to deserialize the data into
     * @return the data of the event
     */
    public <T> T data(Class<T> dataType) {
        return Json.MAPPER.treeToValue(data, dataType);
    }

    /** {@return the hash of the event, which the server computes from its fields and its data} */
    public String hash() {
        return hash;
    }

    /** {@return the hash of the event the server wrote before this one, or 64 zeros for the first event} */
    public String predecessorHash() {
        return predecessorHash;
    }

    /** {@return the {@code traceparent} of the W3C trace context the event was written with, if any} */
    public Optional<String> traceParent() {
        return Optional.ofNullable(traceParent);
    }

    /** {@return the {@code tracestate} of the W3C trace context the event was written with, if any} */
    public Optional<String> traceState() {
        return Optional.ofNullable(traceState);
    }

    /** {@return the signature of the event, if the server signs events} */
    public Optional<String> signature() {
        return Optional.ofNullable(signature);
    }

    /**
     * Verifies the integrity of the event, by recomputing its hash and comparing it to the hash stored in the event.
     *
     * @throws EventSourcingDbException if the hashes differ
     */
    public void verifyHash() {
        var metadata = String.join(
                "|", specVersion, id, predecessorHash, timeFromServer, source, subject, type, dataContentType);

        var metadataHash = sha256(metadata);
        var dataHash = sha256(dataFromServer);
        var finalHash = sha256(metadataHash + dataHash);

        if (!finalHash.equals(hash)) {
            throw new EventSourcingDbException("hash verification failed");
        }
    }

    /**
     * Verifies the authenticity of the event. This first verifies the hash of the event, as {@link #verifyHash()} does,
     * and then checks the signature of the hash.
     *
     * @param verificationKey the public Ed25519 key that matches the private key the server signs events with
     * @throws EventSourcingDbException if the event has no signature, if the hash or the signature does not verify, or if
     *     the key is no Ed25519 key
     */
    public void verifySignature(PublicKey verificationKey) {
        if (signature == null) {
            throw new EventSourcingDbException("signature must not be null");
        }

        verifyHash();

        if (!signature.startsWith(SIGNATURE_PREFIX)) {
            throw new EventSourcingDbException("signature must start with '" + SIGNATURE_PREFIX + "'");
        }

        var signatureBytes = HexFormat.of().parseHex(signature.substring(SIGNATURE_PREFIX.length()));

        var isSignatureValid = Security.call("failed to verify signature", () -> {
            var verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(verificationKey);
            verifier.update(hash.getBytes(UTF_8));
            return verifier.verify(signatureBytes);
        });
        if (!isSignatureValid) {
            throw new EventSourcingDbException("signature verification failed");
        }
    }

    private static String sha256(String value) {
        var digest = Security.call("failed to verify hash", () -> MessageDigest.getInstance("SHA-256"));
        return HexFormat.of().formatHex(digest.digest(value.getBytes(UTF_8)));
    }
}
