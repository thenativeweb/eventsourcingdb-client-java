package io.thenativeweb.eventsourcingdb.springboot;

import io.thenativeweb.eventsourcingdb.Client;
import io.thenativeweb.eventsourcingdb.ClientOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

/**
 * Auto-configures a client for EventSourcingDB.
 *
 * <p>Exclude it to turn the auto-configuration off, e.g. with {@code
 * @SpringBootApplication(exclude = EventSourcingDbAutoConfiguration.class)}.
 */
@AutoConfiguration
@EnableConfigurationProperties(EventSourcingDbProperties.class)
public final class EventSourcingDbAutoConfiguration {
    EventSourcingDbAutoConfiguration() {}

    @Bean
    @ConditionalOnMissingBean(EventSourcingDbConnectionDetails.class)
    PropertiesEventSourcingDbConnectionDetails eventSourcingDbConnectionDetails(EventSourcingDbProperties properties) {
        return new PropertiesEventSourcingDbConnectionDetails(properties);
    }

    // Spring closes the client with the context, as it calls close() on every
    // bean that is AutoCloseable.
    @Bean
    @ConditionalOnMissingBean
    Client eventSourcingDbClient(
            EventSourcingDbConnectionDetails connectionDetails, ObjectProvider<JsonMapper> jsonMapper) {
        var options = new ClientOptions();

        // The JSON mapper of Spring carries the configuration of the
        // application, e.g. its naming strategy, so the data of events is
        // serialized like everything else the application serializes. Without
        // one, the client uses a JSON mapper of its own.
        var dataMapper = jsonMapper.getIfAvailable();
        if (dataMapper != null) {
            options = options.withDataMapper(dataMapper);
        }

        return new Client(connectionDetails.getBaseUrl(), connectionDetails.getApiToken(), options);
    }
}
