package io.thenativeweb.eventsourcingdb;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.util.Set;
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

    // The v2 API refuses a write it accepted with an error in its answer,
    // except for store-failed, which reports that storing it failed halfway.
    // It refuses a write it did not accept with a status code: 400 for a body
    // of the wrong shape, 401 for an invalid API token, 408 for a body that
    // did not arrive in time, 413 for a body that is too large, 429 for too
    // many requests, and 503 for a server that is shutting down.
    static final Predicate<DbApiException> V2_REFUSALS = exception -> {
        if (exception.status() == 200) {
            return !exception.code().equals(ErrorCode.STORE_FAILED);
        }

        return Set.of(400, 401, 408, 413, 429, 503).contains(exception.status());
    };

    private Outcomes() {}

    // Returns the failure itself if the write was certainly not stored, and an
    // OutcomeUnknownException that wraps it otherwise.
    static RuntimeException ofWrite(String action, RuntimeException failure, Predicate<DbApiException> isRefusal) {
        if (failure instanceof DbApiException exception && isRefusal.test(exception)) {
            return failure;
        }
        if (!doesTheJdkSendWritesAgain() && couldNotConnect(failure)) {
            return failure;
        }

        return new OutcomeUnknownException(action, failure);
    }

    // With the system property jdk.httpclient.enableAllMethodRetry, the JDK
    // sends a write again over a new connection, if the connection it sent
    // the write over closed before the answer arrived. A connection that can
    // not be established then may follow on a write that the server stored
    // already, so it tells nothing. The JDK takes an empty value for true.
    // The property is read for each write, since the JDK reads it once, at a
    // time the client does not know of.
    private static boolean doesTheJdkSendWritesAgain() {
        var value = System.getProperty("jdk.httpclient.enableAllMethodRetry");
        return value != null && (value.isEmpty() || Boolean.parseBoolean(value));
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
