package io.thenativeweb.eventsourcingdb;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An event as stored in EventSourcingDB, in the
 * <a href="https://docs.eventsourcingdb.io/fundamentals/cloud-events/">CloudEvents</a> format.
 *
 * <p>Besides the fields of the event, it keeps its data exactly as the server sent it, so that {@link #verifyHash()}
 * and {@link #verifySignature(PublicKey)} can recompute the hash the server computed.
 */
public final class Event {
    private static final String SIGNATURE_PREFIX = "esdb:signature:v1:";

    // A hash names its version with a prefix, except for a hash of version 1,
    // which predates the prefix.
    private static final String HASH_PREFIX = "esdb:hash:";
    private static final String HASH_VERSION_2_PREFIX = HASH_PREFIX + "v2:";

    // Stands for a field that the event does not have, e.g. a missing
    // traceparent, in the input of a hash of version 2.
    private static final String MISSING_FIELD = "-";

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
    private final JsonMapper dataMapper;
    private final String hash;
    private final String predecessorHash;
    private final @Nullable String traceParent;
    private final @Nullable String traceState;
    private final @Nullable String signature;

    private Event(JsonNode cloudEvent, String dataFromServer, JsonMapper dataMapper) {
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
        this.dataMapper = dataMapper;
        hash = cloudEvent.path("hash").asString();
        predecessorHash = cloudEvent.path("predecessorhash").asString();
        traceParent = cloudEvent.path("traceparent").asString(null);
        traceState = cloudEvent.path("tracestate").asString(null);
        signature = cloudEvent.path("signature").asString(null);
    }

    static Event parse(String cloudEvent, JsonMapper dataMapper) {
        var captured = RawJson.readValue(cloudEvent, "data");
        return from(captured.tree(), captured.raw(), dataMapper);
    }

    // Builds an event from a cloud event that was read already, along with the
    // text of its data as the server wrote it.
    static Event from(JsonNode cloudEvent, @Nullable String dataFromServer, JsonMapper dataMapper) {
        if (dataFromServer == null) {
            throw new EventSourcingDbException("failed to parse JSON, property 'data' is missing");
        }

        return new Event(cloudEvent, dataFromServer, dataMapper);
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
     * Returns the data of the event, deserialized into the given type, e.g. a record. This uses the data mapper of the
     * client, i.e. the one given by {@link ClientOptions#withDataMapper(tools.jackson.databind.json.JsonMapper)}, or
     * the client's own.
     *
     * @param <T> the type to deserialize the data into
     * @param dataType the type to deserialize the data into
     * @return the data of the event
     */
    public <T> T data(Class<T> dataType) {
        return dataMapper.treeToValue(data, dataType);
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
     * <p>The data counts as it is now, so a change of the tree that {@link #data()} returns fails the verification as
     * well.
     *
     * <p>The hash is recomputed in the version of the hash stored in the event: version 2, whose hash starts with
     * {@code esdb:hash:v2:}, or version 1, whose hash has no prefix. An event keeps the version it was written with, so
     * the events of a store may carry both.
     *
     * @throws HashVerificationFailedException if the hashes differ
     * @throws UnsupportedHashVersionException if the hash is of a version the client does not know, e.g.
     *     {@code esdb:hash:v3:}
     */
    public void verifyHash() {
        if (!computeHash().equals(hash) || hasDataChanged()) {
            throw new HashVerificationFailedException();
        }
    }

    // The hash covers the data as the server sent it, but data() hands it out
    // as a tree that callers can change, so a change after the event arrived
    // shows only by comparing the tree with the data as the server sent it.
    private boolean hasDataChanged() {
        return !Json.MAPPER.readTree(dataFromServer).equals(data);
    }

    private String computeHash() {
        if (hash.startsWith(HASH_VERSION_2_PREFIX)) {
            return computeHashOfVersion2();
        }
        if (hash.startsWith(HASH_PREFIX)) {
            var version = hash.substring(HASH_PREFIX.length()).split(":", 2)[0];
            throw new UnsupportedHashVersionException(version);
        }

        return computeHashOfVersion1();
    }

    // Version 2 hashes the prefix and the fields, each on a line of its own,
    // in a single pass. The data goes in exactly as the server sent it.
    private String computeHashOfVersion2() {
        var input = String.join(
                "\n",
                HASH_VERSION_2_PREFIX,
                specVersion,
                id,
                predecessorHash,
                timeFromServer,
                source,
                subject,
                type,
                dataContentType,
                Objects.requireNonNullElse(traceParent, MISSING_FIELD),
                Objects.requireNonNullElse(traceState, MISSING_FIELD),
                dataFromServer);

        return HASH_VERSION_2_PREFIX + sha256(input);
    }

    // Version 1 hashes the hash of the metadata and the hash of the data
    // together. It covers neither traceparent nor tracestate.
    private String computeHashOfVersion1() {
        var metadata = String.join(
                "|", specVersion, id, predecessorHash, timeFromServer, source, subject, type, dataContentType);

        var metadataHash = sha256(metadata);
        var dataHash = sha256(dataFromServer);
        return sha256(metadataHash + dataHash);
    }

    /**
     * Verifies the authenticity of the event. This first verifies the hash of the event, as {@link #verifyHash()} does,
     * and then checks the signature of the hash.
     *
     * @param verificationKey the public Ed25519 key that matches the private key the server signs events with
     * @throws HashVerificationFailedException if the hash does not verify
     * @throws SignatureVerificationFailedException if the signature does not verify
     * @throws EventSourcingDbException if the event has no signature, or if the key is no Ed25519 key
     * @throws IllegalArgumentException if the signature is not hexadecimal
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

        var verifier = Security.call("failed to verify signature", () -> {
            var signatureVerifier = Signature.getInstance("Ed25519");
            signatureVerifier.initVerify(verificationKey);
            signatureVerifier.update(hash.getBytes(UTF_8));
            return signatureVerifier;
        });

        // The verifier accepted the key already, so a signature that it can
        // not even read, e.g. one that was cut short, is one that does not
        // match the hash.
        boolean isSignatureValid;
        try {
            isSignatureValid = verifier.verify(signatureBytes);
        } catch (SignatureException ex) {
            throw new SignatureVerificationFailedException("failed to verify signature", ex);
        }
        if (!isSignatureValid) {
            throw new SignatureVerificationFailedException();
        }
    }

    private static String sha256(String value) {
        var digest = Security.call("failed to verify hash", () -> MessageDigest.getInstance("SHA-256"));
        return HexFormat.of().formatHex(digest.digest(value.getBytes(UTF_8)));
    }
}
