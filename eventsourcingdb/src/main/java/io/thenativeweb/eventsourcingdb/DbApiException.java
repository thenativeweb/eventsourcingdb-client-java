package io.thenativeweb.eventsourcingdb;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reports an error that the server reported. Every method of the client that sends a request throws it in that case,
 * so that you can tell failures apart by {@link #code()} instead of parsing the message.
 *
 * <p>The server reports an error in one of two ways. It either refuses the request with an HTTP status code other than
 * 200, e.g. 401 for an invalid API token, in which case {@link #status()} is that status code, and {@link #code()} is
 * empty, unless the error is one of those listed below. Or it accepts the request and reports an error in its answer,
 * e.g. a precondition that does not hold, in which case {@link #status()} is 200, and {@link #code()} names the error,
 * e.g. {@link ErrorCode#PRECONDITION_VIOLATED}.
 *
 * <p>A server that does not offer the v2 API reports every error with a status code. For those errors that clearly
 * match an error of the v2 API, the client sets the code of the v2 API nevertheless, e.g.
 * {@link ErrorCode#PRECONDITION_VIOLATED} for a write whose precondition does not hold, so that you can handle them
 * alike, whichever API the server offers. The status code stays the one the server answered with, e.g. 409.
 */
public final class DbApiException extends EventSourcingDbException {
    private static final long serialVersionUID = 1L;

    private static final int MAX_DESCRIPTION_LENGTH = 4096;

    private static final int TOO_MANY_REQUESTS = 429;

    // The server sends Retry-After as a number of seconds. A date, which HTTP
    // permits as well, is not one it sends, so it is ignored.
    private static final Pattern SECONDS_PATTERN = Pattern.compile("[0-9]{1,18}");

    /** What the client tried to do. */
    private final String action;
    /** The HTTP status code the server answered with. */
    private final int status;
    /** The code of the error, or an empty string. */
    private final String code;
    /** The description of the error. */
    private final String description;
    /** The details of the error. */
    private final ObjectNode details;
    /** How long the server asks to wait before sending the request again, or {@code null}. */
    private final @Nullable Duration retryAfter;

    private DbApiException(
            String message,
            String action,
            int status,
            String code,
            String description,
            ObjectNode details,
            @Nullable Duration retryAfter,
            @Nullable IOException readFailure) {
        super(message, readFailure);
        this.action = action;
        this.status = status;
        this.code = code;
        this.description = description;
        this.details = details;
        this.retryAfter = retryAfter;
    }

    // Reports a request that the server refused with a status code other than
    // 200. The body of the response is the description, and codes tells which
    // code, if any, the error has.
    static DbApiException ofStatus(
            String action, int status, HttpHeaders headers, InputStream body, CodeMapping codes) {
        var retryAfter = status == TOO_MANY_REQUESTS ? retryAfter(headers) : null;

        String description;
        try (body) {
            description = new String(body.readNBytes(MAX_DESCRIPTION_LENGTH), UTF_8).strip();
        } catch (IOException ex) {
            return new DbApiException(
                    statusMessage(action, status) + ", and failed to read the reason: " + ex.getMessage(),
                    action,
                    status,
                    "",
                    "",
                    Json.MAPPER.createObjectNode(),
                    retryAfter,
                    ex);
        }

        var message = statusMessage(action, status) + (description.isEmpty() ? "" : ": " + description);
        return new DbApiException(
                message,
                action,
                status,
                codes.codeOf(status, description),
                description,
                Json.MAPPER.createObjectNode(),
                retryAfter,
                null);
    }

    // Reports an error that the server reported in its answer to a request it
    // accepted, i.e. with status code 200.
    static DbApiException ofAnswer(String action, String code, String description, JsonNode details) {
        return new DbApiException(
                "failed to " + action + ", got error: " + description,
                action,
                200,
                code,
                description,
                details instanceof ObjectNode objectNode ? objectNode : Json.MAPPER.createObjectNode(),
                null,
                null);
    }

    private static String statusMessage(String action, int status) {
        return "failed to %s, got HTTP status code '%d', expected '200'".formatted(action, status);
    }

    private static @Nullable Duration retryAfter(HttpHeaders headers) {
        return headers.firstValue("Retry-After")
                .map(String::strip)
                .filter(seconds -> SECONDS_PATTERN.matcher(seconds).matches())
                .map(seconds -> Duration.ofSeconds(Long.parseLong(seconds)))
                .orElse(null);
    }

    /** {@return what the client tried to do, e.g. {@code write events}} */
    public String action() {
        return action;
    }

    /**
     * {@return the HTTP status code the server answered with}
     *
     * <p>It is 200 for an error that the server reported in its answer to a request it accepted.
     */
    public int status() {
        return status;
    }

    /**
     * {@return the code of the error, exactly as the server sent it, or an empty string if the error has none}
     *
     * <p>The code is the URL of the page in the documentation that explains the error, e.g.
     * {@code https://docs.eventsourcingdb.io/errors/precondition-violated}. Compare it with the constants of
     * {@link ErrorCode}.
     */
    public String code() {
        return code;
    }

    /**
     * {@return the description of the error, as the server gave it}
     *
     * <p>For an error with a status code other than 200, it is the body of the response, stripped of leading and
     * trailing white space, and cut to at most 4096 bytes. It is empty if the server gave none, or if reading it
     * failed, in which case {@link #getCause()} tells why.
     */
    public String description() {
        return description;
    }

    /**
     * {@return the details of the error, as the server sent them}
     *
     * <p>They locate the error and echo the value it is about, e.g. which event of a write does not match its schema.
     * They are an empty object for an error that the server reported without details, e.g. one with a status code
     * other than 200.
     */
    public JsonNode details() {
        return details;
    }

    /**
     * {@return how long the server asks to wait before sending the request again}
     *
     * <p>It is only present if the server refused the request with status code 429, because the client sent too many
     * requests, and gave the time to wait in its {@code Retry-After} header. The client never repeats a request on its
     * own.
     */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
