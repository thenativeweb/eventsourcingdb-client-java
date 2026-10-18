package io.thenativeweb.eventsourcingdb;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.util.function.Predicate;
import javax.net.ssl.SSLHandshakeException;
import org.jspecify.annotations.Nullable;

// Outcomes tells whether a write that failed after it was sent may have been
// stored nevertheless. The server never stops a write halfway, so only two
// failures leave no doubt that it was not: a connection that could not be
// established at all, since then the request never left, and an answer in
// which the server refuses the write. Every other failure, e.g. a stall, a
// broken connection, an abort, or an answer that is not from an
// EventSourcingDB, may come after the server stored the write. The rule is
// the same in every official client SDK, whatever its language could tell
// about how far a request got.
final class Outcomes {
    // The v1 API refuses a write with a status code that names the reason,
    // and only writes it stored answer with 200. A 500 may come after the
    // write was stored, and a 502 or a 504 from a proxy in front of the
    // database.
    static final Predicate<DbApiException> V1_REFUSALS = exception -> {
        var status = exception.status();
        return (status >= 400 && status < 500) || status == 503 || status == 507;
    };

    private Outcomes() {}

    // Returns the failure itself if the write was certainly not stored, and an
    // OutcomeUnknownException that wraps it otherwise.
    static RuntimeException ofWrite(String action, RuntimeException failure, Predicate<DbApiException> isRefusal) {
        if (failure instanceof DbApiException exception && isRefusal.test(exception)) {
            return failure;
        }
        if (couldNotConnect(failure)) {
            return failure;
        }

        return new OutcomeUnknownException(action, failure);
    }

    // The JDK reports a connection that could not be established, e.g.
    // because the host is unknown or nothing listens on the port, with a
    // ConnectException, or with an HttpConnectTimeoutException once the
    // connect timeout of an HTTP client of the caller's own has passed. A
    // TLS handshake that fails comes before the request is sent, too.
    private static boolean couldNotConnect(@Nullable Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException
                    || cause instanceof HttpConnectTimeoutException
                    || cause instanceof SSLHandshakeException) {
                return true;
            }
        }

        return false;
    }
}
