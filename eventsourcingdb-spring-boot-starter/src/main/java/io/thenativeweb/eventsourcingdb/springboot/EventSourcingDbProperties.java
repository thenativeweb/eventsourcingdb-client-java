package io.thenativeweb.eventsourcingdb.springboot;

import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The properties of the client, under the prefix {@code eventsourcingdb}.
 *
 * @param baseUrl the URL of the EventSourcingDB instance, e.g. http://localhost:3000
 * @param apiToken the API token to authenticate with
 */
@ConfigurationProperties("eventsourcingdb")
public record EventSourcingDbProperties(
        @Nullable URI baseUrl, @Nullable String apiToken) {}
