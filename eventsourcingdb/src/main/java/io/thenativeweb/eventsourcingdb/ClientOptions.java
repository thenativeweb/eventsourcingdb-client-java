package io.thenativeweb.eventsourcingdb;

import java.net.http.HttpClient;
import org.jspecify.annotations.Nullable;

/**
 * Options for a {@link Client}.
 *
 * <p>Create them with {@link #ClientOptions()}, and set the options you need with the {@code with} methods:
 *
 * <pre>{@code
 * var httpClient = HttpClient.newBuilder()
 *     .connectTimeout(Duration.ofSeconds(5))
 *     .proxy(ProxySelector.of(new InetSocketAddress("proxy.example.com", 8080)))
 *     .build();
 *
 * var client = new Client(baseUrl, apiToken, new ClientOptions()
 *     .withHttpClient(httpClient));
 * }</pre>
 *
 * @param httpClient the HTTP client to send requests with, or {@code null} to let the client create one of its own
 */
public record ClientOptions(@Nullable HttpClient httpClient) {
    /** Creates options that let the client create an HTTP client of its own. */
    public ClientOptions() {
        this(null);
    }

    /**
     * {@return a copy of these options that send requests with the given HTTP client}
     *
     * <p>Use this to configure what the client can not configure itself, e.g. timeouts, a proxy, or TLS. The client
     * does not close an HTTP client it was given, since it belongs to the caller.
     *
     * @param httpClient the HTTP client to send requests with
     */
    public ClientOptions withHttpClient(HttpClient httpClient) {
        return new ClientOptions(httpClient);
    }
}
