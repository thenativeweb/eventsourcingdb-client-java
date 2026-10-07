package io.eventsourcingdb;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

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
    private final String traceParent;
    private final String traceState;
    private final String signature;

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

    public String specVersion() {
        return specVersion;
    }

    public String id() {
        return id;
    }

    public Instant time() {
        return time;
    }

    public String source() {
        return source;
    }

    public String subject() {
        return subject;
    }

    public String type() {
        return type;
    }

    public String dataContentType() {
        return dataContentType;
    }

    public JsonNode data() {
        return data;
    }

    public <T> T data(Class<T> dataType) {
        return Json.MAPPER.treeToValue(data, dataType);
    }

    public String hash() {
        return hash;
    }

    public String predecessorHash() {
        return predecessorHash;
    }

    public Optional<String> traceParent() {
        return Optional.ofNullable(traceParent);
    }

    public Optional<String> traceState() {
        return Optional.ofNullable(traceState);
    }

    public Optional<String> signature() {
        return Optional.ofNullable(signature);
    }

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
