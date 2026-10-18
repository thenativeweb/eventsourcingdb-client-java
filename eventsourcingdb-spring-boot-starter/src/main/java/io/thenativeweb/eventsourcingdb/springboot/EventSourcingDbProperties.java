package io.thenativeweb.eventsourcingdb.springboot;

import java.net.URI;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * The properties of the client, under the prefix {@code eventsourcingdb}.
 *
 * @param baseUrl the URL of the EventSourcingDB instance, e.g. http://localhost:3000
 * @param apiToken the API token to authenticate with
 * @param stallTimeout how long a request may receive nothing from the server before it fails, e.g. 10s, 30 seconds by
 *     default
 * @see io.thenativeweb.eventsourcingdb.ClientOptions#withStallTimeout(Duration)
 */
@ConfigurationProperties("eventsourcingdb")
public record EventSourcingDbProperties(
        @Nullable URI baseUrl,
        @Nullable String apiToken,
        @Nullable Duration stallTimeout) {
    /**
     * Creates properties with the given values. Spring Boot binds the properties with this constructor; it is marked,
     * since with two constructors, Spring Boot can not tell which one to use.
     *
     * @param baseUrl the URL of the EventSourcingDB instance, e.g. http://localhost:3000
     * @param apiToken the API token to authenticate with
     * @param stallTimeout how long a request may receive nothing from the server before it fails, e.g. 10s, 30 seconds
     *     by default
     */
    @ConstructorBinding
    public EventSourcingDbProperties {}

    /**
     * Creates properties without a stall timeout, so that the client uses its default.
     *
     * @param baseUrl the URL of the EventSourcingDB instance, e.g. http://localhost:3000
     * @param apiToken the API token to authenticate with
     */
    public EventSourcingDbProperties(@Nullable URI baseUrl, @Nullable String apiToken) {
        this(baseUrl, apiToken, null);
    }
}
