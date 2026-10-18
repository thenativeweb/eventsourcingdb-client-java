package io.thenativeweb.eventsourcingdb;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

// BodyHoldingHttpClient sends requests with an HTTP client of the JDK, and
// holds back the body of the response to every request but the probe, until
// the test lets it go. That way, a test can act at the moment when the
// headers of a response have arrived, but the client does not have its body
// yet.
final class BodyHoldingHttpClient extends HttpClient {
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final CountDownLatch bodyRequested = new CountDownLatch(1);
    private final CountDownLatch bodyReleased = new CountDownLatch(1);

    void awaitBodyRequested() throws InterruptedException {
        bodyRequested.await();
    }

    void releaseBody() {
        bodyReleased.countDown();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
        var response = httpClient.sendAsync(request, responseBodyHandler);
        if (request.uri().getPath().equals(FakeServer.PROBE_PATH)) {
            return response;
        }

        return response.<HttpResponse<T>>thenApply(HoldingResponse::new);
    }

    private final class HoldingResponse<T> implements HttpResponse<T> {
        private final HttpResponse<T> response;

        HoldingResponse(HttpResponse<T> response) {
            this.response = response;
        }

        @Override
        public T body() {
            bodyRequested.countDown();
            try {
                bodyReleased.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return response.body();
        }

        @Override
        public int statusCode() {
            return response.statusCode();
        }

        @Override
        public HttpRequest request() {
            return response.request();
        }

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return response.previousResponse();
        }

        @Override
        public HttpHeaders headers() {
            return response.headers();
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return response.sslSession();
        }

        @Override
        public URI uri() {
            return response.uri();
        }

        @Override
        public Version version() {
            return response.version();
        }
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
            throws IOException, InterruptedException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return httpClient.cookieHandler();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return httpClient.connectTimeout();
    }

    @Override
    public Redirect followRedirects() {
        return httpClient.followRedirects();
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return httpClient.proxy();
    }

    @Override
    public SSLContext sslContext() {
        return httpClient.sslContext();
    }

    @Override
    public SSLParameters sslParameters() {
        return httpClient.sslParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return httpClient.authenticator();
    }

    @Override
    public Version version() {
        return httpClient.version();
    }

    @Override
    public Optional<Executor> executor() {
        return httpClient.executor();
    }

    @Override
    public void shutdownNow() {
        httpClient.shutdownNow();
    }
}
