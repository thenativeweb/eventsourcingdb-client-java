package io.thenativeweb.eventsourcingdb;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;

// TransportChooser is the one place that chooses between the v1 and the v2
// API. Creating a client sends no request, so the first request of a client
// asks the server whether it offers the v2 API, with a ping to the v2 API: a
// server that does answers it, and one that does not answers 404 or 405, as
// EventSourcingDB 1.2.0 does for every path of the v2 API it does not know,
// and so does a proxy in front of it that passes on the v1 API only.
// The answer is kept for the client, so the server is asked only once.
//
// Any other outcome, e.g. a server that can not be reached, tells nothing, so
// the request that asked fails, and the next request asks again. Requests
// that are made while the server is being asked wait for its answer rather
// than asking again, and fail along with it, each with its own action.
final class TransportChooser {
    private static final String PROBE_PATH = "/api/v2/system/ping";

    private final Requests requests;
    private final V1Transport v1;
    private final V2Transport v2;

    private final Object lock = new Object();
    private @Nullable Transport chosen;
    private @Nullable CompletableFuture<Transport> probe;

    TransportChooser(Requests requests, V1Transport v1, V2Transport v2) {
        this.requests = requests;
        this.v1 = v1;
        this.v2 = v2;
    }

    // Returns the transport, once the server has told which API it offers.
    // The future is the caller's own, so that canceling it ends only the wait
    // of the caller, not the probe, which other requests may wait for, too.
    CompletableFuture<Transport> transport() {
        synchronized (lock) {
            var chosen = this.chosen;
            if (chosen != null) {
                return CompletableFuture.completedFuture(chosen);
            }

            var probe = this.probe;
            if (probe == null) {
                probe = startProbe();
            }

            return probe.copy();
        }
    }

    // Waits for the transport. A failed probe fails the request with an
    // exception of its own, so that each request that waited for the probe
    // names its own action.
    static Transport await(String action, CompletableFuture<Transport> transport) {
        try {
            return transport.get();
        } catch (InterruptedException ex) {
            transport.cancel(true);
            Thread.currentThread().interrupt();
            throw Http.cancellation(action, ex);
        } catch (ExecutionException ex) {
            // A probe fails with a ProbeFailure only.
            var probeFailure = (ProbeFailure) Objects.requireNonNull(ex.getCause());
            throw probeFailure.toException(action);
        }
    }

    // The answer to the probe is small, so the stall timeout bounds the probe
    // as a whole, which spares it a reader of its own.
    private CompletableFuture<Transport> startProbe() {
        var response = requests.sendAsync(requests.get(PROBE_PATH), HttpResponse.BodyHandlers.ofString());

        var stallTimeout = requests.stallTimeout();
        var hasStalled = new AtomicBoolean();
        var stallTimer = StallGuard.afterStallTimeout(stallTimeout, () -> {
            hasStalled.set(true);
            response.cancel(true);
        });

        var result = new CompletableFuture<Transport>();
        probe = result;
        response.whenComplete((answer, failure) -> {
            stallTimer.cancel(false);
            complete(result, answer, failure, hasStalled.get());
        });

        return result;
    }

    // Keeps the transport that the probe found out, or forgets the probe if
    // it failed, so that the next request probes again. Either way, a probe
    // is running no longer. A probe starts only while no other is running, so
    // the probe that completes is the one that is kept.
    private void complete(
            CompletableFuture<Transport> result,
            @Nullable HttpResponse<String> answer,
            @Nullable Throwable failure,
            boolean hasStalled) {
        Transport transport;
        try {
            transport = interpret(answer, failure, hasStalled);
        } catch (ProbeFailure probeFailure) {
            synchronized (lock) {
                probe = null;
            }
            result.completeExceptionally(probeFailure);
            return;
        }

        synchronized (lock) {
            chosen = transport;
            probe = null;
        }
        result.complete(transport);
    }

    private Transport interpret(
            @Nullable HttpResponse<String> answer, @Nullable Throwable failure, boolean hasStalled) {
        if (answer == null) {
            throw failed(failure, hasStalled);
        }

        // A 404 or a 405 means the v1 API, whichever server sends it, since a
        // proxy that passes on the v1 API only answers the probe itself. The
        // requests to the v1 API check that the server is an EventSourcingDB.
        var status = answer.statusCode();
        if (status == 404 || status == 405) {
            return v1;
        }
        if (!Http.isFromEventSourcingDb(answer)) {
            throw new ProbeFailure(action -> new InvalidServerHeaderException());
        }
        if (status != 200) {
            var body = answer.body().getBytes(UTF_8);
            throw new ProbeFailure(action -> DbApiException.ofStatus(
                    action, status, answer.headers(), new ByteArrayInputStream(body), CodeMapping.NONE));
        }

        try {
            if (V2Transport.isPingReceived(Json.MAPPER.readTree(answer.body()))) {
                return v2;
            }
        } catch (JacksonException ex) {
            throw new ProbeFailure(action -> unexpectedAnswer(action, ex));
        }

        throw new ProbeFailure(action -> unexpectedAnswer(action, null));
    }

    // The JDK reports most failures wrapped in a CompletionException, and
    // others, e.g. a cancellation, as they are.
    private ProbeFailure failed(@Nullable Throwable failure, boolean hasStalled) {
        var cause =
                failure instanceof CompletionException completionException ? completionException.getCause() : failure;
        var stallTimeout = requests.stallTimeout();

        if (hasStalled) {
            var timeout = Http.timeout(stallTimeout, cause);
            return new ProbeFailure(action -> new ConnectionStalledException(action, stallTimeout, timeout));
        }
        if (cause instanceof CancellationException) {
            return new ProbeFailure(action -> Http.cancellation(action, cause));
        }

        return new ProbeFailure(action -> new EventSourcingDbException("failed to " + action, cause));
    }

    private static EventSourcingDbException unexpectedAnswer(String action, @Nullable Throwable cause) {
        return new EventSourcingDbException(
                "failed to " + action + ", the server answered " + PROBE_PATH + " unexpectedly", cause);
    }

    // ProbeFailure carries what a failed probe tells into the requests that
    // waited for it, as the exception that each of them throws.
    private static final class ProbeFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        // The exception is created for each request, which a probe that is
        // serialized does not need, so it is not serialized along.
        private final transient Function<String, RuntimeException> exception;

        ProbeFailure(Function<String, RuntimeException> exception) {
            super(null, null, false, false);
            this.exception = exception;
        }

        RuntimeException toException(String action) {
            return exception.apply(action);
        }
    }
}
