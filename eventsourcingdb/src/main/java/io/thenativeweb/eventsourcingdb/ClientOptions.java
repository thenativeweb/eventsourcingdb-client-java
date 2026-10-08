package io.thenativeweb.eventsourcingdb;

import java.net.http.HttpClient;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

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
 * @param dataMapper the JSON mapper to serialize and deserialize the data of events with, or {@code null} to let the
 *     client use one of its own
 */
public record ClientOptions(
        @Nullable HttpClient httpClient, @Nullable JsonMapper dataMapper) {
    /** Creates options that let the client create an HTTP client and a JSON mapper of its own. */
    public ClientOptions() {
        this(null, null);
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
        return new ClientOptions(httpClient, dataMapper);
    }

    /**
     * {@return a copy of these options that serialize and deserialize the data of events with the given JSON mapper}
     *
     * <p>Use this to apply the configuration of your application to the data of events, e.g. its modules or its naming
     * strategy. The mapper applies to the data of events only: the client keeps reading the rest of what the server
     * sends with a JSON mapper of its own, so that the configuration of an application can not break it.
     *
     * @param dataMapper the JSON mapper to serialize and deserialize the data of events with
     */
    public ClientOptions withDataMapper(JsonMapper dataMapper) {
        return new ClientOptions(httpClient, dataMapper);
    }
}
