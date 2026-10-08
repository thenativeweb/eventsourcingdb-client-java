package io.thenativeweb.eventsourcingdb;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

final class Http {
    private Http() {}

    // A thread that is interrupted before a request is sent does not send it.
    static void throwIfInterrupted(String action) {
        if (Thread.currentThread().isInterrupted()) {
            throw cancellation(action, null);
        }
    }

    // Waits for the server to answer with its headers. Interrupting the waiting
    // thread cancels the request.
    static HttpResponse<InputStream> await(String action, CompletableFuture<HttpResponse<InputStream>> response) {
        try {
            return check(action, response.get());
        } catch (InterruptedException ex) {
            response.cancel(true);
            Thread.currentThread().interrupt();
            throw cancellation(action, ex);
        } catch (ExecutionException ex) {
            throw new EventSourcingDbException("failed to " + action, ex.getCause());
        }
    }

    private static HttpResponse<InputStream> check(String action, HttpResponse<InputStream> response) {
        var serverHeader = response.headers().firstValue("Server").orElse("");
        if (!serverHeader.startsWith("EventSourcingDB/")) {
            close(response.body());
            throw new InvalidServerHeaderException();
        }

        if (response.statusCode() != 200) {
            throw DbApiException.of(action, response);
        }

        return response;
    }

    static String readBody(String action, HttpResponse<InputStream> response) {
        try (var body = response.body()) {
            return new String(body.readAllBytes(), UTF_8);
        } catch (IOException ex) {
            throw failure(action, ex);
        }
    }

    // Tells a read that was interrupted apart from one that failed.
    static RuntimeException failure(String action, IOException ex) {
        if (Thread.currentThread().isInterrupted()) {
            return cancellation(action, ex);
        }

        return new EventSourcingDbException("failed to " + action, ex);
    }

    static CancellationException cancellation(String action, Throwable cause) {
        var exception = new CancellationException("failed to " + action + ", the request was canceled");
        exception.initCause(cause);
        return exception;
    }

    static void close(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
