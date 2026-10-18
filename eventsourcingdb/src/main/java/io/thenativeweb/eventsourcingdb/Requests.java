package io.thenativeweb.eventsourcingdb;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import tools.jackson.databind.JsonNode;

// Requests sends the requests of a client, whichever API they go to, and keeps
// track of the requests that are running and the streams that are open, so
// that closing the client ends them.
//
// Every request is sent under the same lock under which close() marks the
// client as closed, so that close() either sees the request and ends it, or
// the request sees that the client is closed and is not sent at all.
final class Requests {
    // QUERY is the HTTP method of the v2 API for reading (RFC 10008), which the
    // JDK knows no constant for.
    private static final String QUERY = "QUERY";

    private final URI baseUrl;
    private final String apiToken;
    private final Duration stallTimeout;
    private final HttpClient httpClient;
    private final boolean isHttpClientOwned;

    private final Object lock = new Object();
    private final Set<Runnable> openRequests = ConcurrentHashMap.newKeySet();
    private final Set<LineStream<?>> openStreams = ConcurrentHashMap.newKeySet();
    private volatile boolean isClosed;

    Requests(URI baseUrl, String apiToken, Duration stallTimeout, HttpClient httpClient, boolean isHttpClientOwned) {
        this.baseUrl = baseUrl;
        this.apiToken = apiToken;
        this.stallTimeout = stallTimeout;
        this.httpClient = httpClient;
        this.isHttpClientOwned = isHttpClientOwned;
    }

    Duration stallTimeout() {
        return stallTimeout;
    }

    HttpClient httpClient() {
        return httpClient;
    }

    // Builds a request that needs no authentication, as pinging does not.
    HttpRequest get(String path) {
        return HttpRequest.newBuilder(baseUrl.resolve(path)).GET().build();
    }

    HttpRequest post(String path) {
        return authorized(path).POST(HttpRequest.BodyPublishers.noBody()).build();
    }

    HttpRequest post(String path, JsonNode body) {
        return withBody(authorized(path), "POST", body);
    }

    HttpRequest query(String path) {
        return authorized(path)
                .method(QUERY, HttpRequest.BodyPublishers.noBody())
                .build();
    }

    HttpRequest query(String path, JsonNode body) {
        return withBody(authorized(path), QUERY, body);
    }

    private HttpRequest.Builder authorized(String path) {
        return HttpRequest.newBuilder(baseUrl.resolve(path)).header("Authorization", "Bearer " + apiToken);
    }

    private static HttpRequest withBody(HttpRequest.Builder request, String method, JsonNode body) {
        return request.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(body)))
                .build();
    }

    // Sends a request and returns the body of its response.
    String send(String action, HttpRequest request, CodeMapping codes) {
        return send(action, request, codes, response -> response, failure -> failure);
    }

    // Sends a write, and interprets the body of its response. A write that
    // fails after it was sent may have been stored nevertheless, unless the
    // failure leaves no doubt that it was not, which isRefusal tells for the
    // errors that the server reports.
    <T> T write(
            String action,
            HttpRequest request,
            CodeMapping codes,
            Predicate<DbApiException> isRefusal,
            Function<String, T> interpretation) {
        return send(action, request, codes, interpretation, failure -> Outcomes.ofWrite(action, failure, isRefusal));
    }

    // Sends a request, and interprets the body of its response. Every failure
    // once the request was sent, the interpretation included, goes through
    // afterSending.
    private <T> T send(
            String action,
            HttpRequest request,
            CodeMapping codes,
            Function<String, T> interpretation,
            UnaryOperator<RuntimeException> afterSending) {
        Http.throwIfInterrupted(action);

        CompletableFuture<HttpResponse<InputStream>> response;
        Runnable abortion;
        synchronized (lock) {
            throwIfClosed();

            response = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            abortion = () -> Http.abort(response);
            openRequests.add(abortion);
        }

        try {
            return interpretation.apply(receive(action, response, codes));
        } catch (RuntimeException ex) {
            throw afterSending.apply(ex);
        } finally {
            openRequests.remove(abortion);
        }
    }

    private String receive(String action, CompletableFuture<HttpResponse<InputStream>> response, CodeMapping codes) {
        try {
            return Http.readBody(action, Http.await(action, response, stallTimeout, codes), stallTimeout);
        } catch (RuntimeException ex) {
            if (isClosed) {
                throw Http.cancellation(action, ex);
            }
            throw ex;
        }
    }

    // Sends a request whose response the caller handles on its own. Closing
    // the client cancels it, until it has completed.
    <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler) {
        synchronized (lock) {
            throwIfClosed();

            var response = httpClient.sendAsync(request, bodyHandler);
            Runnable abortion = () -> response.cancel(true);
            openRequests.add(abortion);
            response.whenComplete((result, failure) -> openRequests.remove(abortion));

            return response;
        }
    }

    // Sends the request of a stream, which ends it itself once it is closed.
    CompletableFuture<HttpResponse<InputStream>> sendForStream(HttpRequest request) {
        synchronized (lock) {
            throwIfClosed();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        }
    }

    // Keeps track of a stream, so that closing the client closes it, too.
    <T> LineStream<T> track(LineStream<T> stream) {
        synchronized (lock) {
            throwIfClosed();

            openStreams.add(stream);
            return stream;
        }
    }

    void untrack(LineStream<?> stream) {
        openStreams.remove(stream);
    }

    private void throwIfClosed() {
        if (isClosed) {
            throw new IllegalStateException("client is closed");
        }
    }

    void close() {
        synchronized (lock) {
            if (isClosed) {
                return;
            }
            isClosed = true;
        }

        openRequests.forEach(Runnable::run);
        openStreams.forEach(LineStream::close);

        if (isHttpClientOwned) {
            httpClient.shutdownNow();
        }
    }
}
