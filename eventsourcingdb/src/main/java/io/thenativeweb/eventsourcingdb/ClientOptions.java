package io.thenativeweb.eventsourcingdb;

import java.net.http.HttpClient;
import java.time.Duration;
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
 * @param stallTimeout how long a request may receive nothing from the server before it fails, 30 seconds by default
 */
public record ClientOptions(
        @Nullable HttpClient httpClient, @Nullable JsonMapper dataMapper, Duration stallTimeout) {
    private static final Duration DEFAULT_STALL_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Creates options with the given values.
     *
     * @param httpClient the HTTP client to send requests with, or {@code null} to let the client create one of its own
     * @param dataMapper the JSON mapper to serialize and deserialize the data of events with, or {@code null} to let
     *     the client use one of its own
     * @param stallTimeout how long a request may receive nothing from the server before it fails
     * @throws IllegalArgumentException if the stall timeout is zero or negative
     */
    public ClientOptions {
        // A stall timeout of zero or less would fail every request at once.
        if (stallTimeout.isZero() || stallTimeout.isNegative()) {
            throw new IllegalArgumentException("stall timeout must be positive");
        }
    }

    /** Creates options that let the client create an HTTP client and a JSON mapper of its own. */
    public ClientOptions() {
        this(null, null);
    }

    /**
     * Creates options with the given HTTP client and JSON mapper, and the default stall timeout of 30 seconds.
     *
     * @param httpClient the HTTP client to send requests with, or {@code null} to let the client create one of its own
     * @param dataMapper the JSON mapper to serialize and deserialize the data of events with, or {@code null} to let
     *     the client use one of its own
     */
    public ClientOptions(@Nullable HttpClient httpClient, @Nullable JsonMapper dataMapper) {
        this(httpClient, dataMapper, DEFAULT_STALL_TIMEOUT);
    }

    /**
     * {@return a copy of these options that send requests with the given HTTP client}
     *
     * <p>Use this to configure what the client can not configure itself, e.g. a shorter connect timeout, a proxy, or
     * TLS. The client does not close an HTTP client it was given, since it belongs to the caller.
     *
     * @param httpClient the HTTP client to send requests with
     */
    public ClientOptions withHttpClient(HttpClient httpClient) {
        return new ClientOptions(httpClient, dataMapper, stallTimeout);
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
        return new ClientOptions(httpClient, dataMapper, stallTimeout);
    }

    /**
     * {@return a copy of these options that let a request fail once it receives nothing from the server for the given
     * time}
     *
     * <p>The time counts from sending the request to its end, including connecting and waiting for the response, and
     * starts over with everything that arrives, be it a byte, an item, or a heartbeat. It is not a limit for the whole
     * request, so a long stream that keeps receiving items or heartbeats is never cut off, and the time you spend on an
     * item does not count. It applies to every request. A request that stalls ends with a
     * {@link ConnectionStalledException}, except for observing events and running EventQL queries, which end with a
     * {@link HeartbeatTimeoutException} once their stream has started.
     *
     * @param stallTimeout how long a request may receive nothing from the server before it fails, 30 seconds by
     *     default
     * @throws IllegalArgumentException if the stall timeout is zero or negative
     */
    public ClientOptions withStallTimeout(Duration stallTimeout) {
        return new ClientOptions(httpClient, dataMapper, stallTimeout);
    }
}
