package io.eventsourcingdb;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;

public final class DbApiException extends EventSourcingDbException {
    private static final long serialVersionUID = 1L;

    private static final int MAX_REASON_LENGTH = 4096;

    private final String action;
    private final int statusCode;
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

    public String action() {
        return action;
    }

    public int statusCode() {
        return statusCode;
    }

    public String reason() {
        return reason;
    }
}
