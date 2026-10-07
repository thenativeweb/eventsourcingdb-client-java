package io.thenativeweb.eventsourcingdb;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;

/**
 * Reports that the server answered a request with an unexpected HTTP status code. Every method of the client that
 * sends a request throws it in that case, so that you can tell failures apart by {@link #statusCode()} and
 * {@link #reason()} instead of parsing the message.
 *
 * <p>Different failures may share a status code: a failed precondition and an event that does not match its schema,
 * for example, are both answered with 409, and only their reasons differ.
 */
public final class DbApiException extends EventSourcingDbException {
    private static final long serialVersionUID = 1L;

    private static final int MAX_REASON_LENGTH = 4096;

    /** What the client tried to do. */
    private final String action;
    /** The HTTP status code the server answered with. */
    private final int statusCode;
    /** The reason the server gave in the response body. */
    private final String reason;

    private DbApiException(String action, int statusCode, String reason, IOException readFailure) {
        super(message(action, statusCode, reason, readFailure), readFailure);
        this.action = action;
        this.statusCode = statusCode;
        this.reason = reason;
    }

    static DbApiException of(String action, HttpResponse<InputStream> response) {
        try (var body = response.body()) {
            var reason = new String(body.readNBytes(MAX_REASON_LENGTH), UTF_8).strip();
            return new DbApiException(action, response.statusCode(), reason, null);
        } catch (IOException ex) {
            return new DbApiException(action, response.statusCode(), "", ex);
        }
    }

    private static String message(String action, int statusCode, String reason, IOException readFailure) {
        var message = "failed to %s, got HTTP status code '%d', expected '200'".formatted(action, statusCode);

        if (readFailure != null) {
            return message + ", and failed to read the reason: " + readFailure.getMessage();
        }

        if (reason.isEmpty()) {
            return message;
        }

        return message + ": " + reason;
    }

    /** {@return what the client tried to do, e.g. {@code write events}} */
    public String action() {
        return action;
    }

    /** {@return the HTTP status code the server answered with} */
    public int statusCode() {
        return statusCode;
    }

    /**
     * {@return the reason the server gave in the response body}
     *
     * <p>It is stripped of leading and trailing white space, and cut to at most 4096 bytes. It is empty if the server gave
     * none, or if reading it failed, in which case {@link #getCause()} tells why.
     */
    public String reason() {
        return reason;
    }
}
