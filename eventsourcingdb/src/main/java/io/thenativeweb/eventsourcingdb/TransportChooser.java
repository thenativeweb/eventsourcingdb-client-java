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
//
// A server that answers a request through the v2 API with 404 or 405 does not
// offer it any longer, e.g. because it was rolled back to a version without
// it, so the client forgets the answer, and the next request asks again.
//
// A server that did not offer the v2 API when the client asked may have been
// upgraded to a version with it since. Only a write with a recursive
// precondition needs the v2 API, so such a write asks again before the client
// refuses it, unless the write itself was the request that asked.
final class TransportChooser {
    private static final String PROBE_PATH = "/api/v2/system/ping";

    private final Requests requests;
    private final V1Transport v1;
    private final V2Transport v2;

    private final Object lock = new Object();
    private @Nullable Choice chosen;
    private @Nullable CompletableFuture<Choice> probe;
    private long generation;

    // A choice is the transport that a probe found out, along with the
    // generation of that answer, which counts the answers that probes stored.
    // The generation tells an answer apart from a later one that names the
    // same transport, so that a request can tell which answer it was sent
    // under. A request either finds the answer remembered from an earlier
    // request, which may be out of date by now, or waits for the probe that
    // finds it out.
    record Choice(Transport transport, long generation, boolean isRemembered) {}

    TransportChooser(Requests requests, V1Transport v1, V2Transport v2) {
        this.requests = requests;
        this.v1 = v1;
        this.v2 = v2;
    }

    // Returns the choice of the transport, once the server has told which API
    // it offers. The future is the caller's own, so that canceling it ends
    // only the wait of the caller, not the probe, which other requests may
    // wait for, too.
    CompletableFuture<Choice> transport() {
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

    @Nullable
    Transport chosen() {
        synchronized (lock) {
            var chosen = this.chosen;
            return chosen != null ? chosen.transport() : null;
        }
    }

    // Forgets the API once the server answered a request through the v2 API
    // with 404 or 405, while the request fails as it does anyway. As for the
    // probe, that holds whatever server sent the answer, so also for one that
    // lacks the Server header: forgetting only makes the next request ask
    // again, and the probe decides. A write fails that way with an
    // OutcomeUnknownException, whose cause is the failure. Through the v1
    // API, a 404 may be an error of its own, e.g. an event type that does not
    // exist, and an error that the v2 API reports in its answer has the status
    // code 200, so neither tells anything about the API.
    //
    // Only the answer that the request was sent under is forgotten, which
    // its generation tells, so that a request answered late leaves alone the
    // probe that another one started meanwhile, which other requests may wait
    // for, and the answer that such a probe stored, even if it is the v2 API
    // again.
    void forgetIfTheV2ApiIsMissing(Choice choice, RuntimeException failure) {
        var answer = failure instanceof OutcomeUnknownException ? failure.getCause() : failure;
        if (choice.transport() != v2 || !isAnsweredWithoutTheV2Api(answer)) {
            return;
        }

        forget(choice);
    }

    // Asks the server again which API it offers, for a request that was sent
    // under the given answer, and returns the choice as transport() does. As
    // above, only the answer that the request was sent under is forgotten, so
    // that requests that ask again at the same time share one probe, and a
    // request that asks again after another one did takes its answer.
    CompletableFuture<Choice> askAgain(Choice choice) {
        synchronized (lock) {
            forget(choice);
            return transport();
        }
    }

    private void forget(Choice choice) {
        synchronized (lock) {
            var chosen = this.chosen;
            if (chosen != null && chosen.generation() == choice.generation()) {
                this.chosen = null;
            }
        }
    }

    // Tells whether a request failed because the server answered it with a
    // status code that says that it does not offer the v2 API.
    private static boolean isAnsweredWithoutTheV2Api(@Nullable Throwable answer) {
        if (answer instanceof DbApiException exception) {
            return isV2ApiMissing(exception.status());
        }
        if (answer instanceof InvalidServerHeaderException exception) {
            return isV2ApiMissing(exception.status());
        }

        return false;
    }

    // A server answers the paths of the v2 API with 404 or 405 if it does not
    // offer the v2 API, as the comment on the class tells.
    private static boolean isV2ApiMissing(int status) {
        return status == 404 || status == 405;
    }

    // Waits for the transport. A failed probe fails the request with an
    // exception of its own, so that each request that waited for the probe
    // names its own action.
    static Choice await(String action, CompletableFuture<Choice> transport) {
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
    private CompletableFuture<Choice> startProbe() {
        var response = requests.sendAsync(requests.get(PROBE_PATH), HttpResponse.BodyHandlers.ofString());

        var stallTimeout = requests.stallTimeout();
        var hasStalled = new AtomicBoolean();
        var stallTimer = StallGuard.afterStallTimeout(stallTimeout, () -> {
            hasStalled.set(true);
            response.cancel(true);
        });

        var result = new CompletableFuture<Choice>();
        probe = result;
        response.whenComplete((answer, failure) -> {
            stallTimer.cancel(false);
            complete(result, answer, failure, hasStalled.get());
        });

        return result;
    }

    // Keeps the transport that the probe found out, as an answer of a
    // generation of its own, or forgets the probe if it failed, so that the
    // next request probes again. Either way, a probe is running no longer. A
    // probe starts only while no other is running, so the probe that
    // completes is the one that is kept.
    private void complete(
            CompletableFuture<Choice> result,
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

        long answerGeneration;
        synchronized (lock) {
            generation++;
            answerGeneration = generation;
            chosen = new Choice(transport, answerGeneration, true);
            probe = null;
        }
        result.complete(new Choice(transport, answerGeneration, false));
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
        if (isV2ApiMissing(status)) {
            return v1;
        }
        if (!Http.isFromEventSourcingDb(answer)) {
            throw new ProbeFailure(action -> new InvalidServerHeaderException(status));
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
