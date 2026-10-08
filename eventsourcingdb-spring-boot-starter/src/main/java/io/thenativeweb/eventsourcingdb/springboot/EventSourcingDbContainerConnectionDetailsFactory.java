package io.thenativeweb.eventsourcingdb.springboot;

import io.thenativeweb.eventsourcingdb.testcontainers.Container;
import java.net.URI;
import org.springframework.boot.testcontainers.service.connection.ContainerConnectionDetailsFactory;
import org.springframework.boot.testcontainers.service.connection.ContainerConnectionSource;

// Takes the connection details from a test container annotated with
// @ServiceConnection. Spring Boot loads this factory from spring.factories.
// Applications only have the test container and the Testcontainers support of
// Spring Boot in their tests: without the latter, Spring Boot skips the
// factory, as it can not load it, and without the former, the factory skips
// itself, as it requires the class of the test container.
final class EventSourcingDbContainerConnectionDetailsFactory
        extends ContainerConnectionDetailsFactory<Container, EventSourcingDbConnectionDetails> {
    // The class is named as a string, since referencing it would fail exactly
    // when it is missing.
    EventSourcingDbContainerConnectionDetailsFactory() {
        super(ANY_CONNECTION_NAME, "io.thenativeweb.eventsourcingdb.testcontainers.Container");
    }

    @Override
    protected EventSourcingDbConnectionDetails getContainerConnectionDetails(
            ContainerConnectionSource<Container> source) {
        return new ContainerEventSourcingDbConnectionDetails(source);
    }

    private static final class ContainerEventSourcingDbConnectionDetails extends ContainerConnectionDetails<Container>
            implements EventSourcingDbConnectionDetails {
        private ContainerEventSourcingDbConnectionDetails(ContainerConnectionSource<Container> source) {
            super(source);
        }

        @Override
        public URI getBaseUrl() {
            return getContainer().getBaseUrl();
        }

        @Override
        public String getApiToken() {
            return getContainer().getApiToken();
        }
    }
}
