package io.thenativeweb.eventsourcingdb;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;

final class Http {
    private Http() {}

    // A thread that is interrupted before a request is sent does not send it.
    static void throwIfInterrupted(String action) {
        if (Thread.currentThread().isInterrupted()) {
            throw cancellation(action, null);
        }
    }

    // Waits for the server to answer with its headers, for the stall timeout
    // at most, which covers connecting and sending the request as well.
    // Interrupting the waiting thread cancels the request.
    static HttpResponse<InputStream> await(
            String action, CompletableFuture<HttpResponse<InputStream>> response, Duration stallTimeout) {
        try {
            // TimeUnit.convert saturates instead of overflowing, unlike
            // Duration.toNanos, so that a stall timeout of hundreds of years
            // still works.
            var stallTimeoutNanos = TimeUnit.NANOSECONDS.convert(stallTimeout);
            return check(action, response.get(stallTimeoutNanos, TimeUnit.NANOSECONDS), stallTimeout);
        } catch (InterruptedException ex) {
            response.cancel(true);
            Thread.currentThread().interrupt();
            throw cancellation(action, ex);
        } catch (ExecutionException ex) {
            throw new EventSourcingDbException("failed to " + action, ex.getCause());
        } catch (TimeoutException ex) {
            abort(response);

            // The stall timeout ends the wait before the JDK raises anything,
            // so the stall carries the exception that the JDK uses for a
            // response that does not arrive in time, which callers may check
            // for, e.g. those who set timeouts on an HTTP client of their own.
            var timeout =
                    new HttpTimeoutException("nothing arrived from the server for " + Durations.describe(stallTimeout));
            timeout.initCause(ex);
            throw new ConnectionStalledException(action, stallTimeout, timeout);
        }
    }

    private static HttpResponse<InputStream> check(
            String action, HttpResponse<InputStream> response, Duration stallTimeout) {
        var serverHeader = response.headers().firstValue("Server").orElse("");
        if (!serverHeader.startsWith("EventSourcingDB/")) {
            close(response.body());
            throw new InvalidServerHeaderException();
        }

        if (response.statusCode() != 200) {
            throw DbApiException.of(action, response.statusCode(), guard(action, response.body(), stallTimeout));
        }

        return response;
    }

    // Ends a request, whether the server has not answered yet or its body is
    // still being read.
    static void abort(CompletableFuture<HttpResponse<InputStream>> response) {
        response.cancel(true);
        if (!response.isCompletedExceptionally()) {
            close(response.join().body());
        }
    }

    static String readBody(String action, HttpResponse<InputStream> response, Duration stallTimeout) {
        try (var body = guard(action, response.body(), stallTimeout)) {
            return new String(body.readAllBytes(), UTF_8);
        } catch (IOException ex) {
            throw failure(action, ex);
        }
    }

    private static InputStream guard(String action, InputStream body, Duration stallTimeout) {
        return new StallGuard(body, stallTimeout, ex -> new ConnectionStalledException(action, stallTimeout, ex));
    }

    // Tells a read that was interrupted apart from one that failed.
    static RuntimeException failure(String action, IOException ex) {
        if (Thread.currentThread().isInterrupted()) {
            return cancellation(action, ex);
        }

        return new EventSourcingDbException("failed to " + action, ex);
    }

    static CancellationException cancellation(String action, @Nullable Throwable cause) {
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
