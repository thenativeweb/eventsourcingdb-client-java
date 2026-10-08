package io.thenativeweb.eventsourcingdb.springboot;

import java.net.URI;
import org.springframework.boot.autoconfigure.service.connection.ConnectionDetails;

/**
 * The details to connect to an EventSourcingDB instance with.
 *
 * <p>By default, they come from the {@code eventsourcingdb.*} properties. To take them from somewhere else, define a
 * bean of this type. With {@code @ServiceConnection}, Spring Boot takes them from a test container.
 */
public interface EventSourcingDbConnectionDetails extends ConnectionDetails {
    /** {@return the URL of the instance} */
    URI getBaseUrl();

    /** {@return the API token to authenticate with} */
    String getApiToken();
}
