package io.thenativeweb.eventsourcingdb.springboot;

import java.net.URI;

// Takes the connection details from the eventsourcingdb.* properties. They are
// checked only when they are used, since with other connection details, e.g.
// from @ServiceConnection, the properties do not need to be set.
final class PropertiesEventSourcingDbConnectionDetails implements EventSourcingDbConnectionDetails {
    private final EventSourcingDbProperties properties;

    PropertiesEventSourcingDbConnectionDetails(EventSourcingDbProperties properties) {
        this.properties = properties;
    }

    @Override
    public URI getBaseUrl() {
        var baseUrl = properties.baseUrl();
        if (baseUrl == null) {
            throw new IllegalStateException("eventsourcingdb.base-url must be set");
        }

        return baseUrl;
    }

    @Override
    public String getApiToken() {
        var apiToken = properties.apiToken();
        if (apiToken == null || apiToken.isBlank()) {
            throw new IllegalStateException("eventsourcingdb.api-token must be set");
        }

        return apiToken;
    }
}
